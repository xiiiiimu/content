package io.github.xiiiiimu.content;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.github.xiiiiimu.content.domain.*;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.client.producer.SendStatus;
import java.util.UUID;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest @Testcontainers
class DistributionIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379);
 @BeforeAll static void mq()throws Exception{PipelineMqFixture.preflight();}
 @AfterAll static void close()throws Exception{PipelineMqFixture.close();}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.relay.enabled",()->"true");r.add("content.relay.scan-enabled",()->"false");r.add("content.relay.namesrv",PipelineMqFixture::namesrv);r.add("content.relay.topic",()->PipelineMqFixture.TOPIC);
  r.add("content.pipeline.enabled",()->"true");r.add("content.pipeline.scan-enabled",()->"false");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired RelayRunner relay;@Autowired DefaultMQProducer producer;
 @Test void brokerAckIsFollowedByCommittedInboxAndPersistentTask()throws Exception{
  assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
  await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(()->assertThat(producer.send(new Message(PipelineMqFixture.TOPIC,"__Warmup","route-warmup",new byte[]{1}),10000).getSendStatus()).isEqualTo(SendStatus.SEND_OK));
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(301,'PUSH')");follows.change(401,301,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(30101,301,'DRAFT','durable dispatch')");
  var publication=publish.publish(301,30101,UUID.randomUUID().toString(),0);relay.tick();
  await().atMost(Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(()->assertThat(PipelineMqFixture.received(Long.toString(publication.eventId()))).isNotEmpty());
  assertThat(db.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,publication.eventId())).isEqualTo("SENT");
  try{await().atMost(Duration.ofSeconds(15)).until(()->db.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?",Integer.class,publication.eventId())==1);}catch(org.awaitility.core.ConditionTimeoutException missing){/* Assert the missing behavior below, after genuine Broker preflight and ACK. */}
  assertThat(db.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?",Integer.class,publication.eventId())).as("Broker SEND_OK and committed publication exist; production consumer must persist Inbox before ACK").isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE event_id=?",Integer.class,publication.eventId())).isEqualTo(1);
 }
}
