package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Round-1 R9: once a topic's spike is known to be reported for the hour, later interactions skip the risk insert (and its lock + scan). */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RiskEmitDedupIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.trendguard.spike-threshold",()->"2");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired RelationshipService follows;@Autowired TransactionTemplate tx;@Autowired TopicAggregator aggregator;
 final StatementDigests digests=new StatementDigests(MYSQL);
 static final String RISK_INSERT="INSERT INTO `trend_risk_events`%",SERIAL_LOCK="SELECT `topic` FROM `trend_topic_serial`%",BUCKET_READ="SELECT%`topic_minute_buckets`%";
 @BeforeEach void reset(){
  db.update("DELETE FROM trend_risk_events");db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");db.update("DELETE FROM trend_topic_serial");
  db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 void published(long id,String topic){
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(?,1001,'DRAFT','t',?)",id,topic);
  publish.publish(1001,id,UUID.randomUUID().toString(),0);
 }
 void followers(long from,int n){for(int i=0;i<n;i++)follows.change(from+i,1001,true);}

 /** Hardening B1: buckets and risk events are written by the background aggregator; read them once it has caught up. */
 long risks(String topic){aggregator.drain();return db.queryForObject("SELECT COUNT(*) FROM trend_risk_events WHERE topic=?",Long.class,topic);}
 static long hour(){return System.currentTimeMillis()/3_600_000L*3_600_000L;}

 @Test void reportedWindowSkipsTheRiskInsertTheTopicLockAndTheScan(){
  followers(3001,7);
  published(140101,"dedup");
  interactions.like(3001,140101,"dedup");interactions.like(3002,140101,"dedup");
  assertThat(risks("dedup")).isEqualTo(1);
  digests.reset();
  for(int i=2;i<7;i++)interactions.like(3001+i,140101,"dedup");
  assertThat(risks("dedup")).isEqualTo(1);
  assertThat(digests.executions(RISK_INSERT)).as("risk inserts after the window was reported").isZero();
  assertThat(digests.executions(SERIAL_LOCK)).as("topic serial lock after the window was reported").isZero();
  assertThat(digests.executions(BUCKET_READ)).as("spike scan after the window was reported").isZero();
  assertThat(db.queryForObject("SELECT SUM(net_events) FROM topic_minute_buckets WHERE topic='dedup'",Long.class)).as("buckets still counted").isEqualTo(7);
 }

 @Test void riskRowCommittedElsewhereIsLearnedOnceThenSkipped(){
  followers(3101,5);
  published(140201,"learned");
  long h=hour();
  db.update("INSERT INTO trend_risk_events(event_id,schema_version,topic,kind,window_start_ms,occurred_at_ms,post_id,detail,created_at_ms) VALUES(?,1,'learned','TOPIC_SPIKE',?,?,140201,'other-node',?)",
   TrendGuardService.spikeEventId("learned",h),h,System.currentTimeMillis(),System.currentTimeMillis());
  interactions.like(3101,140201,"learned");interactions.like(3102,140201,"learned");
  aggregator.drain();                                                                  // threshold reached → duplicate learned
  digests.reset();
  interactions.like(3103,140201,"learned");interactions.like(3104,140201,"learned");interactions.like(3105,140201,"learned");
  assertThat(risks("learned")).isEqualTo(1);
  assertThat(digests.executions(RISK_INSERT)).isZero();
 }

 @Test void rolledBackEmitDoesNotMarkTheWindowReported(){
  followers(3201,3);
  published(140301,"rb");
  interactions.like(3201,140301,"rb");
  tx.executeWithoutResult(status->{interactions.like(3202,140301,"rb");status.setRollbackOnly();});
  assertThat(risks("rb")).isZero();
  interactions.like(3203,140301,"rb");
  assertThat(risks("rb")).as("the rolled-back emit must not suppress the real one").isEqualTo(1);
 }
}
