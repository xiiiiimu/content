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
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Round-1 R8: topic like events count only real state flips; like/unlike cycles keep net >= 0 and the ledger bounded. */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LikeTopicFlipIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired TrendingService trending;@Autowired RelationshipService follows;
 static final long HOUR=3_600_000L;
 @BeforeEach void reset(){
  db.update("DELETE FROM trend_risk_events");db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");
  db.update("DELETE FROM post_dislikes");db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 void published(long id,String topic){
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(?,1001,'DRAFT','t',?)",id,topic);
  publish.publish(1001,id,UUID.randomUUID().toString(),0);
 }
 long netSince(String topic,long since){Long v=db.queryForObject("SELECT COALESCE(SUM(net_events),0) FROM topic_minute_buckets WHERE topic=? AND bucket_start_ms>=?",Long.class,topic,since);return v==null?0:v;}
 long net(String topic){return netSince(topic,0);}
 long ledgerRows(long post){return db.queryForObject("SELECT COUNT(*) FROM topic_event_ledger WHERE post_id=?",Long.class,post);}
 long hourNet(String topic){return trending.window("net_events",HOUR,1L).stream().filter(e->e.topic().equals(topic)).mapToLong(TrendingService.Entry::score).sum();}

 @Test void likeUnlikeCyclesNeverDriveTopicNetNegativeAndKeepTheLedgerBounded(){
  follows.change(2001,1001,true);
  published(130101,"cycle");
  for(int i=0;i<10;i++){
   interactions.like(2001,130101,"cycle");
   assertThat(net("cycle")).as("after like #%d",i+1).isEqualTo(1);
   interactions.unlike(2001,130101,"cycle");
   assertThat(net("cycle")).as("after unlike #%d",i+1).isZero();
   assertThat(ledgerRows(130101)).as("ledger rows after cycle #%d",i+1).isLessThanOrEqualTo(1);
  }
  interactions.like(2001,130101,"cycle");
  assertThat(net("cycle")).isEqualTo(1);
  assertThat(hourNet("cycle")).isEqualTo(1);
  assertThat(ledgerRows(130101)).isEqualTo(1);
 }

 @Test void repeatedLikesAndUnlikesWithoutAFlipRecordNothing(){
  follows.change(2001,1001,true);
  published(130201,"noop");
  interactions.unlike(2001,130201,"noop");
  interactions.like(2001,130201,"noop");interactions.like(2001,130201,"noop");
  interactions.unlike(2001,130201,"noop");interactions.unlike(2001,130201,"noop");
  assertThat(net("noop")).isZero();
  assertThat(ledgerRows(130201)).isZero();
 }

 @Test void dislikeThatRemovesALikeRevokesItsTopicContribution(){
  follows.change(2001,1001,true);
  published(130301,"dis");
  interactions.like(2001,130301,"dis");
  interactions.dislike(2001,130301);
  assertThat(interactions.reconcile(130301).likes()).isZero();
  assertThat(net("dis")).isZero();
  assertThat(ledgerRows(130301)).isZero();
  interactions.like(2001,130301,"dis");
  assertThat(net("dis")).as("liking again after the dislike counts once").isEqualTo(1);
 }

 @Test void unlikingALikeFromOutsideTheWindowDoesNotSubtractFromTheCurrentWindow(){
  follows.change(2001,1001,true);
  published(130401,"aged");
  long likedAt=System.currentTimeMillis()-2*HOUR;long bucket=likedAt/60_000L*60_000L;
  db.update("INSERT INTO post_likes(post_id,user_id,created_at_ms) VALUES(130401,2001,?)",likedAt);
  db.update("INSERT INTO topic_event_ledger(event_id,topic,post_id,occurred_at_ms,delta,kind) VALUES('like:130401:2001','aged',130401,?,1,'LIKE')",likedAt);
  db.update("INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions) VALUES('aged',?,1,1)",bucket);
  interactions.unlike(2001,130401,"aged");
  assertThat(hourNet("aged")).as("1h window never goes negative because of an old like").isZero();
  assertThat(netSince("aged",System.currentTimeMillis()-HOUR)).isZero();
  assertThat(net("aged")).as("the old like's own bucket is revoked").isZero();
  assertThat(ledgerRows(130401)).isZero();
 }
}
