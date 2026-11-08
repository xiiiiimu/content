package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.MediaMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MediaPipelineIT {
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
  r.add("content.media.ffmpeg-image",()->"linuxserver/ffmpeg:version-7.1-cli");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired MediaService media;@Autowired VisibilityService visibility;
 @Autowired RelationshipService follows;@Autowired MediaMapper mediaMapper;@Autowired MockMvc http;
 @BeforeEach void reset(){
  db.update("DELETE FROM media_tasks");db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");
  db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(3001,'PULL')");
 }
 @Test void mediaTaskCompletesThroughS3CompatibleStoreAndOfflineBlocksSigning()throws Exception{
  follows.change(4001,3001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(300101,3001,'DRAFT','media')");
  var p=publish.publish(3001,300101,UUID.randomUUID().toString(),0);
  var upload=media.attach(3001,300101,p.postVersion(),"fake-video".getBytes(StandardCharsets.UTF_8),"video/mp4");
  assertThat(upload.objectKey()).startsWith("raw/");
  for(int i=0;i<5;i++)media.tick();
  assertThat(db.queryForObject("SELECT state FROM media_tasks WHERE post_id=300101",String.class)).isEqualTo("DONE");
  assertThatThrownBy(()->media.sign(4001,300101,0)).isInstanceOf(FeedFailure.class);
  assertThatThrownBy(()->media.sign(4001,300101,901_000)).isInstanceOf(FeedFailure.class);
  assertThatThrownBy(()->media.sign(9999,300101,60_000)).as("non-follower denied").isInstanceOf(FeedFailure.class);
  var signed=media.sign(4001,300101,60_000);
  assertThat(signed.url()).startsWith("/api/media/objects?");
  var uri=java.net.URI.create("http://localhost"+signed.url());
  var params=new java.util.HashMap<String,String>();
  for(String part:uri.getRawQuery().split("&")){var kv=part.split("=",2);params.put(java.net.URLDecoder.decode(kv[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
  assertThat(params.get("key")).startsWith("derived/");
  http.perform(get("/api/media/objects").param("bucket",params.get("bucket")).param("key",params.get("key")).param("exp",params.get("exp")).param("sig","deadbeef"))
   .andExpect(status().isForbidden());
  http.perform(get("/api/media/objects").param("bucket",params.get("bucket")).param("key",params.get("key")).param("exp",params.get("exp")).param("sig",params.get("sig")))
   .andExpect(status().isOk());
  follows.offline(3001,300101,p.postVersion());
  assertThat(visibility.read(4001,300101)).isEmpty();
  assertThatThrownBy(()->media.sign(4001,300101,60_000)).isInstanceOf(FeedFailure.class);
 }
 @Test void staleWorkerTokenCannotCompleteAfterTakeover(){
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(300102,3001,'DRAFT','lease')");
  var p=publish.publish(3001,300102,UUID.randomUUID().toString(),0);
  media.attach(3001,300102,p.postVersion(),"x".getBytes(StandardCharsets.UTF_8),"video/mp4");
  db.update("UPDATE media_tasks SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)),state='RUNNING',lease_token=1,attempt=1 WHERE post_id=300102");
  long taskId=db.queryForObject("SELECT id FROM media_tasks WHERE post_id=300102",Long.class);
  media.tick();
  assertThat(mediaMapper.complete(taskId,1L,"derived/stale")).as("old lease token must be fenced").isZero();
  assertThat(db.queryForObject("SELECT state FROM media_tasks WHERE post_id=300102",String.class)).isIn("DONE","NEW","RUNNING","DEAD");
 }
}
