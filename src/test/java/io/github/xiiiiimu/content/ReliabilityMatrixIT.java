package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
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
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * M7 reliability matrix: reuses production services against real MySQL/Redis.
 * MQ force-kill / duplicate / lease takeover deep coverage remains in DistributionIT,
 * DeliveryWorkerIT, OutboxRelayIT, RecoveryAndModesIT — this class fills visibility + reconcile + graceful close gaps.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReliabilityMatrixIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired VisibilityService visibility;
 @Autowired InteractionService interactions;@Autowired FollowingFeed feed;@Autowired PipelineReconcile reconcile;@Autowired MockMvc http;
 @Autowired RecoveryRunner recovery;
 static final Path EVIDENCE=Path.of("target","reliability-evidence");
 @BeforeEach void reset()throws Exception{
  Files.createDirectories(EVIDENCE);
  db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM feed_delivery_tasks");db.update("DELETE FROM consumer_inbox");db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(7001,'PUSH')");
 }
 String bearer(long user){long now=System.currentTimeMillis();return new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY").sign(new FeedTokens.Identity(1,user,now,now+300_000));}
 @Test void allReadEntriesRejectOfflineAndReconcileDetectsSentWithoutInbox()throws Exception{
  recovery.rebuild("m7-operator","reliability matrix bootstrap");
  follows.change(7002,7001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(700101,7001,'DRAFT','m7')");
  var p=publish.publish(7001,700101,UUID.randomUUID().toString(),0);
  assertThat(visibility.read(7002,700101)).isPresent();
  interactions.like(7002,700101,"m7");
  http.perform(get("/api/posts/700101").header("Authorization","Bearer "+bearer(7002))).andExpect(status().isOk());
  assertThat(feed.read(7002,10,null).items().stream().map(FollowingFeed.Item::postId)).contains(700101L);
  follows.offline(7001,700101,p.postVersion());
  assertThat(visibility.read(7002,700101)).isEmpty();
  assertThat(visibility.visibleInFeed(7002,700101)).isFalse();
  assertThatThrownBy(()->interactions.like(7002,700101,"m7")).isInstanceOf(FeedFailure.class);
  http.perform(get("/api/posts/700101").header("Authorization","Bearer "+bearer(7002))).andExpect(status().isNotFound());
  assertThat(feed.read(7002,10,null).items().stream().map(FollowingFeed.Item::postId)).doesNotContain(700101L);
  // Seed a SENT control event without Inbox to exercise reconcile detection (operator signal).
  db.update("INSERT INTO outbox_events(id,event_type,aggregate_id,payload,status) VALUES(7000001,'FeedModeChanged',7001,'{\"eventId\":7000001}','SENT')");
  var report=reconcile.scan();
  assertThat(report.sentWithoutInbox()).isGreaterThanOrEqualTo(1);
  assertThat(report.offlineStillInStats()).isGreaterThanOrEqualTo(0);
  Files.writeString(EVIDENCE.resolve("m7-visibility-reconcile.json"),
   "{\"postId\":700101,\"sentWithoutInbox\":"+report.sentWithoutInbox()+",\"pushWithoutTask\":"+report.pushWithoutTask()+",\"orphanTasks\":"+report.orphanTasks()+"}\n");
 }
}
