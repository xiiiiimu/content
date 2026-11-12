package io.github.xiiiiimu.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Batch-1 material loop: A publish → B discover by title/tag → preview + comment @8s →
 * A new version + accept → B still resolves old version @8s; offline/password/fail-safe.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MaterialFlowIT {
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
 }
 @Autowired MockMvc http;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
 @Autowired MaterialService materials;@Autowired PublishService publish;@Autowired InteractionService interactions;
 @Autowired RelationshipService follows;@Autowired VisibilityService visibility;

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

 private com.fasterxml.jackson.databind.JsonNode register(String user,String pass)throws Exception{
  return json(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
    .content("{\"username\":\""+user+"\",\"password\":\""+pass+"\"}")).andExpect(status().isOk()).andReturn());
 }
 private com.fasterxml.jackson.databind.JsonNode json(MvcResult r)throws Exception{
  return json.readTree(r.getResponse().getContentAsString());
 }
}
