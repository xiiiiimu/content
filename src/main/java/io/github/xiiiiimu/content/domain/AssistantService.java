package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owns assistant_task lifecycle: enqueue from comments, claim/dispatch, execute structured commands. */
public final class AssistantService {
 public static final String MENTION_PATTERN="(?i).*@(助手|assistant).*";
 public record TaskView(long taskId,String state,String intent,Long replyCommentId) {}
 public record MaterialCard(long postId,String title,String description,List<String> tags,String aspectRatio,
                            boolean licenseDownload,boolean licenseCommercial,String matchReason) {}

 private final AssistantMapper db;private final InteractionMapper comments;private final FeedMapper posts;private final UserMapper users;
 private final MaterialMapper materials;private final MaterialService materialService;private final VisibilityService visibility;
 private final TransactionTemplate tx;private final RelationshipService events;private final AssistantParser parser;
 private final FeedbackWatchService feedback;private final GiveawayService giveaways;private final ObjectMapper json;
 private final long assistantUserId;

 public AssistantService(AssistantMapper db,InteractionMapper comments,FeedMapper posts,UserMapper users,MaterialMapper materials,
                         MaterialService materialService,VisibilityService visibility,TransactionTemplate tx,RelationshipService events,
                         AssistantParser parser,FeedbackWatchService feedback,GiveawayService giveaways,ObjectMapper json){
  this.db=db;this.comments=comments;this.posts=posts;this.users=users;this.materials=materials;this.materialService=materialService;
  this.visibility=visibility;this.tx=tx;this.events=events;this.parser=parser;this.feedback=feedback;this.giveaways=giveaways;this.json=json;
  this.assistantUserId=ensureAssistantUser();
 }

 public boolean looksLikeAssistant(String body){return body!=null && body.matches(MENTION_PATTERN);}

 /** Called inside the comment transaction after CommentCreated is emitted. */
 public Long enqueueFromComment(long postId,long commentId,long actorUserId,Long parentCommentId,String body){
  if(!looksLikeAssistant(body))return null;
  var row=new AssistantMapper.TaskInsert();
  row.trigger=commentId;row.post=postId;row.actor=actorUserId;row.parent=parentCommentId;row.body=body;row.at=System.currentTimeMillis();
  if(db.insertTask(row)!=1)throw new IllegalStateException("assistant task");
  events.emit("AssistantTaskCreated",postId,Map.of("taskId",row.id,"commentId",commentId,"actorUserId",actorUserId,"postId",postId,"occurredAtMs",row.at));
  return row.id;
 }

 public void tick(){
  var claimed=tx.execute(status->{
   var task=db.claimable();if(task==null)return null;
   long nextToken=Math.addExact(task.token(),1);
   if(db.claim(task.id())!=1)return null;
   return db.task(task.id())==null?null:new AssistantMapper.Task(task.id(),task.triggerCommentId(),task.postId(),task.actorUserId(),
    task.parentCommentId(),task.body(),task.intent(),"RUNNING",task.attempt()+1,nextToken,task.replyCommentId(),task.createdAtMs());
  });
  if(claimed==null)return;
  long token=claimed.token();
  try{
   var post=posts.post(claimed.postId());
   boolean author=post!=null && post.authorId()==claimed.actorUserId();
   var parsed=parser.parse(new AssistantParser.Request(claimed.id(),claimed.postId(),claimed.actorUserId(),claimed.parentCommentId(),
    claimed.body(),author,parserContext(claimed.postId(),claimed.parentCommentId())));
   Outcome outcome=execute(claimed,parsed);
   Long replyId=postAssistantReply(claimed.postId(),claimed.triggerCommentId(),outcome.reply());
   tx.executeWithoutResult(s->{
    if(db.complete(claimed.id(),token,outcome.state(),parsed.intent(),toJson(parsed),replyId)!=1)throw new FeedFailure(409,"ASSISTANT_FENCED");
   });
  }catch(Exception e){
   String msg=e.getMessage()==null?"error":e.getMessage();
   tx.executeWithoutResult(s->db.fail(claimed.id(),token,Math.min(60,1<<Math.min(5,claimed.attempt())),msg.substring(0,Math.min(500,msg.length()))));
  }
 }

 /** Terminal task state plus the one reply posted under the trigger comment. */
 record Outcome(String state,String reply) {}

 /**
  * Hardening A11: the parse result is a proposal. Unsupported conditions reject the whole command; every 4xx FeedFailure
  * raised by the services' own checks becomes a terminal REJECTED (or CLARIFY for a wrong subscribe target) with one reply
  * giving the reason, instead of a retried failure with no reply. Infrastructure errors still propagate and retry.
  */
 private Outcome execute(AssistantMapper.Task task,AssistantParser.Result parsed)throws Exception{
  if(parsed.unsupportedTools()!=null && !parsed.unsupportedTools().isEmpty())
   return new Outcome("REJECTED","暂不支持条件："+String.join(",",parsed.unsupportedTools())+"。活动未开放。");
  if(parsed.clarify()!=null)return new Outcome("CLARIFY",parsed.clarify().question());
  try{
   return switch(parsed.intent()){
    case "SEARCH" -> new Outcome("DONE",renderSearch(task.actorUserId(),parsed.search()));
    case "SUBSCRIBE" -> new Outcome("DONE",feedback.fromAssistant(task.actorUserId(),task.postId(),parsed.subscribe(),assistantUserId));
    case "GIVEAWAY" -> new Outcome("DONE",giveaways.createFromAssistant(task.actorUserId(),task.postId(),task.triggerCommentId(),parsed.giveaway()));
    default -> new Outcome("CLARIFY","暂未识别意图，请说明找素材、蹲改进或抽奖。");
   };
  }catch(FeedFailure f){
   if(f.status>=500)throw f;
   return new Outcome(CLARIFY_CODES.contains(f.code)?"CLARIFY":"REJECTED",rejection(f.code));
  }
 }

 private static final Set<String> CLARIFY_CODES=Set.of("NOT_A_FEEDBACK_COMMENT");
 private static final Map<String,String> REASONS=Map.ofEntries(
  Map.entry("NOT_A_FEEDBACK_COMMENT","只能订阅本帖带素材版本的反馈评论，请在目标反馈下回复「@助手 蹲这条的修改结果」。"),
  Map.entry("SUBSCRIBE_SELF_ONLY","只能为你自己订阅或取消订阅，不能替他人或批量操作。未执行。"),
  Map.entry("UNSUPPORTED_SUBSCRIBE_ACTION","只支持订阅或取消订阅反馈的改进结果。未执行。"),
  Map.entry("MISSING_COMMAND","指令不完整，未执行。"),
  Map.entry("POST_FORBIDDEN","只有作者可以发起抽奖。活动未创建。"),
  Map.entry("INVALID_GIVEAWAY","抽奖指令不完整。活动未创建。"),
  Map.entry("INVALID_RULE_TREE","参与条件无法识别。活动未创建。"),
  Map.entry("INVALID_RULE_OP","参与条件的组合方式无法识别。活动未创建。"),
  Map.entry("UNKNOWN_RULE_FIELD","参与条件包含暂不支持的限定。活动未创建。"),
  Map.entry("DUPLICATE_RULE_CONDITION","参与条件重复。活动未创建。"),
  Map.entry("INVALID_AT_LEAST_K","「至少满足 N 项」与列出的条件数不符。活动未创建。"),
  Map.entry("UNKNOWN_ELIGIBILITY_TOOL","包含暂不支持的参与条件。活动未创建。"),
  Map.entry("ELIGIBILITY_TOOL_DISABLED","包含未开放的参与条件。活动未创建。"),
  Map.entry("REWARD_VERSION_UNAVAILABLE","奖品必须是本帖已就绪的素材版本。活动未创建。"),
  Map.entry("COMMERCIAL_LICENSE_NOT_ALLOWED","本素材不允许商用，不能以商用授权作为奖品。活动未创建。"),
  Map.entry("INVALID_LICENSE","奖品许可只能是下载或商用。活动未创建。"),
  Map.entry("INVALID_DEADLINE","截止时间必须晚于当前时间。活动未创建。"),
  Map.entry("INVALID_WINDOW","参与开始时间必须早于截止时间。活动未创建。"),
  Map.entry("INVALID_WINNER_COUNT","中奖人数须在 1–100 之间。活动未创建。"),
  Map.entry("POST_NOT_VISIBLE","目标不存在或不可见。未执行。"),
  Map.entry("POST_NOT_FOUND","目标不存在或不可见。未执行。"),
  Map.entry("FEEDBACK_NOT_FOUND","目标反馈不存在。未执行。"));
 private static String rejection(String code){return REASONS.getOrDefault(code,"指令未通过校验（"+code+"），未执行。");}

 /** A11: facts the parser cannot look up itself. Values are read from the database, not from the comment text. */
 private Map<String,Object> parserContext(long postId,Long parentCommentId){
  Map<String,Object> ctx=new LinkedHashMap<>();
  String kind=null;
  if(parentCommentId!=null){
   var parent=comments.commentById(parentCommentId);
   if(parent!=null)kind=parent.userId()==assistantUserId?"assistant":parent.materialVersionId()!=null?"feedback":"ordinary";
  }
  ctx.put("parentKind",kind);
  ctx.put("parentIsFeedback","feedback".equals(kind));
  ctx.put("nowMs",System.currentTimeMillis());
  ctx.put("timezone",TIMEZONE);
  var profile=materials.profile(postId);
  ctx.put("postLicenseCommercial",profile!=null && profile.licenseCommercial());
  ctx.put("enabledTools",List.copyOf(giveaways.enabledTools()));
  return ctx;
 }
 static final String TIMEZONE="Asia/Shanghai";

 private String renderSearch(long viewer,AssistantParser.SearchCommand cmd)throws Exception{
  if(cmd==null)return "缺少搜索条件。";
  var page=materialService.discover(viewer,cmd.query(),cmd.tag(),null,null,20);
  List<MaterialCard> cards=new ArrayList<>();
  for(var item:page.items()){
   var profile=materials.profile(item.postId());
   if(profile==null)continue;
   if(cmd.aspectRatio()!=null && !cmd.aspectRatio().equalsIgnoreCase(nullToUnknown(profile.aspectRatio())))continue;
   if(cmd.licenseDownload()!=null && cmd.licenseDownload() && !profile.licenseDownload())continue;
   if(cmd.licenseCommercial()!=null && cmd.licenseCommercial() && !profile.licenseCommercial())continue;
   // Public assistant reply: only PUBLIC_PREVIEW materials; re-check visibility.
   if(!"PUBLIC_PREVIEW".equals(profile.shareMode()))continue;
   if(visibility.read(viewer,item.postId()).isEmpty())continue;
   cards.add(new MaterialCard(item.postId(),item.title(),item.description(),item.tags(),nullToUnknown(profile.aspectRatio()),
    profile.licenseDownload(),profile.licenseCommercial(),matchReason(cmd)));
   if(cards.size()>=5)break;
  }
  if(cards.isEmpty())return "未找到同时满足条件的公开素材。已检查关键词/标签与结构化画幅、下载及商用许可。";
  StringBuilder sb=new StringBuilder("找到 ").append(cards.size()).append(" 条可公开引用的素材：\n");
  for(var c:cards){
   sb.append("- #").append(c.postId()).append(' ').append(c.title())
    .append(" [").append(c.aspectRatio()).append("]")
    .append(c.licenseCommercial()?" 可商用":"")
    .append(c.licenseDownload()?" 可下载":"")
    .append(" /posts/").append(c.postId()).append('\n');
  }
  return sb.toString().trim();
 }

 private Long postAssistantReply(long postId,long parentCommentId,String body){
  String key="assistant-reply:"+parentCommentId+":"+UUID.nameUUIDFromBytes(body.getBytes(StandardCharsets.UTF_8));
  return tx.execute(status->{
   var existing=comments.existingComment(assistantUserId,key);
   if(existing!=null)return existing.id();
   comments.comment(postId,assistantUserId,parentCommentId,null,null,body,key,System.currentTimeMillis());
   return comments.existingComment(assistantUserId,key).id();
  });
 }

 /** Hardening A8: only the task's requester or the author of its post may read it; anyone else sees "not found". */
 public TaskView status(long viewer,long taskId){
  var t=db.task(taskId);if(t==null)throw new FeedFailure(404,"ASSISTANT_TASK_NOT_FOUND");
  if(viewer!=t.actorUserId()){
   var post=posts.post(t.postId());
   if(post==null || post.authorId()!=viewer)throw new FeedFailure(404,"ASSISTANT_TASK_NOT_FOUND");
  }
  return new TaskView(t.id(),t.state(),t.intent(),t.replyCommentId());
 }

 private long ensureAssistantUser(){
  var u=users.byUsername("assistant");
  if(u!=null)return u.id();
  users.insert("assistant","{noop}assistant-system",System.currentTimeMillis());
  long id=users.lastId();users.ensureAuthor(id);return id;
 }
 private String toJson(Object o){try{return json.writeValueAsString(o);}catch(Exception e){return "{}";}}
 private static String matchReason(AssistantParser.SearchCommand cmd){
  List<String> parts=new ArrayList<>();
  if(cmd.query()!=null)parts.add("q="+cmd.query());
  if(cmd.tag()!=null)parts.add("tag="+cmd.tag());
  if(cmd.aspectRatio()!=null)parts.add("aspect="+cmd.aspectRatio());
  if(Boolean.TRUE.equals(cmd.licenseDownload()))parts.add("download");
  if(Boolean.TRUE.equals(cmd.licenseCommercial()))parts.add("commercial");
  return String.join(",",parts);
 }
 private static String nullToUnknown(String v){return v==null||v.isBlank()?"UNKNOWN":v;}
}
