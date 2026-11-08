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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class InteractionsAndTrendingIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired TrendingService trending;
 @Autowired MockMvc http;@Autowired VisibilityService visibility;@Autowired RelationshipService follows;
 @BeforeEach void reset(){
  db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 String bearer(long user){long now=System.currentTimeMillis();return new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY").sign(new FeedTokens.Identity(1,user,now,now+300_000));}
 @Test void likesCommentsAreIdempotentAndStatsReconcileWhileOfflineIsDenied()throws Exception{
  follows.change(2001,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100101,1001,'DRAFT','interact')");
  publish.publish(1001,100101,UUID.randomUUID().toString(),0);
  assertThat(visibility.read(9999,100101)).as("non-follower cannot read published detail").isEmpty();
  var like1=interactions.like(2001,100101,"sports");var like2=interactions.like(2001,100101,"sports");
  assertThat(like1.likeCount()).isEqualTo(1);assertThat(like2.likeCount()).isEqualTo(1);
  var c1=interactions.comment(2001,100101,"hello","idem-1","sports");var c2=interactions.comment(2001,100101,"hello","idem-1","sports");
  assertThat(c1.commentId()).isEqualTo(c2.commentId());assertThat(c2.replay()).isTrue();
  assertThat(interactions.reconcile(100101).likes()).isEqualTo(1);assertThat(interactions.reconcile(100101).comments()).isEqualTo(1);
  interactions.unlike(2001,100101,"sports");assertThat(interactions.reconcile(100101).likes()).isZero();
  assertThat(trending.rebuild(3_600_000L)).isGreaterThanOrEqualTo(1);
  http.perform(get("/api/topics/trending").param("window","1h").param("metric","net_events").header("Authorization","Bearer "+bearer(2001)))
   .andExpect(status().isOk()).andExpect(jsonPath("$[0].topic").value("sports"));
  http.perform(get("/api/posts/100101/stats")).andExpect(status().isUnauthorized());
  http.perform(get("/api/posts/100101/stats").header("Authorization","Bearer "+bearer(2001))).andExpect(status().isOk());
  db.update("UPDATE posts SET status='OFFLINE',version=version+1 WHERE id=100101");
  assertThat(visibility.read(2001,100101)).isEmpty();
  assertThatThrownBy(()->interactions.like(2001,100101,"sports")).isInstanceOf(FeedFailure.class);
 }
}
