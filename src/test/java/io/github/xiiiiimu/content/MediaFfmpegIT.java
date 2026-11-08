package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/**
 * M6补验: real FFmpeg via Docker image.
 * Production path: uploaded bytes are passed as -i input; lavfi is only used to synthesize fixtures.
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MediaFfmpegIT {
 static final String FFMPEG="linuxserver/ffmpeg:version-7.1-cli";
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
  r.add("content.media.ffmpeg-image",()->FFMPEG);
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired MediaService media;@Autowired ObjectStore store;
 byte[] fixture;
 @BeforeEach void reset()throws Exception{
  Files.createDirectories(Path.of("target","m6-m8-batch"));
  db.update("DELETE FROM media_tasks");db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");
  db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(5101,'PULL')");
  if(fixture==null)fixture=new FfmpegTranscoder(FFMPEG).synthesizeFixture();
 }
 @Test void realFfmpegTranscodesUploadedBytesAndProducesCoverMetadata()throws Exception{
  assertThat(fixture.length).isGreaterThan(1000);
  assertThat(new String(fixture,4,4)).isEqualTo("ftyp");

  var direct=new FfmpegTranscoder(FFMPEG).transcode(fixture);
  assertThat(direct.video().length).isGreaterThan(1000);
  assertThat(direct.cover()[0]&0xFF).isEqualTo(0xFF);assertThat(direct.cover()[1]&0xFF).isEqualTo(0xD8);
  assertThat(direct.metadataJson()).contains("\"format\"").contains("streams");
  Files.writeString(Path.of("target","m6-m8-batch","ffmpeg-direct-meta.json"),direct.metadataJson());
  assertThatThrownBy(()->new FfmpegTranscoder(FFMPEG).transcode("not-media".getBytes())).isInstanceOf(IllegalStateException.class);

  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(510101,5101,'DRAFT','ffmpeg')");
  var p=publish.publish(5101,510101,UUID.randomUUID().toString(),0);
  media.attach(5101,510101,p.postVersion(),fixture,"video/mp4");
  for(int i=0;i<5;i++)media.tick();
  assertThat(db.queryForObject("SELECT state FROM media_tasks WHERE post_id=510101",String.class)).isEqualTo("DONE");
  String out=db.queryForObject("SELECT output_key FROM media_tasks WHERE post_id=510101",String.class);
  String cover=db.queryForObject("SELECT cover_key FROM media_tasks WHERE post_id=510101",String.class);
  String meta=db.queryForObject("SELECT CAST(meta_json AS CHAR) FROM media_tasks WHERE post_id=510101",String.class);
  assertThat(out).endsWith(".mp4");assertThat(cover).endsWith("-cover.jpg");
  assertThat(meta).contains("\"format\"").contains("streams");
  byte[] video=store.get("content-media",out);byte[] thumb=store.get("content-media",cover);
  assertThat(video.length).isGreaterThan(1000);assertThat(thumb.length).isGreaterThan(100);
  assertThat(thumb[0]&0xFF).isEqualTo(0xFF);assertThat(thumb[1]&0xFF).isEqualTo(0xD8);
  // Uploaded raw object remains the fixture; derived output differs after re-encode.
  byte[] raw=store.get("content-media","raw/"+510101+"/"+p.postVersion());
  assertThat(raw).isEqualTo(fixture);
  assertThat(video).isNotEqualTo(raw);
 }
 @Test void ffmpegFailureSchedulesRetryThenRecoversOnValidInput(){
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(510102,5101,'DRAFT','fail')");
  var p=publish.publish(5101,510102,UUID.randomUUID().toString(),0);
  media.attach(5101,510102,p.postVersion(),new byte[]{1,2,3,4},"video/mp4");
  media.tick();
  assertThat(db.queryForObject("SELECT state FROM media_tasks WHERE post_id=510102",String.class)).isIn("NEW","DEAD");
  assertThat(db.queryForObject("SELECT last_error FROM media_tasks WHERE post_id=510102",String.class)).contains("not a recognized media");
  String key="raw/"+510102+"/"+p.postVersion();
  store.put("content-media",key,fixture,"video/mp4");
  db.update("UPDATE media_tasks SET object_key=?,state='NEW',next_retry_at=CURRENT_TIMESTAMP(6),lease_until=NULL WHERE post_id=510102",key);
  for(int i=0;i<5;i++)media.tick();
  assertThat(db.queryForObject("SELECT state FROM media_tasks WHERE post_id=510102",String.class)).isEqualTo("DONE");
 }
}
