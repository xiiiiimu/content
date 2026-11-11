package io.github.xiiiiimu.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.domain.DistributionInbox;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * V1.1 community loop: register/login → follow → draft/edit/publish → feed comment → reply → notify → offline.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CommunityFlowIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired MockMvc http;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired DistributionInbox inbox;

 @Test void communityLoopRegisterFollowPublishCommentNotifyOffline()throws Exception{
  var a=register("alice_v11","password-a1");
  var b=register("bob_v11","password-b1");
  String tokenA=a.get("token").asText();String tokenB=b.get("token").asText();
  long idA=a.get("userId").asLong();long idB=b.get("userId").asLong();
  assertThat(idA).isNotEqualTo(idB);

  http.perform(put("/api/follows/"+idA).header("Authorization","Bearer "+tokenB))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.following").value(true));

  var draft=json(http.perform(post("/api/posts").header("Authorization","Bearer "+tokenA)
    .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hello draft\"}"))
    .andExpect(status().isOk()).andReturn());
  long postId=draft.get("postId").asLong();
  assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
  long ver=draft.get("version").asLong();

  var edited=json(http.perform(patch("/api/posts/"+postId).header("Authorization","Bearer "+tokenA)
    .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+ver+",\"body\":\"hello published body\"}"))
    .andExpect(status().isOk()).andReturn());
  assertThat(edited.get("body").asText()).isEqualTo("hello published body");
  ver=edited.get("version").asLong();

  http.perform(post("/api/posts/"+postId+"/publish").header("Authorization","Bearer "+tokenA)
    .header("Idempotency-Key",UUID.randomUUID().toString())
    .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+ver+"}"))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.postId").value((int)postId));

  // Published edit keeps publish time and must not create a second PostPublished outbox for the same post identity path.
  long publishedEvents=db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='PostPublished' AND aggregate_id=?",Long.class,postId);
  assertThat(publishedEvents).isEqualTo(1);
  var afterPub=db.queryForMap("SELECT published_at_ms,version,status FROM posts WHERE id=?",postId);
  long publishedAt=((Number)afterPub.get("published_at_ms")).longValue();
  long pubVer=((Number)afterPub.get("version")).longValue();
  http.perform(patch("/api/posts/"+postId).header("Authorization","Bearer "+tokenA)
    .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+pubVer+",\"body\":\"hello edited after publish\"}"))
   .andExpect(status().isOk());
  assertThat(db.queryForObject("SELECT published_at_ms FROM posts WHERE id=?",Long.class,postId)).isEqualTo(publishedAt);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='PostPublished' AND aggregate_id=?",Long.class,postId)).isEqualTo(1);

  var feed=json(http.perform(get("/api/feed/following").param("limit","20").header("Authorization","Bearer "+tokenB))
   .andExpect(status().isOk()).andReturn());
  assertThat(feed.get("items").toString()).contains("hello edited after publish");

  var comment=json(http.perform(post("/api/posts/"+postId+"/comments").header("Authorization","Bearer "+tokenB)
    .header("Idempotency-Key","c-b-1")
    .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"nice post\"}"))
    .andExpect(status().isOk()).andReturn());
  long commentId=comment.get("commentId").asLong();
  materializeCommentEvents();

  var notifsA=json(http.perform(get("/api/notifications").header("Authorization","Bearer "+tokenA))
   .andExpect(status().isOk()).andReturn());
  assertThat(notifsA.isArray()).isTrue();
  assertThat(notifsA.size()).isGreaterThanOrEqualTo(1);
  assertThat(notifsA.get(0).get("kind").asText()).isEqualTo("COMMENT_ON_POST");
  long notifForA=notifsA.get(0).get("id").asLong();

  var reply=json(http.perform(post("/api/posts/"+postId+"/comments").header("Authorization","Bearer "+tokenA)
    .header("Idempotency-Key","c-a-reply")
    .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"thanks\",\"parentId\":"+commentId+"}"))
    .andExpect(status().isOk()).andReturn());
  assertThat(reply.get("parentId").asLong()).isEqualTo(commentId);
  materializeCommentEvents();

  var notifsB=json(http.perform(get("/api/notifications").header("Authorization","Bearer "+tokenB))
   .andExpect(status().isOk()).andReturn());
  assertThat(notifsB.toString()).contains("REPLY_TO_COMMENT");
  long replyNotif=0;
  for(var n:notifsB)if("REPLY_TO_COMMENT".equals(n.get("kind").asText())){replyNotif=n.get("id").asLong();break;}
  assertThat(replyNotif).isPositive();

  http.perform(post("/api/notifications/"+replyNotif+"/read").header("Authorization","Bearer "+tokenB))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.readAtMs").isNumber());

  // Self-notify excluded: A commenting on own post should not notify A for top-level from A — already covered by B→A.
  http.perform(get("/api/notifications/"+notifForA+"/target").header("Authorization","Bearer "+tokenA))
   .andExpect(status().isOk())
   .andExpect(jsonPath("$.post.body").value("hello edited after publish"));

  http.perform(post("/api/posts/"+postId+"/offline").header("Authorization","Bearer "+tokenA)
    .contentType(MediaType.APPLICATION_JSON)
    .content("{\"expectedVersion\":"+db.queryForObject("SELECT version FROM posts WHERE id=?",Long.class,postId)+"}"))
   .andExpect(status().isOk());

  http.perform(get("/api/posts/"+postId).header("Authorization","Bearer "+tokenB))
   .andExpect(status().isNotFound());
  http.perform(get("/api/notifications/"+replyNotif+"/target").header("Authorization","Bearer "+tokenB))
   .andExpect(status().isNotFound());
 }

 private com.fasterxml.jackson.databind.JsonNode register(String user,String pass)throws Exception{
  return json(http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
    .content("{\"username\":\""+user+"\",\"password\":\""+pass+"\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.token").isString())
    .andReturn());
 }

 /** Simulate reliable consumer delivery of CommentCreated outbox rows (production path is Relay→MQ→Inbox). */
 private void materializeCommentEvents()throws Exception{
  var rows=db.queryForList("SELECT id,payload FROM outbox_events WHERE event_type='CommentCreated' ORDER BY id");
  for(var row:rows){
   long id=((Number)row.get("id")).longValue();
   String payload=(String)row.get("payload");
   try{inbox.accept(payload,Long.toString(id),"CommentCreated");}
   catch(Exception ignored){/* idempotent redelivery */}
  }
 }

 private com.fasterxml.jackson.databind.JsonNode json(MvcResult r)throws Exception{
  return json.readTree(r.getResponse().getContentAsString());
 }
}
