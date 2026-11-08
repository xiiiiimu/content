package io.github.xiiiiimu.content;
import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class RecoveryAndModesIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired FollowingFeed feed;@Autowired MockMvc http;
 @Autowired DistributionInbox inbox;@Autowired org.springframework.context.ApplicationContext context;
 @Autowired(required=false) RecoveryRunner recovery;
 @BeforeEach void reset(){
  for(String table:List.of("delivery_replay_audit","feed_event_conflicts","publish_requests","feed_delivery_tasks","consumer_inbox","outbox_events","follow_history","follows","follow_graph_version","posts","feed_mode_transitions","author_feed_mode"))db.update("DELETE FROM "+table);
  db.update("UPDATE feed_control SET state='PRIMARY',generation=1 WHERE id=1");
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){assertThat(c.sync().ping()).isEqualTo("PONG");c.sync().flushdb();}
 }
 @Test void productionModeSwitchPreservesOldEpochAndBothHistoricalRanges()throws Exception{
  assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){assertThat(c.sync().ping()).isEqualTo("PONG");}
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(701,'PUSH')");follows.change(801,701,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(70101,701,'DRAFT','old push')");var old=publish.publish(701,70101,UUID.randomUUID().toString(),0);
  http.perform(put("/api/authors/me/feed-mode").principal(()->"701").contentType("application/json").content("{\"mode\":\"PULL\",\"expectedEpoch\":0}")).andExpect(status().isOk());
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(70102,701,'DRAFT','new pull')");var next=publish.publish(701,70102,UUID.randomUUID().toString(),0);
  assertThat(next.deliveryMode()).isEqualTo("PULL");assertThat(next.deliveryEpoch()).isEqualTo(1);
  assertThat(db.queryForObject("SELECT delivery_mode FROM posts WHERE id=70101",String.class)).isEqualTo("PUSH");assertThat(db.queryForObject("SELECT delivery_epoch FROM posts WHERE id=70101",Long.class)).isZero();
  assertThat(feed.read(801,20,null).items()).extracting(FollowingFeed.Item::postId).containsExactly(70102L,70101L);
 }
 @Test void controlledRecoveryRebuildsDoneTasksAndAuthoritativeHistoryWithoutMqReplay(){
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(702,'PUSH')");follows.change(802,702,true);
  var publications=new ArrayList<Publication>();for(long id=70201;id<=70203;id++){db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,702,'DRAFT','recovery')",id);publications.add(publish.publish(702,id,UUID.randomUUID().toString(),0));}
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){
   c.sync().set("feed:write-generation","1");c.sync().set("feed:ready-generation","1");db.update("UPDATE feed_control SET state='READY' WHERE id=1");
   var p=publications.get(0);inbox.accept(db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,p.eventId()),Long.toString(p.eventId()),"PostPublished");
   context.getBean("deliveryRunner",Runnable.class).run();assertThat(db.queryForObject("SELECT state FROM feed_delivery_tasks WHERE event_id=?",String.class,p.eventId())).isEqualTo("DONE");
   c.sync().flushdb();assertThat(feed.read(802,20,null).items()).hasSize(3);
   if(recovery!=null)recovery.rebuild("integration-operator","Redis data lost; rebuild authoritative retained range");
   assertThat(c.sync().zcard("feed:v2:{802}:inbox")).as("Real Redis was cleared after a DONE task; recovery must use authoritative posts and current follow facts").isEqualTo(3);
   assertThat(db.queryForObject("SELECT generation FROM feed_control WHERE id=1",Long.class)).isGreaterThan(1);
   assertThat(db.queryForObject("SELECT state FROM feed_control WHERE id=1",String.class)).isEqualTo("READY");
   assertThat(feed.read(802,20,null).items()).extracting(FollowingFeed.Item::postId).containsExactly(70203L,70202L,70201L);
  }
 }
}
