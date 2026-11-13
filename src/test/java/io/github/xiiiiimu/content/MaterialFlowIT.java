package io.github.xiiiiimu.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Batch-1 material loop: A publish → B discover by title/tag → preview + comment @8s →
 * A new version + accept → B still resolves old version @8s; offline/password/fail-safe.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers @Import(MaterialFlowIT.Hooks.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MaterialFlowIT {
 static final AtomicReference<Runnable> BEFORE_MEDIA=new AtomicReference<>();
 @TestConfiguration static class Hooks {
  @Bean @Primary PipelineObserver materialFlowObserver(){
   return new PipelineObserver(){@Override public void beforeMediaProcess(long taskId){var r=BEFORE_MEDIA.getAndSet(null);if(r!=null)r.run();}};
  }
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
  r.add("content.media.secret-key",()->"content-secret-key-32-bytes-minimum");
  r.add("content.media.ffmpeg-image",()->"");
  r.add("content.media.scan-delay-millis",()->"3600000");
 }
 @Autowired MockMvc http;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 @Autowired MaterialService materials;@Autowired PublishService publish;@Autowired InteractionService interactions;
 @Autowired RelationshipService follows;@Autowired VisibilityService visibility;@Autowired ObjectStore store;
 @BeforeEach void resetHooks(){
  BEFORE_MEDIA.set(null);
  // Scheduler also calls materials.tick(); clear leftover versions so claimable cannot steal ticks across tests.
  db.update("DELETE FROM material_tags");
  db.update("DELETE FROM material_versions");
  db.update("DELETE FROM material_profiles");
 }

 @Test void materialDiscoverPreviewTimestampCommentNewVersionAcceptOfflinePassword()throws Exception{
  var a=register("mat_alice","password-a1");
  var b=register("mat_bob","password-b1");
  String tokenA=a.get("token").asText();String tokenB=b.get("token").asText();
  long idA=a.get("userId").asLong();long idB=b.get("userId").asLong();
  follows.change(idB,idA,true);

  var created=materials.createDraft(idA,"Sunset drone clip","coastal wind",List.of("drone","sunset"),
   "PASSWORD_ORIGINAL","share-secret",true,false,"video-v1".getBytes(StandardCharsets.UTF_8),"video/mp4");
  long postId=created.postId();long v1=created.versionId();
  for(int i=0;i<8;i++)materials.tick();
  assertThat(db.queryForObject("SELECT state FROM material_versions WHERE id=?",String.class,v1)).isEqualTo("READY");
  assertThat(db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,postId)).isEqualTo(v1);

  long publishedAtBefore=Optional.ofNullable(db.queryForObject("SELECT published_at_ms FROM posts WHERE id=?",Long.class,postId)).orElse(0L);
  var pub=publish.publish(idA,postId,UUID.randomUUID().toString(),0);
  assertThat(pub.postId()).isEqualTo(postId);
  long publishedAt=db.queryForObject("SELECT published_at_ms FROM posts WHERE id=?",Long.class,postId);
  assertThat(publishedAt).isGreaterThan(publishedAtBefore);
  String mode=db.queryForObject("SELECT delivery_mode FROM posts WHERE id=?",String.class,postId);
  Long epoch=db.queryForObject("SELECT delivery_epoch FROM posts WHERE id=?",Long.class,postId);

  var discover=json(http.perform(get("/api/materials/discover").param("q","Sunset").param("tag","drone").header("Authorization","Bearer "+tokenB))
   .andExpect(status().isOk()).andReturn());
  assertThat(discover.get("items").toString()).contains("Sunset drone clip");

  var preview=materials.signPreview(idB,postId,v1,60_000,null);
  assertThat(preview.url()).contains("/api/media/objects");

  var comment=interactions.comment(idB,postId,"cut wind noise here","mat-c1","drone",null,v1,8000L);
  assertThat(comment.materialVersionId()).isEqualTo(v1);
  assertThat(comment.offsetMs()).isEqualTo(8000L);

  // Failed new version via real tick (missing object) must not steal default
  var failed=materials.uploadVersion(idA,postId,"bad".getBytes(StandardCharsets.UTF_8),"video/mp4");
  db.update("UPDATE material_versions SET raw_object_key='material/missing/raw',attempt=4 WHERE id=?",failed.versionId());
  for(int i=0;i<8;i++)materials.tick();
  assertThat(db.queryForObject("SELECT state FROM material_versions WHERE id=?",String.class,failed.versionId())).isEqualTo("FAILED");
  assertThat(db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,postId)).isEqualTo(v1);

  assertThatThrownBy(()->interactions.comment(idB,postId,"too late","mat-bad-off","drone",null,v1,9_999_999L))
   .isInstanceOf(FeedFailure.class);

  var v2c=materials.uploadVersion(idA,postId,"video-v2".getBytes(StandardCharsets.UTF_8),"video/mp4");
  for(int i=0;i<8;i++)materials.tick();
  long v2=v2c.versionId();
  assertThat(db.queryForObject("SELECT state FROM material_versions WHERE id=?",String.class,v2)).isEqualTo("READY");
  assertThat(db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,postId)).isEqualTo(v2);
  assertThat(db.queryForObject("SELECT published_at_ms FROM posts WHERE id=?",Long.class,postId)).isEqualTo(publishedAt);
  assertThat(db.queryForObject("SELECT delivery_mode FROM posts WHERE id=?",String.class,postId)).isEqualTo(mode);
  assertThat(db.queryForObject("SELECT delivery_epoch FROM posts WHERE id=?",Long.class,postId)).isEqualTo(epoch);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='PostPublished' AND aggregate_id=?",Long.class,postId)).isEqualTo(1);

  var accepted=interactions.accept(idA,postId,comment.commentId());
  assertThat(accepted.acceptedAtMs()).isNotNull();

  var old=db.queryForMap("SELECT material_version_id,offset_ms FROM post_comments WHERE id=?",comment.commentId());
  assertThat(((Number)old.get("material_version_id")).longValue()).isEqualTo(v1);
  assertThat(((Number)old.get("offset_ms")).longValue()).isEqualTo(8000L);
  var still=materials.requireAccessibleVersion(idB,postId,v1);
  assertThat(still.revision()).isEqualTo(1);
  var jump=materials.signPreview(idB,postId,v1,60_000,null);
  assertThat(jump.purpose()).isEqualTo("PREVIEW");

  assertThatThrownBy(()->materials.unlock(idB,postId,"wrong-password")).isInstanceOf(FeedFailure.class);
  var unlock=materials.unlock(idB,postId,"share-secret");
  var original=materials.signOriginal(idB,postId,v1,60_000,unlock.grant());
  assertThat(original.purpose()).isEqualTo("ORIGINAL");
  materials.updateShare(idA,postId,null,null,true,null,null);
  assertThatThrownBy(()->materials.signOriginal(idB,postId,v1,60_000,unlock.grant())).as("revoke bumps share_epoch").isInstanceOf(FeedFailure.class);

  // Ordinary text post still works
  http.perform(post("/api/posts").header("Authorization","Bearer "+tokenA).contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"plain text\"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));

  interactions.dislike(idB,postId);
  interactions.report(idB,postId,"spam","", "{\"versionId\":"+v1+"}");
  var stats=interactions.stats(postId);
  assertThat(stats.dislikes()).isEqualTo(1);
  assertThat(stats.reports()).isEqualTo(1);

  long ver=db.queryForObject("SELECT version FROM posts WHERE id=?",Long.class,postId);
  follows.offline(idA,postId,ver);
  assertThat(visibility.read(idB,postId)).isEmpty();
  http.perform(get("/api/materials/discover").param("q","Sunset").header("Authorization","Bearer "+tokenB))
   .andExpect(status().isOk())
   .andExpect(result->assertThat(result.getResponse().getContentAsString()).doesNotContain("\"postId\":"+postId));
  assertThatThrownBy(()->materials.signPreview(idB,postId,v1,60_000,null)).isInstanceOf(FeedFailure.class);
  assertThatThrownBy(()->materials.detail(idB,postId)).isInstanceOf(FeedFailure.class);
 }

 @Test void laterReadyOfOlderRevisionDoesNotRegressDefaultVersion()throws Exception{
  var a=register("mat_rev_alice","password-a1");
  long idA=a.get("userId").asLong();
  var created=materials.createDraft(idA,"Rev race","",List.of("race"),"PUBLIC_PREVIEW",null,false,false,
   "video-v1".getBytes(StandardCharsets.UTF_8),"video/mp4");
  long postId=created.postId();
  for(int i=0;i<8;i++)materials.tick();
  long v1=created.versionId();
  assertThat(db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,postId)).isEqualTo(v1);

  var v2c=materials.uploadVersion(idA,postId,"video-v2".getBytes(StandardCharsets.UTF_8),"video/mp4");
  var v3c=materials.uploadVersion(idA,postId,"video-v3".getBytes(StandardCharsets.UTF_8),"video/mp4");
  long v2=v2c.versionId();long v3=v3c.versionId();
  // Defer older pending revision so the worker processes v3 first.
  db.update("UPDATE material_versions SET next_retry_at=TIMESTAMPADD(HOUR,1,CURRENT_TIMESTAMP(6)) WHERE id=?",v2);
  for(int i=0;i<8;i++)materials.tick();
  assertThat(db.queryForObject("SELECT state FROM material_versions WHERE id=?",String.class,v3)).isEqualTo("READY");
  assertThat(db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,postId)).isEqualTo(v3);

  db.update("UPDATE material_versions SET next_retry_at=CURRENT_TIMESTAMP(6),state='PENDING',lease_until=NULL WHERE id=?",v2);
  for(int i=0;i<8;i++)materials.tick();
  assertThat(db.queryForObject("SELECT state FROM material_versions WHERE id=?",String.class,v2)).isEqualTo("READY");
  assertThat(db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,postId))
   .as("late READY of lower revision must not regress default").isEqualTo(v3);
  assertThat(db.queryForObject("SELECT preview_object_key FROM material_versions WHERE id=?",String.class,v2))
   .contains("/t");
  assertThat(db.queryForObject("SELECT preview_object_key FROM material_versions WHERE id=?",String.class,v3))
   .contains("/t");
 }

 @Test void expiredLeaseTakeoverKeepsOldWorkerFromPublishingObjectRefs()throws Exception{
  var a=register("mat_lease_alice","password-a1");
  long idA=a.get("userId").asLong();
  var created=materials.createDraft(idA,"Lease race","",List.of("lease"),"PUBLIC_PREVIEW",null,false,false,
   "video-lease".getBytes(StandardCharsets.UTF_8),"video/mp4");
  long postId=created.postId();long versionId=created.versionId();
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  BEFORE_MEDIA.set(()->{
   entered.countDown();
   try{if(!release.await(30,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}
   catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  });
  var pool=Executors.newSingleThreadExecutor();
  try{
   Future<?> oldWorker=pool.submit(()->materials.tick());
   assertThat(entered.await(15,TimeUnit.SECONDS)).isTrue();
   long oldToken=db.queryForObject("SELECT lease_token FROM material_versions WHERE id=?",Long.class,versionId);
   String oldPreview="material/"+postId+"/v1/t"+oldToken+"/preview.mp4";
   String oldCover="material/"+postId+"/v1/t"+oldToken+"/cover.jpg";
   db.update("UPDATE material_versions SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",versionId);
   materials.tick();
   assertThat(db.queryForObject("SELECT state FROM material_versions WHERE id=?",String.class,versionId)).isEqualTo("READY");
   long newToken=db.queryForObject("SELECT lease_token FROM material_versions WHERE id=?",Long.class,versionId);
   assertThat(newToken).isGreaterThan(oldToken);
   String publishedPreview=db.queryForObject("SELECT preview_object_key FROM material_versions WHERE id=?",String.class,versionId);
   String publishedCover=db.queryForObject("SELECT cover_object_key FROM material_versions WHERE id=?",String.class,versionId);
   assertThat(publishedPreview).isEqualTo("material/"+postId+"/v1/t"+newToken+"/preview.mp4");
   assertThat(publishedCover).isEqualTo("material/"+postId+"/v1/t"+newToken+"/cover.jpg");
   assertThat(store.exists("content-media",publishedPreview)).isTrue();
   byte[] winnerPreview=store.get("content-media",publishedPreview);
   release.countDown();
   oldWorker.get(30,TimeUnit.SECONDS);
   assertThat(db.queryForObject("SELECT state FROM material_versions WHERE id=?",String.class,versionId))
    .as("stale worker must not fail/regress READY").isEqualTo("READY");
   assertThat(db.queryForObject("SELECT lease_token FROM material_versions WHERE id=?",Long.class,versionId)).isEqualTo(newToken);
   assertThat(db.queryForObject("SELECT preview_object_key FROM material_versions WHERE id=?",String.class,versionId)).isEqualTo(publishedPreview);
   assertThat(db.queryForObject("SELECT cover_object_key FROM material_versions WHERE id=?",String.class,versionId)).isEqualTo(publishedCover);
   assertThat(store.get("content-media",publishedPreview)).isEqualTo(winnerPreview);
   assertThat(store.exists("content-media",oldPreview)).as("old worker may only write its own claim-scoped keys").isTrue();
   assertThat(store.exists("content-media",oldCover)).isTrue();
   assertThat(oldPreview).isNotEqualTo(publishedPreview);
  }finally{pool.shutdownNow();}
 }

 private com.fasterxml.jackson.databind.JsonNode register(String user,String pass)throws Exception{
  return json(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
    .content("{\"username\":\""+user+"\",\"password\":\""+pass+"\"}")).andExpect(status().isOk()).andReturn());
 }
 private com.fasterxml.jackson.databind.JsonNode json(MvcResult r)throws Exception{
  return json.readTree(r.getResponse().getContentAsString());
 }
}
