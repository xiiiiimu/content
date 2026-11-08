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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers @org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RecoveryAndModesIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 static class RecoveryRedis extends GenericContainer<RecoveryRedis>{RecoveryRedis(){super("redis:7.4");addFixedExposedPort(30923,6379);withCommand("redis-server","--save","","--appendonly","no","--maxmemory-policy","noeviction");}}
 @Container static final RecoveryRedis REDIS=new RecoveryRedis();
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.test.hooks",()->"recovery");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired FollowingFeed feed;@Autowired MockMvc http;
 @Autowired DistributionInbox inbox;@Autowired org.springframework.context.ApplicationContext context;
 @Autowired(required=false) RecoveryRunner recovery;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 @Autowired FeedRecovery repair;@Autowired FeedModes modes;@Autowired org.springframework.transaction.support.TransactionTemplate tx;@Autowired Faults faults;
 static class Faults implements PipelineObserver {
  volatile java.util.function.BiConsumer<Long,Long> clear=(g,t)->{};
  volatile java.util.function.BiConsumer<Long,Long> batch=(g,t)->{};
  volatile java.util.function.LongConsumer beforeReturn=id->{};
  volatile java.util.function.BiConsumer<Long,FeedCandidate> authority=(id,p)->{};
  @Override public void afterRecoveryClear(long generation,long token){clear.accept(generation,token);}
  @Override public void afterRecoveryBatch(long generation,long token){batch.accept(generation,token);}
  @Override public void beforeFeedReturn(long reader){beforeReturn.accept(reader);}
  @Override public void beforeFeedAuthority(long reader,FeedCandidate c){authority.accept(reader,c);}
 }
 @TestConfiguration @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="content.test.hooks",havingValue="recovery") static class Hooks {@Bean @Primary Faults faults(){return new Faults();}}
 @AfterEach void evidence(TestInfo test)throws Exception{
  var folder=java.nio.file.Path.of("target","recovery-evidence");java.nio.file.Files.createDirectories(folder);
  var data=new LinkedHashMap<String,Object>();data.put("test",test.getTestMethod().orElseThrow().getName());
  data.put("control",db.queryForList("SELECT state,generation,recovery_token FROM feed_control"));
  data.put("recovery",db.queryForList("SELECT generation,recovery_token,state,readers,members,duration_ms,error FROM feed_recovery_audit ORDER BY generation,recovery_token"));
  data.put("transitions",db.queryForList("SELECT author_id,from_epoch,to_epoch,old_mode,new_mode,state,coverage_strategy,source_window_since_ms FROM feed_mode_transitions ORDER BY author_id,to_epoch"));
  java.nio.file.Files.writeString(folder.resolve(test.getTestMethod().orElseThrow().getName()+".json"),json.writeValueAsString(data));
 }
 @BeforeEach void reset(){
  for(String table:List.of("feed_recovery_audit","delivery_replay_audit","feed_event_conflicts","publish_requests","feed_delivery_tasks","consumer_inbox","outbox_events","follow_history","follows","follow_graph_version","posts","feed_mode_transitions","author_feed_mode"))db.update("DELETE FROM "+table);
  db.update("UPDATE feed_control SET state='PRIMARY',generation=1 WHERE id=1");
  faults.clear=(g,t)->{};faults.batch=(g,t)->{};faults.beforeReturn=id->{};faults.authority=(id,p)->{};
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){assertThat(c.sync().ping()).isEqualTo("PONG");c.sync().flushdb();}
 }
 void setup(int n){db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(701,'PUSH')");follows.change(801,701,true);for(long id=70101;id<70101+n;id++){db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,701,'DRAFT','coverage')",id);publish.publish(701,id,UUID.randomUUID().toString(),0);}}
 void locks(int n){org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(()->{
  try(var c=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")){r.next();assertThat(r.getInt(1)).isGreaterThanOrEqualTo(n);}
 });}
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
 @Test void repeatedBidirectionalTransitionsPreserveEveryEpochAndDoNotNeedRedis()throws Exception{
  setup(1);var expected=new ArrayList<Long>();expected.add(70101L);long epoch=0;
  for(String mode:List.of("PULL","PUSH","PULL","PUSH")){var changed=modes.change(701,mode,epoch);epoch++;assertThat(changed.epoch()).isEqualTo(epoch);
   long id=70101+epoch;db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,701,'DRAFT','transition')",id);var p=publish.publish(701,id,UUID.randomUUID().toString(),0);assertThat(p.deliveryMode()).isEqualTo(mode);assertThat(p.deliveryEpoch()).isEqualTo(epoch);expected.add(0,id);
  }
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){c.sync().flushdb();}
  assertThat(feed.read(801,20,null).items()).extracting(FollowingFeed.Item::postId).containsExactlyElementsOf(expected);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feed_mode_transitions WHERE state='DONE' AND coverage_strategy='PRIMARY_ALL_MODES'",Integer.class)).isEqualTo(4);
  assertThat(modes.change(701,"PUSH",0).epoch()).isEqualTo(4);assertThat(db.queryForObject("SELECT COUNT(*) FROM feed_mode_transitions",Integer.class)).isEqualTo(4);
  // Delayed, reverse-ordered and duplicate control/publication events retain immutable ownership.
  follows.offline(701,70103,1);expected.remove(70103L);
  for(var event:db.queryForList("SELECT id,event_type,payload FROM outbox_events ORDER BY id DESC")){
   for(int delivery=0;delivery<2;delivery++)inbox.accept((String)event.get("payload"),event.get("id").toString(),(String)event.get("event_type"));
  }
  repair.rebuild("operator","verify all retained epochs after reordered events and offlining");
  context.getBean("deliveryRunner",Runnable.class).run();
  assertThat(feed.read(801,20,null).items()).extracting(FollowingFeed.Item::postId).containsExactlyElementsOf(expected);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feed_event_conflicts",Integer.class)).isZero();
 }
 @Test void modeAndPublisherBothAuthorLockOrdersUseProductionServices()throws Exception{
  setup(0);var pool=Executors.newFixedThreadPool(2);
  try{
   var later=new AtomicReference<Future<Publication>>();
   tx.executeWithoutResult(s->{modes.change(701,"PULL",0);db.update("INSERT INTO posts(id,author_id,status,body) VALUES(70101,701,'DRAFT','mode first')");later.set(pool.submit(()->publish.publish(701,70101,"mode-first",0)));locks(1);});
   var first=later.get().get(10,TimeUnit.SECONDS);assertThat(first.deliveryMode()).isEqualTo("PULL");assertThat(first.deliveryEpoch()).isEqualTo(1);
   var switched=new AtomicReference<Future<FeedModes.Result>>();
   tx.executeWithoutResult(s->{db.update("INSERT INTO posts(id,author_id,status,body) VALUES(70102,701,'DRAFT','publish first')");var p=publish.publish(701,70102,"publish-first",0);assertThat(p.deliveryMode()).isEqualTo("PULL");switched.set(pool.submit(()->modes.change(701,"PUSH",1)));locks(1);});
   assertThat(switched.get().get(10,TimeUnit.SECONDS).epoch()).isEqualTo(2);assertThat(db.queryForObject("SELECT delivery_epoch FROM posts WHERE id=70102",Long.class)).isEqualTo(1);
  }finally{pool.shutdownNow();}
 }
 @Test void followingAndPublisherBothLockOrdersClassifyHistoryBySequence()throws Exception{
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(701,'PUSH')");var pool=Executors.newFixedThreadPool(2);
  try{
   db.update("INSERT INTO posts(id,author_id,status,body) VALUES(70101,701,'DRAFT','follow first')");var later=new AtomicReference<Future<Publication>>();
   tx.executeWithoutResult(s->{follows.change(801,701,true);later.set(pool.submit(()->publish.publish(701,70101,"follow-first",0)));locks(1);});later.get().get(10,TimeUnit.SECONDS);
   assertThat(db.queryForObject("SELECT follow_cutoff_seq FROM follows WHERE follower_id=801",Long.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM follow_history WHERE follower_id=801",Integer.class)).isZero();
   var relation=new AtomicReference<Future<RelationshipService.Result>>();
   tx.executeWithoutResult(s->{db.update("INSERT INTO posts(id,author_id,status,body) VALUES(70102,701,'DRAFT','publish first')");publish.publish(701,70102,"before-follow",0);relation.set(pool.submit(()->follows.change(802,701,true)));locks(1);});relation.get().get(10,TimeUnit.SECONDS);
   assertThat(db.queryForObject("SELECT follow_cutoff_seq FROM follows WHERE follower_id=802",Long.class)).isEqualTo(2);assertThat(db.queryForObject("SELECT COUNT(*) FROM follow_history WHERE follower_id=802 AND post_id=70102",Integer.class)).isEqualTo(1);
  }finally{pool.shutdownNow();}
 }
 @Test void rebuildingPausesMutationsKeepsPrimaryReadsAndRejectsOldCursor()throws Exception{
  setup(3);String oldCursor=feed.read(801,1,null).nextCursor();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  faults.clear=(g,t)->{entered.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}};
  var running=CompletableFuture.supplyAsync(()->repair.rebuild("operator","controlled maintenance"));
  try{assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(db.queryForObject("SELECT state FROM feed_control WHERE id=1",String.class)).isEqualTo("REBUILDING");assertThat(feed.read(801,20,null).items()).hasSize(3);
   db.update("INSERT INTO posts(id,author_id,status,body) VALUES(70104,701,'DRAFT','blocked')");assertThatThrownBy(()->publish.publish(701,70104,"blocked",0)).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.status).isEqualTo(503));
   assertThatThrownBy(()->follows.change(802,701,true)).isInstanceOf(FeedFailure.class);assertThatThrownBy(()->modes.change(701,"PULL",0)).isInstanceOf(FeedFailure.class);
   assertThatThrownBy(()->feed.read(801,1,oldCursor)).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("CURSOR_STALE"));
   assertThatThrownBy(()->repair.begin("other","concurrent recovery")).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("RECOVERY_BUSY"));
  }finally{release.countDown();}
  assertThat(running.get(10,TimeUnit.SECONDS).members()).isEqualTo(3);assertThat(db.queryForObject("SELECT state FROM feed_control WHERE id=1",String.class)).isEqualTo("READY");
 }
 @Test void recoveryFailurePersistsNotReadyAndExpiredOwnershipCanBeTakenOver(){
  setup(3);faults.batch=(g,t)->{throw new IllegalStateException("injected recovery process boundary");};
  assertThatThrownBy(()->repair.rebuild("operator","fail after durable batch")).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("SELECT state FROM feed_control WHERE id=1",String.class)).isEqualTo("REBUILDING");assertThat(feed.read(801,20,null).items()).hasSize(3);
  faults.batch=(g,t)->{};var report=repair.rebuild("operator","resume failed recovery");assertThat(report.members()).isEqualTo(3);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM feed_recovery_audit WHERE state='DONE' AND members=3",Integer.class)).isEqualTo(1);
 }
 @Test void oldRecoveryOwnerAndOldRedisGenerationCannotAlterNewReadyIndex()throws Exception{
  setup(3);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var firstToken=new AtomicLong();
  faults.clear=(g,t)->{if(firstToken.compareAndSet(0,t)){entered.countDown();try{release.await(20,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}}};
  var old=CompletableFuture.supplyAsync(()->repair.rebuild("old-operator","old recovery"));
  RecoveryRunner.Report fresh;
  try{assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();db.update("UPDATE feed_control SET recovery_lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1");fresh=repair.rebuild("new-operator","takeover expired recovery");}
  finally{release.countDown();}
  assertThatThrownBy(()->old.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(FeedFailure.class);
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){
   var index=new io.github.xiiiiimu.content.infrastructure.RedisFeedCandidateStore(c);
   assertThatThrownBy(()->index.addFenced(801,List.of(new FeedCandidate(1,99)),1)).isInstanceOf(CandidateIndexUnavailableException.class);
   assertThatThrownBy(()->index.addRecoveryFenced(801,List.of(new FeedCandidate(1,99)),fresh.generation(),firstToken.get())).isInstanceOf(CandidateIndexUnavailableException.class);
   assertThat(c.sync().zcard("feed:v2:{801}:inbox")).isEqualTo(3);
  }
 }
 @Test void graphChangeBeforeResponseRejectsPartialPage()throws Exception{
  setup(3);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  faults.beforeReturn=id->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}};
  var response=CompletableFuture.supplyAsync(()->feed.read(801,1,null));
  try{assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();follows.change(801,701,false);}finally{release.countDown();}
  assertThatThrownBy(()->response.get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(FeedFailure.class);
 }
 @Test void offliningAfterPrefetchBeforeAuthorityCannotBeReturned()throws Exception{
  setup(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  faults.authority=(id,c)->{entered.countDown();try{release.await(1,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}};
  var response=CompletableFuture.supplyAsync(()->feed.read(801,20,null));
  try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();follows.offline(701,70101,1);}finally{release.countDown();}
  assertThat(response.get(5,TimeUnit.SECONDS).items()).isEmpty();
 }
 @Test void redisRestartLeavesPrimaryAvailableUntilControlledRebuild()throws Exception{
  setup(3);repair.rebuild("operator","initial cache");
  // The volatile test node is killed, not gracefully saved. Explicit port binding survives Docker restart.
  REDIS.getDockerClient().killContainerCmd(REDIS.getContainerId()).withSignal("KILL").exec();REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
  org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(()->assertThat(feed.read(801,20,null).items()).hasSize(3));
  org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(()->{
   try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){assertThat(c.sync().ping()).isEqualTo("PONG");assertThat(c.sync().get("feed:ready-generation")).isNull();}
  });
  var report=repair.rebuild("operator","rebuild after restart loss");assertThat(report.members()).isEqualTo(3);
 }
 @Test void killedRecoveryCannotEnableRedisAndOperatorCanResume()throws Exception{
  setup(3);var signal=PipelineProcesses.DIR.resolve("recovery-kill");
  var child=PipelineProcesses.fault(MYSQL,"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379),"RECOVERY_BATCH",0,signal,Map.of());
  try{PipelineProcesses.killAtBarrier(child,signal);}finally{PipelineProcesses.stop(child);}
  assertThat(db.queryForObject("SELECT state FROM feed_control WHERE id=1",String.class)).isEqualTo("REBUILDING");assertThat(feed.read(801,20,null).items()).hasSize(3);
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){assertThat(c.sync().get("feed:ready-generation")).isNull();}
  db.update("UPDATE feed_control SET recovery_lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=1");
  var resumed=PipelineProcesses.start(io.github.xiiiiimu.content.worker.FeedOpsCommand.class,MYSQL,"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379),"recovery-resume",Map.of(),"rebuild","resume killed recovery after lease expiry");
  try{assertThat(resumed.waitFor(180,TimeUnit.SECONDS)).isTrue();assertThat(resumed.exitValue()).isZero();}finally{PipelineProcesses.stop(resumed);}
  assertThat(db.queryForObject("SELECT state FROM feed_control WHERE id=1",String.class)).isEqualTo("READY");assertThat(feed.read(801,20,null).items()).hasSize(3);
 }
}
