package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Natural-language giveaways: create from assistant, seal candidates, draw without replacement, grant entitlements. */
public final class GiveawayService {
 public record FavoriteResult(boolean favorited,long favoriteCount) {}
 private final GiveawayMapper db;private final InteractionMapper comments;private final FeedMapper posts;private final MaterialMapper materials;
 private final NotificationMapper notifications;private final UserMapper users;private final EligibilityRegistry eligibility;
 private final VisibilityService visibility;private final TransactionTemplate tx;private final ObjectMapper json;
 private final SecureRandom random=new SecureRandom();
 private java.util.function.Supplier<SaleService> sales;

 public GiveawayService(GiveawayMapper db,InteractionMapper comments,FeedMapper posts,MaterialMapper materials,
                        NotificationMapper notifications,UserMapper users,EligibilityRegistry eligibility,
                        VisibilityService visibility,TransactionTemplate tx,ObjectMapper json){
  this.db=db;this.comments=comments;this.posts=posts;this.materials=materials;this.notifications=notifications;
  this.users=users;this.eligibility=eligibility;this.visibility=visibility;this.tx=tx;this.json=json;
 }
 public void setSaleBridgeProvider(java.util.function.Supplier<SaleService> sales){this.sales=sales;}

 public FavoriteResult favorite(long user,long post){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   db.favorite(post,user,System.currentTimeMillis());
   return new FavoriteResult(true,db.favoriteCount(post));
  });
 }
 public FavoriteResult unfavorite(long user,long post){
  visibility.read(user,post).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return tx.execute(status->{
   db.unfavorite(post,user);
   return new FavoriteResult(false,db.favoriteCount(post));
  });
 }

 public String createFromAssistant(long authorId,long postId,long sourceCommentId,AssistantParser.GiveawayCommand cmd)throws Exception{
  var post=posts.post(postId);if(post==null)throw new FeedFailure(404,"POST_NOT_FOUND");
  if(post.authorId()!=authorId)throw new FeedFailure(403,"POST_FORBIDDEN");
  if(cmd==null)throw new FeedFailure(400,"INVALID_GIVEAWAY");
  JsonNode tree=json.readTree(cmd.ruleTreeJson());
  eligibility.validateTreeOrThrow(tree);
  Long reward=resolveRewardVersion(postId,cmd.rewardVersionHint());
  if(reward==null)throw new FeedFailure(409,"REWARD_VERSION_UNAVAILABLE");
  if(cmd.deadlineMs()==null || cmd.deadlineMs()<=System.currentTimeMillis())throw new FeedFailure(400,"INVALID_DEADLINE");
  if(cmd.winnerCount()<=0 || cmd.winnerCount()>100)throw new FeedFailure(400,"INVALID_WINNER_COUNT");
  String license=cmd.rewardLicense()==null?"DOWNLOAD":cmd.rewardLicense();
  if(!Set.of("DOWNLOAD","COMMERCIAL").contains(license))throw new FeedFailure(400,"INVALID_LICENSE");
  var existing=db.bySource(sourceCommentId);
  if(existing!=null)return existing.summaryText()!=null?existing.summaryText():("活动 #"+existing.id()+" 已存在，状态 "+existing.state());

  var row=new GiveawayMapper.GiveawayInsert();
  row.source=sourceCommentId;row.post=postId;row.author=authorId;row.state="OPEN";row.rules=cmd.ruleTreeJson();
  row.tools=json.writeValueAsString(eligibility.enabledTypes());row.tz=cmd.timezone()==null?"Asia/Shanghai":cmd.timezone();
  row.windowStart=cmd.windowStartMs();row.deadline=cmd.deadlineMs();row.winners=cmd.winnerCount();row.reward=reward;
  row.license=license;row.summary=buildSummary(cmd,reward);row.at=System.currentTimeMillis();
  tx.executeWithoutResult(s->{if(db.insert(row)!=1)throw new IllegalStateException("giveaway");});
  long announceId=postAnnounce(postId,sourceCommentId,row.summary);
  tx.executeWithoutResult(s->db.transition(row.id,"OPEN","OPEN",announceId,row.summary,null,null,null,System.currentTimeMillis()));
  return row.summary+"（活动 #"+row.id+" 已开放）";
 }

 public void tick(){
  long now=System.currentTimeMillis();
  for(Long id:db.dueOpen(now)){
   try{seal(id);}catch(Exception e){/* leave OPEN/SEALING for retry */}
   try{draw(id);}catch(Exception e){/* idempotent retry */}
  }
 }

 public void seal(long giveawayId)throws Exception{
  tx.executeWithoutResult(status->{
   var g=db.lock(giveawayId);if(g==null)return;
   if("SEALED".equals(g.state()) || "DRAWN".equals(g.state()) || "COMPLETED".equals(g.state()))return;
   if(!"OPEN".equals(g.state()) && !"SEALING".equals(g.state()))return;
   if(db.transition(g.id(),g.state(),"SEALING",null,null,null,null,null,System.currentTimeMillis())!=1)return;
   try{
    JsonNode tree=json.readTree(g.ruleTreeJson());
    long assistantId=assistantUserId();
    Set<Long> accounts=eligibility.evaluate(tree,g.postId(),g.authorId(),assistantId,g.windowStartMs(),g.deadlineMs());
    long at=System.currentTimeMillis();
    for(Long account:accounts)db.addCandidate(g.id(),account,"{}",at);
    if(db.transition(g.id(),"SEALING","SEALED",null,null,at,null,null,at)!=1)throw new FeedFailure(409,"GIVEAWAY_FENCED");
   }catch(RuntimeException e){
    db.transition(g.id(),"SEALING","OPEN",null,null,null,null,e.getMessage()==null?"seal failed":e.getMessage().substring(0,Math.min(500,e.getMessage().length())),System.currentTimeMillis());
    throw e;
   }catch(Exception e){
    db.transition(g.id(),"SEALING","OPEN",null,null,null,null,e.getMessage(),System.currentTimeMillis());
    throw new IllegalStateException(e);
   }
  });
 }

 public void draw(long giveawayId){
  tx.executeWithoutResult(status->{
   var g=db.lock(giveawayId);if(g==null)return;
   if("DRAWN".equals(g.state()) || "COMPLETED".equals(g.state())){ensureAwards(g);return;}
   if(!"SEALED".equals(g.state()))return;
   var existing=db.winners(g.id());
   if(!existing.isEmpty()){db.transition(g.id(),"SEALED","DRAWN",null,null,null,System.currentTimeMillis(),null,System.currentTimeMillis());ensureAwards(g);return;}
   List<GiveawayMapper.Candidate> candidates=new ArrayList<>(db.candidates(g.id()));
   Collections.shuffle(candidates,random);
   int n=Math.min(g.winnerCount(),candidates.size());
   long at=System.currentTimeMillis();
   for(int i=0;i<n;i++)db.addWinner(g.id(),candidates.get(i).accountId(),i+1,null,at);
   if(db.transition(g.id(),"SEALED","DRAWN",null,null,null,at,null,at)!=1)throw new FeedFailure(409,"GIVEAWAY_FENCED");
   ensureAwards(db.get(g.id()));
  });
 }

 private void ensureAwards(GiveawayMapper.Giveaway g){
  if(g==null || g.rewardVersionId()==null)return;
  for(var w:db.winners(g.id())){
   if(w.entitlementId()!=null)continue;
   SaleService bridge=sales==null?null:sales.get();
   if(bridge!=null)bridge.autoRefundOnGiveawayWin(w.accountId(),g.postId(),g.id());
   var ent=new GiveawayMapper.EntitlementInsert();
   ent.account=w.accountId();ent.post=g.postId();ent.version=g.rewardVersionId();ent.license=g.rewardLicense();
   ent.kind="GIVEAWAY";ent.source=g.id();ent.at=System.currentTimeMillis();
   try{db.insertEntitlement(ent);}catch(org.springframework.dao.DuplicateKeyException e){
    var existing=db.activeEntitlement(w.accountId(),g.postId(),g.rewardVersionId());
    if(existing!=null)ent.id=existing.id();
   }
   if(ent.id>0)db.setWinnerEntitlement(g.id(),w.accountId(),ent.id,System.currentTimeMillis());
   String dedupe="giveaway-win:"+g.id()+":"+w.accountId();
   long synthetic=Math.floorMod(Objects.hash(g.id(),w.accountId()),1_000_000_000)+3_000_000_000L;
   notifications.insertWithMeta(w.accountId(),synthetic,"GIVEAWAY_WIN",g.authorId(),g.postId(),g.sourceCommentId(),
    null,g.rewardVersionId(),dedupe,System.currentTimeMillis());
  }
  if("DRAWN".equals(g.state()))db.transition(g.id(),"DRAWN","COMPLETED",null,null,null,null,null,System.currentTimeMillis());
 }

 public boolean hasEntitlement(long accountId,long postId,long versionId){
  return db.activeEntitlement(accountId,postId,versionId)!=null;
 }

 public record GiveawayView(long id,String state,long deadlineMs,int winnerCount,Long rewardVersionId,String rewardLicense,
                            String summaryText,List<Long> candidateAccountIds,List<Long> winnerAccountIds) {}
 public record EntitlementView(boolean entitled,Long versionId,String license) {}

 public GiveawayView view(long viewer,long giveawayId){
  var g=db.get(giveawayId);if(g==null)throw new FeedFailure(404,"GIVEAWAY_NOT_FOUND");
  visibility.read(viewer,g.postId()).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return toView(g);
 }
 public List<GiveawayView> listForPost(long viewer,long postId,int limit){
  visibility.read(viewer,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  int n=limit<=0?10:Math.min(limit,50);
  return db.forPost(postId,n).stream().map(this::toView).toList();
 }
 public EntitlementView myEntitlement(long viewer,long postId,Long versionId){
  visibility.read(viewer,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  var profile=materials.profile(postId);if(profile==null)throw new FeedFailure(404,"NOT_MATERIAL");
  long vid=versionId==null?(profile.defaultVersionId()==null?0:profile.defaultVersionId()):versionId;
  if(vid<=0)return new EntitlementView(false,null,null);
  var ent=db.activeEntitlement(viewer,postId,vid);
  return ent==null?new EntitlementView(false,vid,null):new EntitlementView(true,vid,ent.license());
 }
 private GiveawayView toView(GiveawayMapper.Giveaway g){
  return new GiveawayView(g.id(),g.state(),g.deadlineMs()==null?0:g.deadlineMs(),g.winnerCount(),g.rewardVersionId(),g.rewardLicense(),
   g.summaryText(),
   db.candidates(g.id()).stream().map(GiveawayMapper.Candidate::accountId).toList(),
   db.winners(g.id()).stream().map(GiveawayMapper.Winner::accountId).toList());
 }

 private Long resolveRewardVersion(long postId,String hint){
  var profile=materials.profile(postId);if(profile==null)return null;
  if(hint==null || "DEFAULT".equalsIgnoreCase(hint) || "最新版".equals(hint))return profile.defaultVersionId();
  try{long id=Long.parseLong(hint);var v=materials.version(id);return v!=null && v.postId()==postId && "READY".equals(v.state())?id:null;}
  catch(Exception e){return profile.defaultVersionId();}
 }
 private String buildSummary(AssistantParser.GiveawayCommand cmd,long rewardVersionId){
  return (cmd.summary()==null?"抽奖活动":cmd.summary())+"；奖励版本 #"+rewardVersionId+"；资格在截止后封池时核验；每账号一次。";
 }
 private long postAnnounce(long postId,long parentCommentId,String body){
  long assistant=assistantUserId();
  String key="giveaway-announce:"+parentCommentId;
  return tx.execute(status->{
   var existing=comments.existingComment(assistant,key);
   if(existing!=null)return existing.id();
   comments.comment(postId,assistant,parentCommentId,null,null,body,key,System.currentTimeMillis());
   return comments.existingComment(assistant,key).id();
  });
 }
 private long assistantUserId(){
  var u=users.byUsername("assistant");
  if(u!=null)return u.id();
  users.insert("assistant","{noop}assistant-system",System.currentTimeMillis());
  long id=users.lastId();users.ensureAuthor(id);return id;
 }
}
