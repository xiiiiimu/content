package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.FeedRedis;
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

/**
 * Visibility carry-over (V2.1 §8 / ADR-004 T10b): topic content list + Redis non-authority.
 * Not V2.1 §14 M9 TrendGuard risk-event acceptance — see {@code TrendGuardRiskIT} / docs/m9-trendguard.md.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class TrendGuardIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired TrendingService trending;
 @Autowired MockMvc http;@Autowired VisibilityService visibility;@Autowired RelationshipService follows;@Autowired FeedRedis redis;
 @BeforeEach void reset(){
  db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
  try{redis.connection().sync().flushdb();}catch(Exception ignored){}
 }
 String bearer(long user){long now=System.currentTimeMillis();return new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY").sign(new FeedTokens.Identity(1,user,now,now+300_000));}

 @Test void topicContentListFiltersInvisiblePostsAndRequiresAuth()throws Exception{
  follows.change(2001,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100201,1001,'DRAFT','visible'),(100202,1001,'DRAFT','offline-soon')");
  publish.publish(1001,100201,UUID.randomUUID().toString(),0);
  publish.publish(1001,100202,UUID.randomUUID().toString(),0);
  interactions.like(2001,100201,"sports");
  interactions.like(2001,100202,"sports");
  assertThat(trending.topicPosts("sports",2001,50)).extracting(VisibilityService.View::postId).containsExactlyInAnyOrder(100201L,100202L);

  http.perform(get("/api/topics/sports/posts")).andExpect(status().isUnauthorized());
  http.perform(get("/api/topics/sports/posts").header("Authorization","Bearer "+bearer(2001)))
   .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.postId==100201)]").exists()).andExpect(jsonPath("$[?(@.postId==100202)]").exists());

  db.update("UPDATE posts SET status='OFFLINE',version=version+1 WHERE id=100202");
  assertThat(visibility.read(2001,100202)).isEmpty();
  assertThat(trending.topicPosts("sports",2001,50)).extracting(VisibilityService.View::postId).containsExactly(100201L);
  http.perform(get("/api/topics/sports/posts").header("Authorization","Bearer "+bearer(2001)))
   .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.postId==100201)]").exists()).andExpect(jsonPath("$[?(@.postId==100202)]").doesNotExist());
  assertThat(trending.topicPosts("sports",9999,50)).isEmpty();
 }

 @Test void poisonedRedisRankingCannotAuthorizeTopicContent()throws Exception{
  follows.change(2001,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100301,1001,'DRAFT','ok')");
  publish.publish(1001,100301,UUID.randomUUID().toString(),0);
  interactions.like(2001,100301,"music");
  // Poison Redis projection with a phantom topic and a score; must not mint content or bypass MySQL.
  redis.connection().sync().zadd("trend:v1:net:3600000",9999.0,"phantom");
  redis.connection().sync().zadd("trend:v1:posts:phantom",1.0,"100399");
  assertThat(trending.window("net_events",3_600_000L,2001)).extracting(TrendingService.Entry::topic).contains("music").doesNotContain("phantom");
  assertThat(trending.topicPosts("phantom",2001,50)).isEmpty();
  http.perform(get("/api/topics/phantom/posts").header("Authorization","Bearer "+bearer(2001)))
   .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
 }

 @Test void mysqlBucketsRemainAuthoritativeWhenRedisProjectionMissing(){
  follows.change(2001,1001,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(100401,1001,'DRAFT','bucket')");
  publish.publish(1001,100401,UUID.randomUUID().toString(),0);
  interactions.like(2001,100401,"tech");
  assertThat(trending.rebuild(3_600_000L)).isGreaterThanOrEqualTo(1);
  redis.connection().sync().del("trend:v1:net:3600000");
  // Read path never consults Redis ranking; empty projection must not hide MySQL scores or authorize phantom posts.
  assertThat(trending.window("net_events",3_600_000L,2001)).extracting(TrendingService.Entry::topic).contains("tech");
  assertThat(trending.topicPosts("tech",2001,50)).extracting(VisibilityService.View::postId).containsExactly(100401L);
 }
}
