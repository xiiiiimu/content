package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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

/** Round-1 R1: a post's topic is a server-side attribute; client topic parameters never route topic statistics. */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PostTopicIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired TrendingService trending;
 @Autowired MockMvc http;@Autowired RelationshipService follows;
 @BeforeEach void reset(){
  db.update("DELETE FROM trend_risk_events");db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");
  db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("DELETE FROM users");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 String bearer(long user){long now=System.currentTimeMillis();return "Bearer "+new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY").sign(new FeedTokens.Identity(1,user,now,now+300_000));}
 void published(long id,String topic){
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(?,1001,'DRAFT','t',?)",id,topic);
  publish.publish(1001,id,UUID.randomUUID().toString(),0);
 }
 java.util.List<String> bucketTopics(){return db.queryForList("SELECT DISTINCT topic FROM topic_minute_buckets ORDER BY topic",String.class);}
 java.util.List<String> ledgerTopics(){return db.queryForList("SELECT DISTINCT topic FROM topic_event_ledger ORDER BY topic",String.class);}

 @Test void authorSetsChangesAndClearsTheTopicThroughThePostApi()throws Exception{
  db.update("INSERT INTO users(id,username,password_hash,status,created_at_ms) VALUES(1001,'author1001','x','ACTIVE',0)");
  var created=http.perform(post("/api/posts").header("Authorization",bearer(1001)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"body\":\"hello\",\"topic\":\"  music \"}"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.topic").value("music")).andReturn();
  long id=com.jayway.jsonpath.JsonPath.<Number>read(created.getResponse().getContentAsString(),"$.postId").longValue();
  assertThat(db.queryForObject("SELECT topic FROM posts WHERE id=?",String.class,id)).isEqualTo("music");
  http.perform(patch("/api/posts/"+id).header("Authorization",bearer(1001)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"body\":\"hello2\",\"expectedVersion\":0,\"topic\":\"art\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.topic").value("art"));
  http.perform(patch("/api/posts/"+id).header("Authorization",bearer(1001)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"body\":\"hello3\",\"expectedVersion\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.topic").value("art"));
  http.perform(patch("/api/posts/"+id).header("Authorization",bearer(1001)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"body\":\"hello4\",\"expectedVersion\":2,\"topic\":null}")).andExpect(status().isOk()).andExpect(jsonPath("$.topic").doesNotExist());
  assertThat(db.queryForObject("SELECT topic FROM posts WHERE id=?",String.class,id)).isNull();
  http.perform(post("/api/posts").header("Authorization",bearer(1001)).contentType(MediaType.APPLICATION_JSON)
    .content("{\"body\":\"long\",\"topic\":\""+"x".repeat(129)+"\"}")).andExpect(status().isBadRequest());
 }

 /** Hardening D: the topic is public (the topic page already lists it), so every reader who can see the post gets it. */
 @Test void postDetailReturnsTheTopicToEveryReaderWhoCanSeeThePost()throws Exception{
  follows.change(2001,1001,true);
  published(120401,"music");
  published(120402,null);
  http.perform(get("/api/posts/120401").header("Authorization",bearer(1001)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.topic").value("music"));
  http.perform(get("/api/posts/120401").header("Authorization",bearer(2001)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.topic").value("music"));
  http.perform(get("/api/posts/120402").header("Authorization",bearer(2001)))
   .andExpect(status().isOk()).andExpect(jsonPath("$.topic").doesNotExist());
  http.perform(get("/api/posts/120401").header("Authorization",bearer(2999)))
   .andExpect(status().isNotFound());
 }

 @Test void interactionsOnAPostWithoutTopicWriteNoTopicStatistics(){
  follows.change(2001,1001,true);follows.change(2002,1001,true);
  published(120101,null);
  interactions.like(2001,120101,"sports");
  interactions.like(2002,120101,null);
  interactions.comment(2001,120101,"hi","r1-c1","sports");
  assertThat(interactions.reconcile(120101).likes()).isEqualTo(2);
  assertThat(bucketTopics()).as("no topic → no buckets (neither the client's 'sports' nor 'general')").isEmpty();
  assertThat(ledgerTopics()).isEmpty();
 }

 @Test void clientSuppliedTopicCannotRedirectStatistics(){
  follows.change(2001,1001,true);follows.change(2002,1001,true);
  published(120201,"music");
  interactions.like(2001,120201,"sports");
  interactions.like(2002,120201,null);
  interactions.comment(2001,120201,"hi","r1-c2","sports");
  assertThat(bucketTopics()).containsExactly("music");
  assertThat(ledgerTopics()).containsExactly("music");
  assertThat(db.queryForObject("SELECT SUM(net_events) FROM topic_minute_buckets WHERE topic='music'",Long.class)).isEqualTo(3);
 }

 @Test void topicPageListsPostsByTheirOwnTopicAttribute(){
  follows.change(2001,1001,true);
  published(120301,"music");          // no interaction at all
  published(120302,null);             // interaction carries a forged client topic
  interactions.like(2001,120302,"music");
  assertThat(trending.topicPosts("music",2001,50)).extracting(VisibilityService.View::postId).containsExactly(120301L);
 }
}
