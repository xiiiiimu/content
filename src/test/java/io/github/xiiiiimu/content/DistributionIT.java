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

@SpringBootTest @org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc @Testcontainers @org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class DistributionIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379);
 @BeforeAll static void mq()throws Exception{PipelineMqFixture.preflight();}
 @AfterAll static void close(@Autowired io.github.xiiiiimu.content.worker.PipelineConsumer consumer,@Autowired DefaultMQProducer producer)throws Exception{consumer.close();producer.shutdown();PipelineMqFixture.close();}
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.relay.enabled",()->"true");r.add("content.relay.scan-enabled",()->"false");r.add("content.relay.namesrv",PipelineMqFixture::namesrv);r.add("content.relay.topic",()->PipelineMqFixture.TOPIC);
  r.add("content.pipeline.enabled",()->"true");r.add("content.pipeline.scan-enabled",()->"false");
  r.add("content.test.hooks",()->"inbox");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired RelayRunner relay;@Autowired DefaultMQProducer producer;
 @Autowired DistributionInbox inbox;@Autowired ObjectMapper json;@Autowired Faults faults;
 @Autowired io.github.xiiiiimu.content.worker.PipelineConsumer mainConsumer;@Autowired RecoveryRunner recovery;
 @Autowired DistributionWorker worker;
 @Autowired org.springframework.test.web.servlet.MockMvc http;
 static final AtomicLong IDS=new AtomicLong(1000);
 @BeforeEach void confirmProductionProducerConnection()throws Exception{
  // SDK connect logging enumerates Windows adapters before completing a fresh channel.
  // This setup watchdog does not alter the production Relay's 3s send timeout or 30s lease.
  long started=System.nanoTime();
  await().atMost(Duration.ofSeconds(180)).ignoreExceptions().untilAsserted(()->assertThat(producer.send(new Message(PipelineMqFixture.TOPIC,"__Warmup","route-warmup",new byte[]{1}),10000).getSendStatus()).isEqualTo(SendStatus.SEND_OK));
  java.nio.file.Files.createDirectories(java.nio.file.Path.of("target","pipeline-evidence"));
  java.nio.file.Files.writeString(java.nio.file.Path.of("target","pipeline-evidence","producer-warmup.log"),"time="+java.time.Instant.now()+" durationMs="+(System.nanoTime()-started)/1_000_000+" ack=SEND_OK\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
 }
 static class Faults implements PipelineObserver {
  final AtomicLong failAfter=new AtomicLong();
  final java.util.concurrent.ConcurrentHashMap<Long,java.util.concurrent.atomic.AtomicInteger> commits=new java.util.concurrent.ConcurrentHashMap<>();
  @Override public void afterInboxCommit(long id){commits.computeIfAbsent(id,k->new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();if(failAfter.compareAndSet(id,0))throw new IllegalStateException("Injected failure after durable Inbox commit before ACK");}
 }
 @TestConfiguration @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="content.test.hooks",havingValue="inbox") static class Hooks {@Bean @Primary Faults faults(){return new Faults();}}
 Publication publication(String mode){long a=IDS.incrementAndGet();db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,?)",a,mode);follows.change(a+10000,a,true);
  long post=a*10000;db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,?,'DRAFT','dispatch')",post,a);return publish.publish(a,post,UUID.randomUUID().toString(),0);}
 String payload(long event){return db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,event);}
 int count(String table,long id){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE event_id=?",Integer.class,id);}
 @Test void brokerAckIsFollowedByCommittedInboxAndPersistentTask()throws Exception{
  assertThat(db.queryForObject("SELECT 1",Integer.class)).isEqualTo(1);
  recovery.rebuild("integration-operator","initialize verified generation before publication chain");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(301,'PUSH')");follows.change(401,301,true);
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(30101,301,'DRAFT','durable dispatch')");
  var publication=publish.publish(301,30101,UUID.randomUUID().toString(),0);
  for(int i=0;i<20 && !"SENT".equals(db.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,publication.eventId()));i++)relay.tick();
  await().atMost(Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(()->assertThat(PipelineMqFixture.received(Long.toString(publication.eventId()))).isNotEmpty());
  assertThat(db.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,publication.eventId())).isEqualTo("SENT");
  try{await().atMost(Duration.ofSeconds(15)).until(()->db.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?",Integer.class,publication.eventId())==1);}catch(org.awaitility.core.ConditionTimeoutException missing){/* Assert the missing behavior below, after genuine Broker preflight and ACK. */}
  assertThat(db.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE event_id=?",Integer.class,publication.eventId())).as("Broker SEND_OK and committed publication exist; production consumer must persist Inbox before ACK").isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE event_id=?",Integer.class,publication.eventId())).isEqualTo(1);
  for(int i=0;i<20 && !"DONE".equals(db.queryForObject("SELECT state FROM feed_delivery_tasks WHERE event_id=?",String.class,publication.eventId()));i++)worker.tick();
  assertThat(db.queryForObject("SELECT state FROM feed_delivery_tasks WHERE event_id=?",String.class,publication.eventId())).isEqualTo("DONE");
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var connection=client.connect()){
   assertThat(connection.sync().zrange("feed:v2:{401}:inbox",0,-1)).contains(FeedMemberCodec.encode(new FeedCandidate(publication.publishedAtMs(),30101)));
  }
  long now=System.currentTimeMillis();var issuer=new FeedTokens("identity-test-key-32-bytes-minimum-0001","IDENTITY");String identity=issuer.sign(new FeedTokens.Identity(1,401,now,now+300_000));
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/feed/following").header("Authorization","Bearer "+identity))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].postId").value(30101));
  String authorIdentity=issuer.sign(new FeedTokens.Identity(1,301,now,now+300_000));
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/authors/me/feed-mode").header("Authorization","Bearer "+authorIdentity).contentType("application/json").content("{\"mode\":\"PULL\",\"expectedEpoch\":0}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(30102,301,'DRAFT','new pull after old push')");
  var pulled=json.readValue(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/posts/30102/publish").header("Authorization","Bearer "+authorIdentity).header("Idempotency-Key","full-chain-pull").contentType("application/json").content("{\"expectedVersion\":0}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString(),Publication.class);
  assertThat(pulled.deliveryMode()).isEqualTo("PULL");assertThat(pulled.deliveryEpoch()).isEqualTo(1);
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/posts/30101/offline").header("Authorization","Bearer "+authorIdentity).contentType("application/json").content("{\"expectedVersion\":1}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  var events=db.queryForList("SELECT id FROM outbox_events WHERE (aggregate_id=301 AND event_type='FeedModeChanged') OR (aggregate_id=30101 AND event_type='PostOfflined') OR id=? ORDER BY id",Long.class,pulled.eventId());
  assertThat(events).hasSize(3);
  for(long event:events){
   for(int i=0;i<20 && !"SENT".equals(db.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,event));i++)relay.tick();
   assertThat(db.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,event)).isEqualTo("SENT");
   await().atMost(Duration.ofSeconds(30)).until(()->count("consumer_inbox",event)==1);
  }
  assertThat(count("feed_delivery_tasks",pulled.eventId())).isZero();
  assertThat(db.queryForObject("SELECT decision FROM consumer_inbox WHERE event_id=?",String.class,pulled.eventId())).isEqualTo("PULL_NO_FANOUT");
  assertThat(db.queryForObject("SELECT delivery_mode FROM posts WHERE id=30101",String.class)).isEqualTo("PUSH");
  // The old PUSH member remains cached; the primary authority must hide it after HTTP offlining.
  var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/feed/following").header("Authorization","Bearer "+identity))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items.length()").value(1))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].postId").value(30102)).andReturn().getResponse().getContentAsString();
  java.nio.file.Files.writeString(java.nio.file.Path.of("target","pipeline-evidence","full-chain.json"),json.writeValueAsString(java.util.Map.of("events",db.queryForList("SELECT o.id,o.event_type,o.status,i.decision FROM outbox_events o JOIN consumer_inbox i ON i.event_id=o.id WHERE o.aggregate_id IN(301,30101,30102) ORDER BY o.id"),"response",json.readTree(response))));
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
 @Test void actualMqDuplicatesRetainOneTask()throws Exception{
  var p=publication("PUSH");String body=payload(p.eventId());
  for(int i=0;i<2;i++){
   var message=new Message(PipelineMqFixture.TOPIC,"PostPublished",Long.toString(p.eventId()),body.getBytes(java.nio.charset.StandardCharsets.UTF_8));message.putUserProperty("eventId",Long.toString(p.eventId()));
   await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(()->assertThat(producer.send(message,10000).getSendStatus()).isEqualTo(SendStatus.SEND_OK));
  }
  await().atMost(Duration.ofSeconds(30)).until(()->faults.commits.getOrDefault(p.eventId(),new java.util.concurrent.atomic.AtomicInteger()).get()>=2);
  assertThat(count("consumer_inbox",p.eventId())).isEqualTo(1);assertThat(count("feed_delivery_tasks",p.eventId())).isEqualTo(1);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"INBOX_BEFORE","INBOX_AFTER"})
 void killedConsumerAtTransactionBarrierRedeliversActualMqMessage(String phase)throws Exception{
  mainConsumer.close();var p=publication("PUSH");var signal=PipelineProcesses.DIR.resolve(phase.toLowerCase()+"-"+p.eventId());Process child=null;
  try{
   child=PipelineProcesses.fault(MYSQL,"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379),phase,p.eventId(),signal,java.util.Map.of("PIPELINE_TEST_NAMESRV",PipelineMqFixture.namesrv()));
   // Send only this committed event through the same actual Java producer as the Relay.
   var message=new Message(PipelineMqFixture.TOPIC,"PostPublished",Long.toString(p.eventId()),payload(p.eventId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));message.putUserProperty("eventId",Long.toString(p.eventId()));
   await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(()->assertThat(producer.send(message,10000).getSendStatus()).isEqualTo(SendStatus.SEND_OK));
   PipelineProcesses.killAtBarrier(child,signal);
   int committed=phase.equals("INBOX_AFTER")?1:0;
   assertThat(count("consumer_inbox",p.eventId())).isEqualTo(committed);assertThat(count("feed_delivery_tasks",p.eventId())).isEqualTo(committed);
   mainConsumer.start();
   // No explicit resend: the restarted group must read the original unacknowledged Broker message.
   await().atMost(Duration.ofSeconds(90)).until(()->faults.commits.getOrDefault(p.eventId(),new java.util.concurrent.atomic.AtomicInteger()).get()>=1);
   assertThat(count("consumer_inbox",p.eventId())).isEqualTo(1);assertThat(count("feed_delivery_tasks",p.eventId())).isEqualTo(1);
   recovery.rebuild("integration-operator","verify recovered task executable after MQ redelivery");
   for(int i=0;i<20 && !"DONE".equals(db.queryForObject("SELECT state FROM feed_delivery_tasks WHERE event_id=?",String.class,p.eventId()));i++)worker.tick();
   assertThat(db.queryForObject("SELECT state FROM feed_delivery_tasks WHERE event_id=?",String.class,p.eventId())).isEqualTo("DONE");
   java.nio.file.Files.writeString(PipelineProcesses.DIR.resolve(phase.toLowerCase()+"-"+p.eventId()+".redelivery.json"),json.writeValueAsString(java.util.Map.of("eventId",p.eventId(),"phase",phase,"beforeRestartInbox",committed,"afterRestartInbox",count("consumer_inbox",p.eventId()),"tasks",count("feed_delivery_tasks",p.eventId()),"parentCommitCallbacks",faults.commits.get(p.eventId()).get(),"payloadHash",DistributionInbox.digest(payload(p.eventId())))));
  }finally{PipelineProcesses.stop(child);mainConsumer.start();}
 }
}
