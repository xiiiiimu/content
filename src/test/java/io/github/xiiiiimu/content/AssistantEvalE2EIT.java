package io.github.xiiiiimu.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Eval harness, not a regression test: replays recorded Python parse results
 * (assistant-python/eval/run_eval.py --emit-e2e) through the real Java path
 * comment -> assistant_task -> HttpAssistantParser -> AssistantService.execute -> Giveaway/Feedback services,
 * and records which state-changing commands Java actually accepted.
 *
 * Skipped unless -Dassistant.eval.cases=<e2e-cases.jsonl> is given. Output: -Dassistant.eval.out (JSONL).
 * Only harness health is asserted here; illegal-execution counting is done by eval/e2e_summary.py.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantEvalE2EIT {
 static final String CASES=System.getProperty("assistant.eval.cases","");
 static final String OUT=System.getProperty("assistant.eval.out","");
 static final ObjectMapper JSON=new ObjectMapper();
 static final AtomicReference<String> CURRENT=new AtomicReference<>("{\"intent\":\"UNKNOWN\"}");
 static final AtomicReference<String> LAST_REQUEST=new AtomicReference<>(null);
 static final HttpServer FAKE_PY;
 static {
  try{
   FAKE_PY=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   FAKE_PY.createContext("/v1/parse",ex->{
    String reqBody=new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
    LAST_REQUEST.set(reqBody);
    byte[] b=bindPlaceholders(CURRENT.get(),reqBody).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type","application/json");
    ex.sendResponseHeaders(200,b.length);
    try(var os=ex.getResponseBody()){os.write(b);}
   });
   FAKE_PY.start();
  }catch(IOException e){throw new ExceptionInInitializerError(e);}
 }

 /** run_eval.py records parses with placeholder parentCommentId=9001; the rule parser echoes request.parentCommentId,
  *  so bind it to the id Java actually sent. Nothing else in the recorded result is altered. */
 static final long PLACEHOLDER_PARENT=9001L;
 static String bindPlaceholders(String recorded,String request){
  try{
   JsonNode req=JSON.readTree(request);
   ObjectNode res=(ObjectNode)JSON.readTree(recorded);
   if(res.path("subscribe").path("feedbackCommentId").asLong()==PLACEHOLDER_PARENT)
    ((ObjectNode)res.get("subscribe")).set("feedbackCommentId",req.path("parentCommentId"));
   return JSON.writeValueAsString(res);
  }catch(IOException e){return recorded;}
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
  r.add("content.media.secret-key",()->"assistant-eval-test-secret-key-not-for-production-01");
  r.add("content.media.ffmpeg-image",()->"");
  r.add("content.assistant.python-url",()->"http://127.0.0.1:"+FAKE_PY.getAddress().getPort());
  r.add("content.assistant.internal-token",()->"eval-token");
  r.add("content.media.scan-delay-millis",()->"3600000");
 }
 @BeforeAll static void requireCases(){
  Assumptions.assumeTrue(!CASES.isBlank() && !OUT.isBlank(),"set -Dassistant.eval.cases and -Dassistant.eval.out to run the assistant eval E2E");
 }
 @AfterAll static void stopServer(){FAKE_PY.stop(0);}

 @Autowired MockMvc http;@Autowired JdbcTemplate db;
 @Autowired MaterialService materials;@Autowired InteractionService interactions;@Autowired PublishService publish;
 @Autowired RelationshipService follows;

 @Test void replayParsedCommandsThroughJava()throws Exception{
  List<JsonNode> cases=new ArrayList<>();
  for(String line:Files.readAllLines(Path.of(CASES),StandardCharsets.UTF_8))if(!line.isBlank())cases.add(JSON.readTree(line));
  assertThat(cases).isNotEmpty();

  String run=Long.toString(System.currentTimeMillis(),36);
  long author=register("ev_a_"+run,"password-a1");
  long viewer=register("ev_v_"+run,"password-b1");
  long other=register("ev_o_"+run,"password-c1");
  long assistantId=db.queryForObject("SELECT id FROM users WHERE username='assistant'",Long.class);
  follows.change(viewer,author,true);follows.change(other,author,true);follows.change(assistantId,author,true);
  var commercialPost=material(author,"雨夜街景 eval",true);
  var downloadOnlyPost=material(author,"雪山 eval",false);
  Map<Long,Map<String,Long>> parents=new HashMap<>();
  for(var m:List.of(commercialPost,downloadOnlyPost)){
   Map<String,Long> p=new HashMap<>();
   p.put("feedback",interactions.comment(other,m.postId(),"第 12 秒风噪太大，能降一下吗","fb-"+run+"-"+m.postId(),"drone",null,m.versionId(),null).commentId());
   p.put("ordinary",interactions.comment(other,m.postId(),"好看！求原图","or-"+run+"-"+m.postId(),"drone",null,null,null).commentId());
   p.put("assistant",interactions.comment(assistantId,m.postId(),"找到 2 条可公开引用的素材：…","as-"+run+"-"+m.postId(),"drone",null,null,null).commentId());
   parents.put(m.postId(),p);
  }

  Path out=Path.of(OUT);Files.createDirectories(out.getParent());
  int harnessErrors=0;
  try(var w=Files.newBufferedWriter(out,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)){
   int i=0;
   for(JsonNode c:cases){
    i++;
    JsonNode ctx=c.path("context");
    boolean asAuthor=ctx.path("actorIsAuthor").asBoolean();
    long actor=asAuthor?author:viewer;
    JsonNode lic=ctx.path("postLicenseCommercial"); // null for non-giveaway rows: license of this post is irrelevant
    var mat=(lic.isBoolean() && !lic.booleanValue())?downloadOnlyPost:commercialPost;
    Long parent=ctx.path("parent").isNull()||ctx.path("parent").isMissingNode()?null:parents.get(mat.postId()).get(ctx.path("parent").path("kind").asText());

    db.update("DELETE FROM feedback_subscriptions");
    CURRENT.set(JSON.writeValueAsString(c.path("parseResult")));
    LAST_REQUEST.set(null);
    var comment=interactions.comment(actor,mat.postId(),c.path("text").asText(),"ev-"+run+"-"+i,"drone",parent,null,null);
    long commentId=comment.commentId();
    Long taskId=db.query("SELECT id FROM assistant_tasks WHERE trigger_comment_id=?",rs->rs.next()?rs.getLong(1):null,commentId);

    ObjectNode rec=JSON.createObjectNode();
    rec.put("id",c.path("id").asText());
    rec.put("commentId",commentId);
    rec.put("postLicenseCommercial",mat==commercialPost);
    if(taskId==null){
     rec.put("harness","NO_TASK");harnessErrors++;
     w.write(JSON.writeValueAsString(rec));w.newLine();continue;
    }
    Map<String,Object> task=waitOneAttempt(taskId);
    String sent=LAST_REQUEST.get();
    if(sent!=null){
     JsonNode req=JSON.readTree(sent);
     rec.put("sentActorIsAuthor",req.path("actorIsAuthor").asBoolean());
     rec.put("sentParentCommentId",req.path("parentCommentId").isNull()?null:req.path("parentCommentId").asLong());
     if(req.path("actorIsAuthor").asBoolean()!=asAuthor)rec.put("harness","ACTOR_MISMATCH");
    }else{
     rec.put("harness","PARSER_NOT_CALLED");
    }
    rec.put("taskState",String.valueOf(task.get("state")));
    rec.put("taskIntent",task.get("intent")==null?null:String.valueOf(task.get("intent")));
    rec.put("lastError",task.get("last_error")==null?null:String.valueOf(task.get("last_error")));
    Long replyId=task.get("reply_comment_id")==null?null:((Number)task.get("reply_comment_id")).longValue();
    String reply=replyId==null?null:db.queryForObject("SELECT body FROM post_comments WHERE id=?",String.class,replyId);
    rec.put("reply",reply);
    rec.put("searchExecuted",reply!=null && (reply.startsWith("找到 ") || reply.startsWith("未找到同时满足")));

    var g=db.queryForList("SELECT id,rule_tree_json,deadline_ms,winner_count,reward_license,reward_version_id,state FROM giveaways WHERE source_comment_id=?",commentId);
    rec.put("giveawayCreated",!g.isEmpty());
    if(!g.isEmpty()){
     var row=g.get(0);
     ObjectNode gv=rec.putObject("giveaway");
     gv.put("ruleTreeJson",String.valueOf(row.get("rule_tree_json")));
     gv.put("deadlineMs",((Number)row.get("deadline_ms")).longValue());
     gv.put("winnerCount",((Number)row.get("winner_count")).intValue());
     gv.put("rewardLicense",String.valueOf(row.get("reward_license")));
     gv.put("state",String.valueOf(row.get("state")));
    }
    int subs=parent==null?0:db.queryForObject("SELECT COUNT(*) FROM feedback_subscriptions WHERE account_id=? AND feedback_comment_id=? AND state='ACTIVE'",Integer.class,actor,parent);
    int anySubs=db.queryForObject("SELECT COUNT(*) FROM feedback_subscriptions WHERE account_id=? AND state='ACTIVE'",Integer.class,actor);
    rec.put("subscriptionCreated",anySubs>0);
    rec.put("subscriptionOnParent",subs>0);
    if(rec.has("harness"))harnessErrors++;
    w.write(JSON.writeValueAsString(rec));w.newLine();
    // stop retries of failed tasks from hitting the next case's canned parse result
    db.update("DELETE FROM assistant_tasks WHERE id=?",taskId);
   }
  }
  assertThat(harnessErrors).as("harness errors, see %s",OUT).isZero();
 }

 private Map<String,Object> waitOneAttempt(long taskId)throws Exception{
  long until=System.currentTimeMillis()+15_000;
  while(true){
   var t=db.queryForMap("SELECT state,intent,attempt,last_error,reply_comment_id FROM assistant_tasks WHERE id=?",taskId);
   int attempt=((Number)t.get("attempt")).intValue();
   String state=String.valueOf(t.get("state"));
   if(attempt>=1 && !"RUNNING".equals(state))return t;
   if(System.currentTimeMillis()>until)return t;
   applicationTick();
   Thread.sleep(20);
  }
 }
 @Autowired AssistantService assistant;
 private void applicationTick(){assistant.tick();}

 private MaterialService.Created material(long author,String title,boolean commercial)throws Exception{
  var m=materials.createDraft(author,title,"eval fixture",List.of("eval"),"PUBLIC_PREVIEW",null,true,commercial,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,m.postId(),UUID.randomUUID().toString(),0);
  return m;
 }
 private long register(String user,String pass)throws Exception{
  var body=http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
   .content("{\"username\":\""+user+"\",\"password\":\""+pass+"\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  return JSON.readTree(body).get("userId").asLong();
 }
}
