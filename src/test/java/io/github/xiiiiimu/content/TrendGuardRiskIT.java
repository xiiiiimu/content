package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * V2.1 §14 / ADR-005: production emit path, uniqueness, concurrent threshold, ops-only read.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TrendGuardRiskIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.trendguard.spike-threshold",()->"2");
 }
 @Autowired JdbcTemplate db;@Autowired MockMvc http;@Autowired PublishService publish;
 @Autowired InteractionService interactions;@Autowired RelationshipService follows;@Autowired TrendGuardService trendGuard;
 @Autowired TransactionTemplate tx;
 @BeforeEach void reset(){
  db.update("DELETE FROM trend_risk_events");db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");
  try{db.update("DELETE FROM trend_topic_serial");}catch(Exception ignored){}
  db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 String bearer(long user){return bearer(user,"READER");}
 String bearer(long user,String role){long now=System.currentTimeMillis();return new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY").sign(new FeedTokens.Identity(1,user,now,now+300_000,role));}
 long hourBucket(){return System.currentTimeMillis()/3_600_000L*3_600_000L;}

 @Test void productionInteractionPathEmitsRiskEventQueryableReadonly()throws Exception{
  follows.change(2001,1001,true);follows.change(2002,1001,true);follows.change(2003,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100501,1001,'DRAFT','tg')");
  publish.publish(1001,100501,UUID.randomUUID().toString(),0);

  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events",Integer.class)).isZero();
  interactions.like(2001,100501,"sports");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events",Integer.class)).as("below threshold → no risk yet").isZero();

  interactions.like(2002,100501,"sports");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events",Integer.class)).as("threshold reached via trending.record emit").isEqualTo(1);
  var row=db.queryForMap("SELECT event_id,schema_version,kind,topic FROM trend_risk_events");
  assertThat(row.get("schema_version")).isEqualTo(1);
  assertThat(row.get("kind")).isEqualTo("TOPIC_SPIKE");
  assertThat(row.get("topic")).isEqualTo("sports");
  String id=(String)row.get("event_id");
  assertThat(id).isEqualTo("tg:1:TOPIC_SPIKE:sports:"+hourBucket());
  assertThat(id.length()).isLessThanOrEqualTo(256);

  interactions.like(2003,100501,"sports");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events",Integer.class)).as("idempotent same-hour spike").isEqualTo(1);

  http.perform(get("/api/trendguard/risks").param("window","1h")).andExpect(status().isUnauthorized());
  http.perform(get("/api/trendguard/risks").param("window","1h").param("topic","sports").header("Authorization","Bearer "+bearer(2001,"READER")))
   .andExpect(status().isForbidden());
  http.perform(get("/api/trendguard/risks").param("window","1h").param("topic","sports").header("Authorization","Bearer "+bearer(2001,"TRENDGUARD_OPS")))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$[0].schemaVersion").value(1))
   .andExpect(jsonPath("$[0].kind").value("TOPIC_SPIKE"))
   .andExpect(jsonPath("$[0].topic").value("sports"))
   .andExpect(jsonPath("$.length()").value(1));

  assertThat(trendGuard.record(id,"sports","TOPIC_SPIKE",hourBucket(),100501L,"dup")).isFalse();
 }

 @Test void longTopicsSharingPrefixProduceDistinctRiskEvents(){
  follows.change(2101,1001,true);follows.change(2102,1001,true);follows.change(2103,1001,true);follows.change(2104,1001,true);
  String prefix="a".repeat(40);
  String topicA=prefix+"TOPIC_A_UNIQUE_TAIL";String topicB=prefix+"TOPIC_B_UNIQUE_TAIL";
  assertThat(topicA.length()).isLessThanOrEqualTo(128);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100601,1001,'DRAFT','a'),(100602,1001,'DRAFT','b')");
  publish.publish(1001,100601,UUID.randomUUID().toString(),0);publish.publish(1001,100602,UUID.randomUUID().toString(),0);
  interactions.like(2101,100601,topicA);interactions.like(2102,100601,topicA);
  interactions.like(2103,100602,topicB);interactions.like(2104,100602,topicB);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events",Integer.class)).isEqualTo(2);
  var topics=db.queryForList("SELECT topic FROM trend_risk_events ORDER BY topic",String.class);
  assertThat(topics).containsExactly(topicA,topicB);
  var ids=db.queryForList("SELECT event_id FROM trend_risk_events ORDER BY topic",String.class);
  assertThat(ids.get(0)).contains(topicA).doesNotContain("TOPIC_B");
  assertThat(ids.get(1)).contains(topicB);
  assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
 }

 @Test void sameTopicDifferentWindowsAreDistinctAndSameWindowDedupsByBusinessKey(){
  long h1=1_700_000_000_000L;long h2=h1+3_600_000L;
  assertThat(trendGuard.record("tg:1:TOPIC_SPIKE:win:"+h1,"win","TOPIC_SPIKE",h1,null,"w1")).isTrue();
  assertThat(trendGuard.record("tg:1:TOPIC_SPIKE:win:"+h2,"win","TOPIC_SPIKE",h2,null,"w2")).isTrue();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events WHERE topic='win'",Integer.class)).isEqualTo(2);
  // Different event_id string but same business key (kind,topic,window) must not insert a second row.
  assertThat(trendGuard.record("tg:1:TOPIC_SPIKE:win:"+h1+":other","win","TOPIC_SPIKE",h1,null,"dup-window")).isFalse();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events WHERE topic='win'",Integer.class)).isEqualTo(2);
 }

 @Test void concurrentThresholdEmitProducesOneEventWithoutFurtherInteraction()throws Exception{
  follows.change(2201,1001,true);follows.change(2202,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100701,1001,'DRAFT','c')");
  publish.publish(1001,100701,UUID.randomUUID().toString(),0);
  CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
  ExecutorService pool=Executors.newFixedThreadPool(2);
  Future<?> f1=pool.submit(()->{ready.countDown();go.await(30,TimeUnit.SECONDS);interactions.like(2201,100701,"race");return null;});
  Future<?> f2=pool.submit(()->{ready.countDown();go.await(30,TimeUnit.SECONDS);interactions.like(2202,100701,"race");return null;});
  assertThat(ready.await(30,TimeUnit.SECONDS)).isTrue();go.countDown();
  f1.get(60,TimeUnit.SECONDS);f2.get(60,TimeUnit.SECONDS);pool.shutdownNow();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM post_likes WHERE post_id=100701",Integer.class)).isEqualTo(2);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events WHERE topic='race'",Integer.class))
   .as("concurrent cumulative threshold must emit without a third interaction").isEqualTo(1);
 }

 @Test void rolledBackInteractionDoesNotLeaveRiskEvent(){
  follows.change(2301,1001,true);follows.change(2302,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100801,1001,'DRAFT','rb')");
  publish.publish(1001,100801,UUID.randomUUID().toString(),0);
  interactions.like(2301,100801,"rollback");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events WHERE topic='rollback'",Integer.class)).isZero();
  tx.executeWithoutResult(status->{
   interactions.like(2302,100801,"rollback");
   status.setRollbackOnly();
  });
  assertThat(db.queryForObject("SELECT COUNT(*) FROM post_likes WHERE post_id=100801",Integer.class))
   .as("threshold like rolled back with outer TX").isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events WHERE topic='rollback'",Integer.class))
   .as("risk emit must roll back with the interaction TX").isZero();
 }

 @Test void risksEndpointRejectsReaderAndInvalidTokenButAllowsOps()throws Exception{
  follows.change(2401,1001,true);follows.change(2402,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100901,1001,'DRAFT','auth')");
  publish.publish(1001,100901,UUID.randomUUID().toString(),0);
  interactions.like(2401,100901,"authz");interactions.like(2402,100901,"authz");
  String ops=bearer(2401,"TRENDGUARD_OPS");String reader=bearer(2401,"READER");
  http.perform(get("/api/trendguard/risks").param("window","1h").header("Authorization","Bearer "+reader)).andExpect(status().isForbidden());
  http.perform(get("/api/trendguard/risks").param("window","1h").header("Authorization","Bearer "+ops.substring(0,ops.length()-2)+"xx")).andExpect(status().isUnauthorized());
  http.perform(get("/api/trendguard/risks").param("window","1h").header("Authorization","Bearer "+ops)).andExpect(status().isOk()).andExpect(jsonPath("$[0].topic").value("authz"));
 }
}
