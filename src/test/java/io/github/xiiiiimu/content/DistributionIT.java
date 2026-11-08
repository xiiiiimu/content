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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
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
 @Autowired DistributionInbox inbox;@Autowired ObjectMapper json;@Autowired Faults faults;
 static final AtomicLong IDS=new AtomicLong(1000);
 static class Faults implements PipelineObserver {
  final AtomicLong failAfter=new AtomicLong();
  @Override public void afterInboxCommit(long id){if(failAfter.compareAndSet(id,0))throw new IllegalStateException("Injected failure after durable Inbox commit before ACK");}
 }
 @TestConfiguration static class Hooks {@Bean @Primary Faults faults(){return new Faults();}}
 Publication publication(String mode){long a=IDS.incrementAndGet();db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,?)",a,mode);follows.change(a+10000,a,true);
  long post=a*10000;db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,?,'DRAFT','dispatch')",post,a);return publish.publish(a,post,UUID.randomUUID().toString(),0);}
 String payload(long event){return db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,event);}
 int count(String table,long id){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE event_id=?",Integer.class,id);}
 @Test void brokerAckIsFollowedByCommittedInboxAndPersistentTask()throws Exception{
  assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
  await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(()->assertThat(producer.send(new Message(PipelineMqFixture.TOPIC,"__Warmup","route-warmup",new byte[]{1}),10000).getSendStatus()).isEqualTo(SendStatus.SEND_OK));
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(301,'PUSH')");follows.change(401,301,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(30101,301,'DRAFT','durable dispatch')");
  var publication=publish.publish(301,30101,UUID.randomUUID().toString(),0);
  for(int i=0;i<20 && !"SENT".equals(db.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,publication.eventId()));i++)relay.tick();
  await().atMost(Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(()->assertThat(PipelineMqFixture.received(Long.toString(publication.eventId()))).isNotEmpty());
  assertThat(db.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,publication.eventId())).isEqualTo("SENT");
  try{await().atMost(Duration.ofSeconds(15)).until(()->db.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?",Integer.class,publication.eventId())==1);}catch(org.awaitility.core.ConditionTimeoutException missing){/* Assert the missing behavior below, after genuine Broker preflight and ACK. */}
  assertThat(db.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?",Integer.class,publication.eventId())).as("Broker SEND_OK and committed publication exist; production consumer must persist Inbox before ACK").isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE event_id=?",Integer.class,publication.eventId())).isEqualTo(1);
 }
 @Test void pullDecisionIsDurableAndNeverCreatesFanoutTask(){
  var p=publication("PULL");inbox.accept(payload(p.eventId()),Long.toString(p.eventId()),"PostPublished");
  assertThat(count("consumer_inbox",p.eventId())).isEqualTo(1);assertThat(count("feed_delivery_tasks",p.eventId())).isZero();
  assertThat(db.queryForObject("SELECT decision FROM consumer_inbox WHERE event_id=?",String.class,p.eventId())).isEqualTo("PULL_NO_FANOUT");
 }
 @Test void concurrentReplayCreatesOneInboxAndOneTask()throws Exception{
  var p=publication("PUSH");String payload=payload(p.eventId());var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(4);
  try{var futures=new java.util.ArrayList<Future<?>>();for(int i=0;i<8;i++)futures.add(executor.submit(()->{start.await();inbox.accept(payload,Long.toString(p.eventId()),"PostPublished");return null;}));start.countDown();for(var f:futures)f.get(20,TimeUnit.SECONDS);}
  finally{executor.shutdownNow();}
  assertThat(count("consumer_inbox",p.eventId())).isEqualTo(1);assertThat(count("feed_delivery_tasks",p.eventId())).isEqualTo(1);
 }
 @Test void realTaskInsertFailureRollsBackInboxAndCanBeRetried()throws Exception{
  var p=publication("PUSH");
  try(var connection=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var s=connection.createStatement()){
   s.execute("CREATE TRIGGER injected_task_failure BEFORE INSERT ON feed_delivery_tasks FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected inbox task failure'");
   try{assertThatThrownBy(()->inbox.accept(payload(p.eventId()),Long.toString(p.eventId()),"PostPublished")).isInstanceOf(RuntimeException.class);assertThat(count("consumer_inbox",p.eventId())).isZero();assertThat(count("feed_delivery_tasks",p.eventId())).isZero();}
   finally{s.execute("DROP TRIGGER injected_task_failure");}
  }
  inbox.accept(payload(p.eventId()),Long.toString(p.eventId()),"PostPublished");assertThat(count("consumer_inbox",p.eventId())).isEqualTo(1);assertThat(count("feed_delivery_tasks",p.eventId())).isEqualTo(1);
 }
 @Test void failureAfterCommitBeforeAckRetainsExactlyOneRecoverableTask(){
  var p=publication("PUSH");faults.failAfter.set(p.eventId());
  assertThatThrownBy(()->inbox.accept(payload(p.eventId()),Long.toString(p.eventId()),"PostPublished")).isInstanceOf(IllegalStateException.class);
  assertThat(count("consumer_inbox",p.eventId())).isEqualTo(1);assertThat(count("feed_delivery_tasks",p.eventId())).isEqualTo(1);
  inbox.accept(payload(p.eventId()),Long.toString(p.eventId()),"PostPublished");assertThat(count("feed_delivery_tasks",p.eventId())).isEqualTo(1);
 }
 @Test void changedPayloadAndCorruptPersistentTaskEnterReconciliation()throws Exception{
  var p=publication("PUSH");String original=payload(p.eventId());inbox.accept(original,Long.toString(p.eventId()),"PostPublished");
  var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original);body.put("postId",p.postId()+1);
  for(int i=0;i<2;i++)assertThatThrownBy(()->inbox.accept(body.toString(),Long.toString(p.eventId()),"PostPublished")).isInstanceOf(FeedFailure.class);
  assertThat(count("feed_event_conflicts",p.eventId())).isEqualTo(1);
  db.update("UPDATE feed_delivery_tasks SET post_id=post_id+1 WHERE event_id=?",p.eventId());
  try{assertThatThrownBy(()->inbox.accept(original,Long.toString(p.eventId()),"PostPublished")).isInstanceOf(FeedFailure.class);}
  finally{db.update("UPDATE feed_delivery_tasks SET post_id=? WHERE event_id=?",p.postId(),p.eventId());}
  assertThat(count("feed_event_conflicts",p.eventId())).isEqualTo(2);assertThat(count("consumer_inbox",p.eventId())).isEqualTo(1);
 }
}
