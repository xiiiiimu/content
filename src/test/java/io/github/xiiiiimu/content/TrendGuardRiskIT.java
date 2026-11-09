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
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * V2.1 §14 / ADR-005: risk events must be emitted by the production interaction→trending path
 * and visible via readonly query. Direct {@code record(...)} is not the integration acceptance.
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
 @BeforeEach void reset(){
  db.update("DELETE FROM trend_risk_events");db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");
  db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 String bearer(long user){long now=System.currentTimeMillis();return new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY").sign(new FeedTokens.Identity(1,user,now,now+300_000));}

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
  assertThat((String)row.get("event_id")).startsWith("tg:1:TOPIC_SPIKE:sports:");

  interactions.like(2003,100501,"sports");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM trend_risk_events",Integer.class)).as("idempotent same-hour spike").isEqualTo(1);

  http.perform(get("/api/trendguard/risks").param("window","1h")).andExpect(status().isUnauthorized());
  http.perform(get("/api/trendguard/risks").param("window","1h").param("topic","sports").header("Authorization","Bearer "+bearer(2001)))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$[0].schemaVersion").value(1))
   .andExpect(jsonPath("$[0].kind").value("TOPIC_SPIKE"))
   .andExpect(jsonPath("$[0].topic").value("sports"))
   .andExpect(jsonPath("$.length()").value(1));

  // Auxiliary idempotency of store API (not a substitute for emit-path acceptance above).
  String id=(String)row.get("event_id");
  assertThat(trendGuard.record(id,"sports","TOPIC_SPIKE",100501L,"dup")).isFalse();
 }
}
