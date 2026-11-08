package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Official MinIO acceptance (not S3Mock). Requires local image content-minio:RELEASE.2025-04-22T22-12-26Z.
 * Enable: -Dcontent.minio.it=true. Default CI skips — skipped ≠ passed for M6 MinIO close-out.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@EnabledIfSystemProperty(named="content.minio.it", matches="true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MediaMinioIT {
 static final String MINIO_IMAGE="content-minio:RELEASE.2025-04-22T22-12-26Z";
 static final String ACCESS="contentadmin";
 static final String SECRET="content-secret-key-32-bytes-minimum";
 static final String FFMPEG="linuxserver/ffmpeg:version-7.1-cli";
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> MINIO=new GenericContainer<>(DockerImageName.parse(MINIO_IMAGE))
  .withExposedPorts(9000)
  .withEnv("MINIO_ROOT_USER",ACCESS)
  .withEnv("MINIO_ROOT_PASSWORD",SECRET)
  .withCommand("server","/data","--console-address",":9001")
  .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).withStartupTimeout(Duration.ofSeconds(120)));
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.media.enabled",()->"true");
  r.add("content.media.s3-endpoint",()->"http://"+MINIO.getHost()+":"+MINIO.getMappedPort(9000));
  r.add("content.media.bucket",()->"content-media");
  r.add("content.media.access-key",()->ACCESS);
  r.add("content.media.secret-key",()->SECRET);
  r.add("content.media.ffmpeg-image",()->FFMPEG);
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired MediaService media;@Autowired ObjectStore store;
 @Autowired VisibilityService visibility;@Autowired RelationshipService follows;@Autowired MockMvc http;
 byte[] fixture;
 @BeforeEach void reset()throws Exception{
  Files.createDirectories(Path.of("target","minio-evidence"));
  db.update("DELETE FROM media_tasks");db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");
  db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(6101,'PULL')");
  if(fixture==null)fixture=new FfmpegTranscoder(FFMPEG).synthesizeFixture();
 }
 @Test void uploadDownloadAuthExpiryOfflineAgainstOfficialMinio()throws Exception{
  follows.change(6201,6101,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(610101,6101,'DRAFT','minio')");
  var p=publish.publish(6101,610101,UUID.randomUUID().toString(),0);
  var upload=media.attach(6101,610101,p.postVersion(),fixture,"video/mp4");
  assertThat(upload.objectKey()).startsWith("raw/");
  assertThat(store.get("content-media",upload.objectKey())).isEqualTo(fixture);
  for(int i=0;i<5;i++)media.tick();
  assertThat(db.queryForObject("SELECT state FROM media_tasks WHERE post_id=610101",String.class)).isEqualTo("DONE");
  String out=db.queryForObject("SELECT output_key FROM media_tasks WHERE post_id=610101",String.class);
  String cover=db.queryForObject("SELECT cover_key FROM media_tasks WHERE post_id=610101",String.class);
  byte[] video=store.get("content-media",out);byte[] thumb=store.get("content-media",cover);
  assertThat(video.length).isGreaterThan(1000);assertThat(thumb[0]&0xFF).isEqualTo(0xFF);assertThat(thumb[1]&0xFF).isEqualTo(0xD8);
  assertThat(new FfmpegTranscoder(FFMPEG).verifyDecodable(video)).contains("streams");
  // raw retained after derive (rebuild/retention)
  assertThat(store.exists("content-media",upload.objectKey())).isTrue();
  assertThat(store.get("content-media",upload.objectKey())).isEqualTo(fixture);

  assertThatThrownBy(()->media.sign(6201,610101,0)).isInstanceOf(FeedFailure.class);
  assertThatThrownBy(()->media.sign(6201,610101,901_000)).isInstanceOf(FeedFailure.class);
  assertThatThrownBy(()->media.sign(9999,610101,60_000)).as("non-follower denied").isInstanceOf(FeedFailure.class);
  var signed=media.sign(6201,610101,60_000);
  var uri=java.net.URI.create("http://localhost"+signed.url());
  var params=new java.util.HashMap<String,String>();
  for(String part:uri.getRawQuery().split("&")){var kv=part.split("=",2);params.put(java.net.URLDecoder.decode(kv[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(kv[1],StandardCharsets.UTF_8));}
  http.perform(get("/api/media/objects").param("bucket",params.get("bucket")).param("key",params.get("key")).param("exp",params.get("exp")).param("sig","deadbeef"))
   .andExpect(status().isForbidden());
  http.perform(get("/api/media/objects").param("bucket",params.get("bucket")).param("key",params.get("key")).param("exp",params.get("exp")).param("sig",params.get("sig")))
   .andExpect(status().isOk());
  long past=System.currentTimeMillis()-1_000;
  http.perform(get("/api/media/objects").param("bucket",params.get("bucket")).param("key",params.get("key")).param("exp",String.valueOf(past)).param("sig",params.get("sig")))
   .andExpect(status().isForbidden());

  follows.offline(6101,610101,p.postVersion());
  assertThat(visibility.read(6201,610101)).isEmpty();
  assertThatThrownBy(()->media.sign(6201,610101,60_000)).isInstanceOf(FeedFailure.class);
  // objects remain in MinIO after offline (retention)
  assertThat(store.exists("content-media",out)).isTrue();
  assertThat(store.exists("content-media",upload.objectKey())).isTrue();
  Files.writeString(Path.of("target","minio-evidence","MediaMinioIT-ok.txt"),
   "image="+MINIO_IMAGE+"\npost=610101\noutput="+out+"\nraw_retained=true\noffline_blocks_sign=true\n");
 }
}
