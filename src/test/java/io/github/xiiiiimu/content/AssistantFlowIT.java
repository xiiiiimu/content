package io.github.xiiiiimu.content;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AssistantFlowIT {
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
  r.add("content.media.secret-key",()->"content-secret-key-32-bytes-minimum");
  r.add("content.media.ffmpeg-image",()->"");
  r.add("content.assistant.python-url",()->""); // stub parser
  r.add("content.media.scan-delay-millis",()->"3600000");
 }
 @Autowired MockMvc http;@Autowired JdbcTemplate db;
 @Autowired MaterialService materials;@Autowired InteractionService interactions;@Autowired PublishService publish;
 @Autowired AssistantService assistant;@Autowired FeedbackWatchService feedback;@Autowired GiveawayService giveaways;
 @Autowired RelationshipService follows;@Autowired NotificationService notifications;@Autowired VisibilityService visibility;

 @BeforeEach void wipeAssistantState(){
  db.update("DELETE FROM feedback_notify_log");
  db.update("DELETE FROM feedback_resolutions");
  db.update("DELETE FROM feedback_subscriptions");
  db.update("DELETE FROM giveaway_winners");
  db.update("DELETE FROM giveaway_candidates");
  db.update("DELETE FROM material_entitlements");
  db.update("DELETE FROM giveaways");
  db.update("DELETE FROM assistant_tasks");
  db.update("DELETE FROM post_favorites");
 }

 @Test void searchSubscribeAndGiveawayCorePaths()throws Exception{
  long author=register("as_author","password-a1");
  long viewer=register("as_viewer","password-b1");
  long liker=register("as_liker","password-c1");
  long outsider=register("as_out","password-d1");

  var mat=materials.createDraft(author,"雨夜街景","neon alley",List.of("rain","night"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  var pub=publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  follows.change(viewer,author,true);follows.change(liker,author,true);

  // Ordinary @助手 comment still creates a normal comment even before assistant tick.
  var ask=interactions.comment(viewer,mat.postId(),"@助手 找竖屏、允许商用的雨夜","ask-1","drone",null,null,null);
  assertThat(ask.commentId()).isPositive();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM assistant_tasks WHERE trigger_comment_id=?",Integer.class,ask.commentId())).isEqualTo(1);
  for(int i=0;i<5;i++)assistant.tick();
  String taskState=db.queryForObject("SELECT state FROM assistant_tasks WHERE trigger_comment_id=?",String.class,ask.commentId());
  String taskErr=db.queryForObject("SELECT last_error FROM assistant_tasks WHERE trigger_comment_id=?",String.class,ask.commentId());
  assertThat(taskState).as("last_error=%s",taskErr).isIn("DONE","CLARIFY");
  String reply=db.queryForObject("""
   SELECT body FROM post_comments WHERE parent_id=? AND user_id=(SELECT id FROM users WHERE username='assistant')
   """,String.class,ask.commentId());
  assertThat(reply).contains("#"+mat.postId()).contains("雨夜");

  // Feedback subscribe: READY after link OR link after READY both notify once.
  var feedbackComment=interactions.comment(viewer,mat.postId(),"风噪太大","fb-1","drone",null,mat.versionId(),1000L);
  feedback.subscribe(liker,feedbackComment.commentId());
  feedback.subscribe(liker,feedbackComment.commentId()); // duplicate
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feedback_subscriptions WHERE feedback_comment_id=?",Integer.class,feedbackComment.commentId())).isEqualTo(1);

  var v2=materials.uploadVersion(author,mat.postId(),"video-v2".getBytes(StandardCharsets.UTF_8),"video/mp4");
  for(int i=0;i<8;i++)materials.tick();
  assertThat(db.queryForObject("SELECT state FROM material_versions WHERE id=?",String.class,v2.versionId())).isEqualTo("READY");
  feedback.linkResolution(author,mat.postId(),feedbackComment.commentId(),v2.versionId(),2000L);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feedback_notify_log WHERE feedback_comment_id=? AND new_version_id=?",Integer.class,
   feedbackComment.commentId(),v2.versionId())).isEqualTo(1);
  assertThat(notifications.list(liker,20).stream().anyMatch(n->"FEEDBACK_READY".equals(n.kind()))).isTrue();

  feedback.cancel(liker,feedbackComment.commentId());
  var v3=materials.uploadVersion(author,mat.postId(),"video-v3".getBytes(StandardCharsets.UTF_8),"video/mp4");
  for(int i=0;i<8;i++)materials.tick();
  feedback.linkResolution(author,mat.postId(),feedbackComment.commentId(),v3.versionId(),null);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feedback_notify_log WHERE new_version_id=?",Integer.class,v3.versionId())).isZero();

  // Giveaway: like-only (no comment) can enter; duplicate comments still one seat; concurrent draw stable.
  interactions.like(liker,mat.postId(),"drone");
  giveaways.favorite(viewer,mat.postId());
  long deadline=System.currentTimeMillis()+3_000;
  var gAsk=interactions.comment(author,mat.postId(),"@助手 从点赞过的人里抽1人 截止epoch:"+deadline,"g-1","drone",null,null,null);
  for(int i=0;i<8;i++)assistant.tick();
  String gErr=db.queryForObject("SELECT COALESCE(last_error,'') FROM assistant_tasks WHERE trigger_comment_id=?",String.class,gAsk.commentId());
  Long gid=db.queryForObject("SELECT id FROM giveaways WHERE source_comment_id=?",Long.class,gAsk.commentId());
  assertThat(gid).as("assistant last_error=%s",gErr).isNotNull();
  // Wait past deadline then seal/draw twice.
  while(System.currentTimeMillis()<=deadline)Thread.sleep(50);
  giveaways.seal(gid);giveaways.draw(gid);
  var winners1=db.queryForList("SELECT account_id FROM giveaway_winners WHERE giveaway_id=? ORDER BY draw_rank",Long.class,gid);
  giveaways.draw(gid); // concurrent/replay
  var winners2=db.queryForList("SELECT account_id FROM giveaway_winners WHERE giveaway_id=? ORDER BY draw_rank",Long.class,gid);
  assertThat(winners2).isEqualTo(winners1);
  assertThat(winners1).contains(liker);
  assertThat(winners1).doesNotContain(author);
  assertThat(giveaways.hasEntitlement(liker,mat.postId(),v3.versionId()) || giveaways.hasEntitlement(liker,mat.postId(),
   db.queryForObject("SELECT reward_version_id FROM giveaways WHERE id=?",Long.class,gid))).isTrue();
  long reward=db.queryForObject("SELECT reward_version_id FROM giveaways WHERE id=?",Long.class,gid);
  assertThat(giveaways.hasEntitlement(outsider,mat.postId(),reward)).isFalse();
  assertThatThrownBy(()->materials.signOriginal(outsider,mat.postId(),reward,60_000,null)).isInstanceOf(FeedFailure.class);
  assertThat(materials.signOriginal(liker,mat.postId(),reward,60_000,null).purpose()).isEqualTo("ORIGINAL");

  // ANY / AT_LEAST smoke via eligibility tree create
  long deadline2=System.currentTimeMillis()+3_000;
  var anyAsk=interactions.comment(author,mat.postId(),"@助手 点赞或收藏任一即可抽1人 截止epoch:"+deadline2,"g-2","drone",null,null,null);
  for(int i=0;i<8;i++)assistant.tick();
  Long gid2=db.queryForObject("SELECT id FROM giveaways WHERE source_comment_id=?",Long.class,anyAsk.commentId());
  assertThat(gid2).isNotNull();
  while(System.currentTimeMillis()<=deadline2)Thread.sleep(50);
  giveaways.seal(gid2);giveaways.draw(gid2);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM giveaway_candidates WHERE giveaway_id=?",Integer.class,gid2)).isGreaterThanOrEqualTo(2);

  // Offline still hides material from visibility target path
  follows.offline(author,mat.postId(),pub.postVersion());
  assertThat(visibility.read(viewer,mat.postId())).isEmpty();
 }

 private long register(String user,String pass)throws Exception{
  var body=http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
   .content("{\"username\":\""+user+"\",\"password\":\""+pass+"\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("userId").asLong();
 }
}
