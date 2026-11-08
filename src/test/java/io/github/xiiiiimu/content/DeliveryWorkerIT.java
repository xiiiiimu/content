package io.github.xiiiiimu.content;
import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.*;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @Testcontainers
class DeliveryWorkerIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired DistributionInbox inbox;
 @Autowired(required=false) @Qualifier("deliveryRunner") Runnable runner;
 @Test void committedTaskIsExecutedByProductionRunnerAndWritesOriginalTuple()throws Exception {
  assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){
   assertThat(c.sync().ping()).isEqualTo("PONG");c.sync().set("feed:write-generation","1");c.sync().set("feed:ready-generation","1");
   db.update("UPDATE feed_control SET state='READY' WHERE id=1");
   db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(501,'PUSH')");follows.change(601,501,true);
   db.update("INSERT INTO posts(id,author_id,status,body) VALUES(50101,501,'DRAFT','task execution')");
   var p=publish.publish(501,50101,UUID.randomUUID().toString(),0);String payload=db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,p.eventId());
   inbox.accept(payload,Long.toString(p.eventId()),"PostPublished");
   assertThat(db.queryForObject("SELECT state FROM feed_delivery_tasks WHERE event_id=?",String.class,p.eventId())).isEqualTo("NEW");
   if(runner!=null)runner.run();
   assertThat(db.queryForObject("SELECT state FROM feed_delivery_tasks WHERE event_id=?",String.class,p.eventId())).as("Real MySQL/Redis preflight and durable Inbox succeeded; production runner must process the committed task").isEqualTo("DONE");
   assertThat(c.sync().zrange("feed:v2:{601}:inbox",0,0)).containsExactly(FeedMemberCodec.encode(new FeedCandidate(p.publishedAtMs(),p.postId())));
  }
 }
}
