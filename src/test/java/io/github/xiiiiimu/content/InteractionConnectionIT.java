package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * Hardening B3: an interaction takes one pooled connection. The visibility check runs inside the interaction transaction
 * (on its connection) instead of borrowing two more connections before it. Background jobs that would also borrow
 * connections are switched off so the Hikari acquire count belongs to the calls under test.
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class InteractionConnectionIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.trending.aggregator.enabled",()->"false");r.add("content.trending.retention-enabled",()->"false");r.add("content.stats.reconcile.enabled",()->"false");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired RelationshipService follows;@Autowired MeterRegistry meters;
 @BeforeEach void reset(){
  db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM post_reports");db.update("DELETE FROM post_dislikes");db.update("DELETE FROM post_comments");
  db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 long acquires(){var t=meters.find("hikaricp.connections.acquire").timer();assertThat(t).as("Hikari acquire timer").isNotNull();return t.count();}
 /** Acquisitions taken by {@code action}; JdbcTemplate reads in the test run outside the measured block. */
 long measure(Runnable action){long before=acquires();action.run();return acquires()-before;}

 @Test void eachInteractionTakesOneConnection(){
  for(long u=6001;u<=6010;u++)follows.change(u,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(170101,1001,'DRAFT','t','conn')");
  publish.publish(1001,170101,UUID.randomUUID().toString(),0);
  interactions.like(6010,170101,null);   // builds the stats row; not measured
  assertThat(measure(()->{for(long u=6001;u<=6005;u++)interactions.like(u,170101,null);})).as("5 likes").isEqualTo(5);
  assertThat(measure(()->interactions.unlike(6001,170101,null))).as("unlike").isEqualTo(1);
  assertThat(measure(()->interactions.dislike(6002,170101))).as("dislike").isEqualTo(1);
  assertThat(measure(()->interactions.undislike(6002,170101))).as("undislike").isEqualTo(1);
  assertThat(measure(()->interactions.report(6003,170101,"spam",null,null))).as("report").isEqualTo(1);
  assertThat(measure(()->interactions.comment(6004,170101,"hi","b3-"+UUID.randomUUID(),null))).as("comment").isEqualTo(1);
 }

 @Test void invisiblePostIsRejectedWithOneConnectionAndWritesNothing(){
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(170201,1001,'DRAFT','t','conn')");
  publish.publish(1001,170201,UUID.randomUUID().toString(),0);
  long used=measure(()->assertThatThrownBy(()->interactions.like(6101,170201,null))
   .isInstanceOfSatisfying(FeedFailure.class,f->assertThat(f.getMessage()).contains("POST_NOT_VISIBLE")));
  assertThat(used).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM post_likes WHERE post_id=170201",Long.class)).isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM post_stats WHERE post_id=170201",Long.class)).isZero();
 }
}
