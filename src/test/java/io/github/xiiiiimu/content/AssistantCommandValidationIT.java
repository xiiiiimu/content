package io.github.xiiiiimu.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Hardening A11: whatever the parser returns, Java must not execute an illegal assistant command.
 *
 * Each test hand-builds the parse result (served by a fake /v1/parse, so the real HttpAssistantParser and task path run)
 * and checks the Java outcome. Sample ids refer to docs/perf-evidence/assistant/eval-baseline-20261121.md §5.1 (subscribe,
 * a110–a120) and the r1 smoke run (giveaway, a188–a211). Facts Java can verify itself (target is a feedback comment of this
 * post, author never a candidate, commercial licence allowed by the material, reward version of this post, one entry per
 * account) are checked from the database, never from parser fields; parser-only semantics (cancel/negation, on behalf of
 * others, bulk, unsupported conditions) arrive as explicit fields and are refused or honoured as such.
 *
 * Outcomes: REJECTED = terminal, one assistant reply with the reason, no side effect; CLARIFY = reply asking for the right
 * target; infrastructure errors are not covered here (they still retry).
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantCommandValidationIT {
 static final ObjectMapper JSON=new ObjectMapper();
 static final AtomicReference<String> CURRENT=new AtomicReference<>("{\"intent\":\"UNKNOWN\"}");
 static final AtomicReference<String> LAST_REQUEST=new AtomicReference<>(null);
 static final HttpServer FAKE_PY;
 static {
  try{
   FAKE_PY=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   FAKE_PY.createContext("/v1/parse",ex->{
    LAST_REQUEST.set(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
    byte[] b=CURRENT.get().getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type","application/json");
    ex.sendResponseHeaders(200,b.length);
    try(var os=ex.getResponseBody()){os.write(b);}
   });
   FAKE_PY.start();
  }catch(IOException e){throw new ExceptionInInitializerError(e);}
 }

 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.media.enabled",()->"true");
  r.add("content.media.s3-endpoint",()->"http://"+S3.getHost()+":"+S3.getMappedPort(9090));
  r.add("content.media.bucket",()->"content-media");
  r.add("content.media.secret-key",()->"assistant-validation-test-secret-key-01");
  r.add("content.media.ffmpeg-image",()->"");
  r.add("content.assistant.python-url",()->"http://127.0.0.1:"+FAKE_PY.getAddress().getPort());
  r.add("content.assistant.internal-token",()->"validation-token");
  r.add("content.media.scan-delay-millis",()->"3600000");
 }
 @AfterAll static void stopServer(){FAKE_PY.stop(0);}

 @Autowired MockMvc http;@Autowired JdbcTemplate db;
 @Autowired MaterialService materials;@Autowired InteractionService interactions;@Autowired PublishService publish;
 @Autowired AssistantService assistant;@Autowired FeedbackWatchService feedback;@Autowired GiveawayService giveaways;
 @Autowired RelationshipService follows;

 /** Shared fixture, built once (lazily: containers must be up first). */
 static boolean ready;
 static long author,viewer,other,assistantId;
 static MaterialService.Created commercial,downloadOnly,otherPost;
 static long feedbackC,ordinaryC,assistantC,feedbackOnDownloadOnly;
 static final AtomicInteger SEQ=new AtomicInteger();
 static final long DAY=86_400_000L;

 @BeforeEach void fixture()throws Exception{
  db.update("DELETE FROM feedback_subscriptions");
  db.update("DELETE FROM assistant_tasks");
  if(ready)return;
  String run=Long.toString(System.nanoTime(),36);
  author=register("av_a_"+run,"password-a1");viewer=register("av_v_"+run,"password-b1");other=register("av_o_"+run,"password-c1");
  assistantId=db.queryForObject("SELECT id FROM users WHERE username='assistant'",Long.class);
  follows.change(viewer,author,true);follows.change(other,author,true);follows.change(assistantId,author,true);
  commercial=material(author,"雨夜街景 a11",true);
  downloadOnly=material(author,"雪山 a11",false);
  otherPost=material(author,"另一个帖子 a11",true);
  feedbackC=interactions.comment(other,commercial.postId(),"第 12 秒风噪太大，能降一下吗","fb-c","t",null,commercial.versionId(),null).commentId();
  ordinaryC=interactions.comment(other,commercial.postId(),"好看！求原图","or-c","t",null,null,null).commentId();
  assistantC=interactions.comment(assistantId,commercial.postId(),"找到 2 条可公开引用的素材：…","as-c","t",null,null,null).commentId();
  feedbackOnDownloadOnly=interactions.comment(other,downloadOnly.postId(),"画面偏暗","fb-d","t",null,downloadOnly.versionId(),null).commentId();
  ready=true;
 }

 record Outcome(long commentId,String state,String reply,String lastError,int attempt) {}

 Outcome run(long actor,long postId,Long parent,String text,String parseResult)throws Exception{
  CURRENT.set(parseResult);LAST_REQUEST.set(null);
  long comment=interactions.comment(actor,postId,"@助手 "+text,"a11-"+SEQ.incrementAndGet(),"t",parent,null,null).commentId();
  long task=db.queryForObject("SELECT id FROM assistant_tasks WHERE trigger_comment_id=?",Long.class,comment);
  long until=System.currentTimeMillis()+15_000;
  while(true){
   var t=db.queryForMap("SELECT state,attempt,last_error,reply_comment_id FROM assistant_tasks WHERE id=?",task);
   int attempt=((Number)t.get("attempt")).intValue();String state=String.valueOf(t.get("state"));
   if((attempt>=1 && !"RUNNING".equals(state)) || System.currentTimeMillis()>until){
    Long replyId=t.get("reply_comment_id")==null?null:((Number)t.get("reply_comment_id")).longValue();
    String reply=replyId==null?null:db.queryForObject("SELECT body FROM post_comments WHERE id=?",String.class,replyId);
    db.update("UPDATE assistant_tasks SET next_retry_at=TIMESTAMPADD(DAY,1,CURRENT_TIMESTAMP(6)) WHERE id=?",task); // no stray retries
    return new Outcome(comment,state,reply,(String)t.get("last_error"),attempt);
   }
   assistant.tick();Thread.sleep(20);
  }
 }
 int activeSubs(long account){return db.queryForObject("SELECT COUNT(*) FROM feedback_subscriptions WHERE account_id=? AND state='ACTIVE'",Integer.class,account);}
 int giveawaysFor(long sourceComment){return db.queryForObject("SELECT COUNT(*) FROM giveaways WHERE source_comment_id=?",Integer.class,sourceComment);}

 static String subscribe(long target,String action,String target2){
  return "{\"intent\":\"SUBSCRIBE\",\"subscribe\":{\"feedbackCommentId\":"+target+",\"action\":\""+action+"\",\"target\":\""+target2+"\"},\"unsupportedTools\":[]}";
 }
 static String giveaway(String tree,long deadline,int winners,String hint,String license,Long windowStart,String... unsupported){
  try{
   var g=JSON.createObjectNode();
   g.put("ruleTreeJson",tree);g.put("timezone","Asia/Shanghai");
   if(windowStart==null)g.putNull("windowStartMs");else g.put("windowStartMs",windowStart);
   g.put("deadlineMs",deadline);g.put("winnerCount",winners);g.put("rewardVersionHint",hint);g.put("rewardLicense",license);g.put("summary","a11 test");
   var root=JSON.createObjectNode();root.put("intent","GIVEAWAY");root.set("giveaway",g);
   var u=root.putArray("unsupportedTools");for(String s:unsupported)u.add(s);
   return JSON.writeValueAsString(root);
  }catch(Exception e){throw new IllegalStateException(e);}
 }
 static final String LIKE="{\"op\":\"ALL\",\"children\":[{\"type\":\"LIKE\"}]}";
 static long future(){return System.currentTimeMillis()+DAY;}

 void assertRejected(Outcome o){
  assertThat(o.state()).as("state (last_error=%s, attempt=%s)",o.lastError(),o.attempt()).isEqualTo("REJECTED");
  assertThat(o.reply()).as("one assistant reply giving the reason").isNotBlank();
 }

 // ---------------------------------------------------------------- subscribe

 @Test void controlSubscribeToFeedbackForSelfIsExecuted()throws Exception{
  var o=run(viewer,commercial.postId(),feedbackC,"蹲这条的修改结果",subscribe(feedbackC,"SUBSCRIBE","SELF"));
  assertThat(o.state()).isEqualTo("DONE");
  assertThat(activeSubs(viewer)).isEqualTo(1);
 }

 /** a110 / a111 / a113 reply to an ordinary comment; a112 replies to the assistant's own reply. Java checks the comment row. */
 @ParameterizedTest(name="{0}")
 @CsvSource({"a110,ordinary,蹲这条的修改结果","a111,ordinary,订阅这条反馈","a113,ordinary,蹲","a112,assistant,蹲改进"})
 void subscribeTargetMustBeAFeedbackComment(String id,String kind,String text)throws Exception{
  long target="ordinary".equals(kind)?ordinaryC:assistantC;
  var o=run(viewer,commercial.postId(),target,text,subscribe(target,"SUBSCRIBE","SELF"));
  assertThat(o.state()).as(id).isEqualTo("CLARIFY");
  assertThat(o.reply()).as(id).isNotBlank();
  assertThat(activeSubs(viewer)).as(id+": no subscription").isZero();
 }

 @Test void subscribeTargetMustBelongToThisPost()throws Exception{
  var o=run(viewer,commercial.postId(),feedbackC,"蹲这条",subscribe(feedbackOnDownloadOnly,"SUBSCRIBE","SELF"));
  assertThat(o.state()).isIn("CLARIFY","REJECTED");
  assertThat(activeSubs(viewer)).isZero();
 }

 /** a115 帮 @小王 也蹲 (OTHERS); a118 把所有订阅者都通知一遍 / a119 给所有点赞的人都订阅 (ALL). */
 @ParameterizedTest(name="{0}")
 @CsvSource({"a115,OTHERS,帮 @小王 也蹲一下这条","a118,ALL,把这条的所有订阅者都通知一遍","a119,ALL,给所有点赞的人都订阅这条"})
 void subscribeOnlyForTheRequesterHimself(String id,String target,String text)throws Exception{
  var o=run(viewer,commercial.postId(),feedbackC,text,subscribe(feedbackC,"SUBSCRIBE",target));
  assertRejected(o);
  assertThat(activeSubs(viewer)).as(id).isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feedback_subscriptions",Integer.class)).as(id).isZero();
 }

 /** a116 取消蹲这条 / a117 不用蹲了 / a120 我不想蹲这条: never turned into a subscription; nothing to cancel → no change. */
 @ParameterizedTest(name="{0}")
 @CsvSource({"a116,取消蹲这条","a117,不用蹲了","a120,我不想蹲这条，别通知我"})
 void cancelIntentNeverSubscribes(String id,String text)throws Exception{
  var o=run(viewer,commercial.postId(),feedbackC,text,subscribe(feedbackC,"CANCEL","SELF"));
  assertThat(o.state()).as(id).isEqualTo("DONE");
  assertThat(o.reply()).as(id).isNotBlank();
  assertThat(activeSubs(viewer)).as(id).isZero();
 }

 @Test void cancelIntentCancelsAnExistingSubscription()throws Exception{
  feedback.subscribe(viewer,feedbackC);
  assertThat(activeSubs(viewer)).isEqualTo(1);
  var o=run(viewer,commercial.postId(),feedbackC,"取消蹲这条",subscribe(feedbackC,"CANCEL","SELF"));
  assertThat(o.state()).isEqualTo("DONE");
  assertThat(activeSubs(viewer)).isZero();
  assertThat(db.queryForObject("SELECT state FROM feedback_subscriptions WHERE account_id=? AND feedback_comment_id=?",String.class,viewer,feedbackC)).isEqualTo("CANCELLED");
 }

 @Test void unknownSubscribeActionIsRejected()throws Exception{
  var o=run(viewer,commercial.postId(),feedbackC,"通知所有人",subscribe(feedbackC,"BROADCAST","SELF"));
  assertRejected(o);
  assertThat(activeSubs(viewer)).isZero();
 }

 // ---------------------------------------------------------------- giveaway

 @Test void controlValidGiveawayIsCreated()throws Exception{
  var o=run(author,commercial.postId(),null,"点赞的抽1人送素材",giveaway(LIKE,future(),1,"DEFAULT","DOWNLOAD",null));
  assertThat(o.state()).as("last_error=%s",o.lastError()).isEqualTo("DONE");
  assertThat(giveawaysFor(o.commentId())).isEqualTo(1);
 }

 /** a188 转发 / a189 分享 / a191 评论字数 / a192 关注时长 / a194 @好友 / a202 否定条件 / a208 转售 / a210 作者参与 / a211 多次参与. */
 @ParameterizedTest(name="{0}")
 @CsvSource({"a188,转发","a189,分享","a191,评论字数","a192,关注时长","a194,@好友","a202,未关注（否定条件）","a208,转售授权","a210,作者本人参与","a211,同一人多次参与"})
 void unsupportedConditionRejectsTheWholeGiveaway(String id,String tool)throws Exception{
  var o=run(author,commercial.postId(),null,id,giveaway(LIKE,future(),1,"DEFAULT","DOWNLOAD",null,tool));
  assertRejected(o);
  assertThat(o.reply()).as(id).contains(tool);
  assertThat(giveawaysFor(o.commentId())).as(id).isZero();
 }

 /** a199 至少满足3项：点赞、评论 → AT_LEAST k=3 over 2 conditions. */
 @Test void a199AtLeastKLargerThanConditionsIsRejectedWithReply()throws Exception{
  var o=run(author,commercial.postId(),null,"至少满足3项：点赞、评论，抽1人",
   giveaway("{\"op\":\"AT_LEAST\",\"k\":3,\"children\":[{\"type\":\"LIKE\"},{\"type\":\"COMMENT\"}]}",future(),1,"DEFAULT","DOWNLOAD",null));
  assertRejected(o);
  assertThat(giveawaysFor(o.commentId())).isZero();
 }

 /** a202 / a210 / a211 expressed through rule-node params: only PURCHASE.postId is a known param, anything else is refused. */
 @ParameterizedTest(name="{0}")
 @CsvSource(delimiter='|',value={
  "a202|{\"op\":\"ALL\",\"children\":[{\"type\":\"FOLLOW\",\"params\":{\"negate\":true}}]}",
  "a210|{\"op\":\"ALL\",\"children\":[{\"type\":\"LIKE\",\"params\":{\"includeAuthor\":true}}]}",
  "a211|{\"op\":\"ALL\",\"children\":[{\"type\":\"COMMENT\",\"params\":{\"entriesPerComment\":true}}]}",
  "extra-key|{\"op\":\"ALL\",\"includeAuthor\":true,\"children\":[{\"type\":\"LIKE\"}]}"})
 void unknownRuleParamsAreNotSilentlyIgnored(String id,String tree)throws Exception{
  var o=run(author,commercial.postId(),null,id,giveaway(tree,future(),1,"DEFAULT","DOWNLOAD",null));
  assertRejected(o);
  assertThat(giveawaysFor(o.commentId())).as(id).isZero();
 }

 /** a207 送商用授权 on a material that does not allow commercial use: checked against material_posts, not the parser. */
 @Test void a207CommercialRewardRequiresCommercialMaterial()throws Exception{
  var o=run(author,downloadOnly.postId(),null,"点赞的抽1人送商用授权",giveaway(LIKE,future(),1,"DEFAULT","COMMERCIAL",null));
  assertRejected(o);
  assertThat(giveawaysFor(o.commentId())).isZero();
 }

 @Test void commercialRewardOnCommercialMaterialIsAllowed()throws Exception{
  var o=run(author,commercial.postId(),null,"点赞的抽1人送商用授权",giveaway(LIKE,future(),1,"DEFAULT","COMMERCIAL",null));
  assertThat(o.state()).as("last_error=%s",o.lastError()).isEqualTo("DONE");
  assertThat(giveawaysFor(o.commentId())).isEqualTo(1);
 }

 /** a209 奖品是我另一个帖子的素材: the reward must be a READY version of this post; unknown hints are not defaulted. */
 @ParameterizedTest(name="{0}")
 @CsvSource({"other-post-version","OTHER_POST","另一个帖子"})
 void a209RewardMustBeAVersionOfThisPost(String hint)throws Exception{
  String h="other-post-version".equals(hint)?Long.toString(otherPost.versionId()):hint;
  var o=run(author,commercial.postId(),null,"点赞的抽1人，奖品是我另一个帖子的素材",giveaway(LIKE,future(),1,h,"DOWNLOAD",null));
  assertRejected(o);
  assertThat(giveawaysFor(o.commentId())).isZero();
 }

 /** a210 / a211, Java side: whatever the parser says, the author is never a candidate and an account enters once. */
 @Test void authorNeverCandidateAndOneEntryPerAccount()throws Exception{
  interactions.like(author,commercial.postId(),"t");interactions.like(viewer,commercial.postId(),"t");interactions.like(other,commercial.postId(),"t");
  for(int i=0;i<3;i++)interactions.comment(viewer,commercial.postId(),"再评一次 "+i,"multi-"+SEQ.incrementAndGet(),"t");
  interactions.comment(author,commercial.postId(),"作者自己评论","author-c-"+SEQ.incrementAndGet(),"t");
  var o=run(author,commercial.postId(),null,"点赞或评论的抽2人",
   giveaway("{\"op\":\"ANY\",\"children\":[{\"type\":\"LIKE\"},{\"type\":\"COMMENT\"}]}",future(),2,"DEFAULT","DOWNLOAD",null));
  assertThat(o.state()).as("last_error=%s",o.lastError()).isEqualTo("DONE");
  long gid=db.queryForObject("SELECT id FROM giveaways WHERE source_comment_id=?",Long.class,o.commentId());
  giveaways.seal(gid);
  List<Long> candidates=db.queryForList("SELECT account_id FROM giveaway_candidates WHERE giveaway_id=?",Long.class,gid);
  assertThat(candidates).doesNotContain(author,assistantId);
  assertThat(candidates).doesNotHaveDuplicates();
  assertThat(candidates).contains(viewer,other);
 }

 @ParameterizedTest(name="{0}")
 @CsvSource({"winners-0","winners-101","deadline-past","window-after-deadline"})
 void winnerCountAndTimesMustBeValid(String id)throws Exception{
  long d=future();int w=1;Long ws=null;
  switch(id){
   case "winners-0" -> w=0;
   case "winners-101" -> w=101;
   case "deadline-past" -> d=System.currentTimeMillis()-60_000;
   default -> ws=d+60_000;
  }
  var o=run(author,commercial.postId(),null,id,giveaway(LIKE,d,w,"DEFAULT","DOWNLOAD",ws));
  assertRejected(o);
  assertThat(giveawaysFor(o.commentId())).as(id).isZero();
 }

 @Test void nonAuthorGiveawayIsRejected()throws Exception{
  var o=run(viewer,commercial.postId(),null,"点赞的抽1人",giveaway(LIKE,future(),1,"DEFAULT","DOWNLOAD",null));
  assertRejected(o);
  assertThat(giveawaysFor(o.commentId())).isZero();
 }

 @Test void disabledOrUnknownToolInTreeIsRejectedWithReply()throws Exception{
  var o=run(author,commercial.postId(),null,"转发的抽1人",giveaway("{\"op\":\"ALL\",\"children\":[{\"type\":\"SHARE\"}]}",future(),1,"DEFAULT","DOWNLOAD",null));
  assertRejected(o);
  assertThat(giveawaysFor(o.commentId())).isZero();
 }

 // ---------------------------------------------------------------- parser context

 @Test void contextCarriesParentKindTimeAndLicense()throws Exception{
  long before=System.currentTimeMillis();
  run(viewer,commercial.postId(),feedbackC,"蹲这条",subscribe(feedbackC,"SUBSCRIBE","SELF"));
  JsonNode ctx=JSON.readTree(LAST_REQUEST.get()).path("context");
  assertThat(ctx.path("parentKind").asText()).isEqualTo("feedback");
  assertThat(ctx.path("parentIsFeedback").asBoolean(false)).isTrue();
  assertThat(ctx.path("nowMs").asLong()).isBetween(before,System.currentTimeMillis());
  assertThat(ctx.path("timezone").asText()).isEqualTo("Asia/Shanghai");
  assertThat(ctx.path("postLicenseCommercial").isBoolean()).isTrue();
  assertThat(ctx.path("postLicenseCommercial").asBoolean()).isTrue();
  assertThat(ctx.path("enabledTools").isArray()).isTrue();

  run(viewer,downloadOnly.postId(),null,"找雪山",
   "{\"intent\":\"SEARCH\",\"search\":{\"query\":\"雪山\"},\"unsupportedTools\":[]}");
  ctx=JSON.readTree(LAST_REQUEST.get()).path("context");
  assertThat(ctx.path("parentKind").isNull()).as("no parent").isTrue();
  assertThat(ctx.path("parentIsFeedback").asBoolean(true)).isFalse();
  assertThat(ctx.path("postLicenseCommercial").asBoolean(true)).isFalse();

  run(viewer,commercial.postId(),ordinaryC,"蹲",subscribe(ordinaryC,"SUBSCRIBE","SELF"));
  assertThat(JSON.readTree(LAST_REQUEST.get()).path("context").path("parentKind").asText()).isEqualTo("ordinary");
  run(viewer,commercial.postId(),assistantC,"蹲",subscribe(assistantC,"SUBSCRIBE","SELF"));
  assertThat(JSON.readTree(LAST_REQUEST.get()).path("context").path("parentKind").asText()).isEqualTo("assistant");
 }

 // ---------------------------------------------------------------- fixtures

 private MaterialService.Created material(long owner,String title,boolean commercialLicense)throws Exception{
  var m=materials.createDraft(owner,title,"a11 fixture",List.of("a11"),"PUBLIC_PREVIEW",null,true,commercialLicense,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(owner,m.postId(),UUID.randomUUID().toString(),0);
  return m;
 }
 private long register(String user,String pass)throws Exception{
  var body=http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
   .content("{\"username\":\""+user+"\",\"password\":\""+pass+"\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  return JSON.readTree(body).get("userId").asLong();
 }
}
