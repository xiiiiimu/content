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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @Testcontainers
class DeliveryWorkerIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.feed.history-limit",()->"3");
  r.add("content.test.hooks",()->"delivery");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired DistributionInbox inbox;
 @Autowired(required=false) @Qualifier("deliveryRunner") Runnable runner;
 @Autowired DeliveryStore store;@Autowired DistributionWorker worker;@Autowired FollowingFeed feed;@Autowired Faults faults;
 static final AtomicLong IDS=new AtomicLong(2000);static final AtomicLong POSTS=new AtomicLong(100000);
 static class Faults implements PipelineObserver {
  volatile java.util.function.Consumer<DeliveryLease> beforeRedis=lease->{};
  final AtomicInteger batches=new AtomicInteger();
  @Override public void beforeRedis(DeliveryLease lease){beforeRedis.accept(lease);}
  @Override public void afterRedis(DeliveryLease lease){batches.incrementAndGet();}
 }
 @TestConfiguration @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="content.test.hooks",havingValue="delivery") static class Hooks {@Bean @Primary Faults faults(){return new Faults();}}
 @BeforeEach void reset(){
  for(String table:List.of("delivery_replay_audit","feed_event_conflicts","publish_requests","feed_delivery_tasks","consumer_inbox","outbox_events","follow_history","follows","follow_graph_version","posts","feed_mode_transitions","author_feed_mode"))db.update("DELETE FROM "+table);
  db.update("UPDATE feed_control SET state='READY',generation=1 WHERE id=1");faults.beforeRedis=lease->{};faults.batches.set(0);
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){c.sync().flushdb();c.sync().set("feed:write-generation","1");c.sync().set("feed:ready-generation","1");}
 }
 long author(String mode){long id=IDS.incrementAndGet();db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,?)",id,mode);return id;}
 Publication post(long author){long id=POSTS.incrementAndGet();db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,?,'DRAFT','worker')",id,author);return publish.publish(author,id,UUID.randomUUID().toString(),0);}
 String payload(long event){return db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,event);}
 long accept(Publication p){inbox.accept(payload(p.eventId()),Long.toString(p.eventId()),"PostPublished");return db.queryForObject("SELECT id FROM feed_delivery_tasks WHERE event_id=?",Long.class,p.eventId());}
 String state(long task){return db.queryForObject("SELECT state FROM feed_delivery_tasks WHERE id=?",String.class,task);}
 void expire(long task){db.update("UPDATE feed_delivery_tasks SET lease_until=TIMESTAMPADD(MICROSECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",task);}
 void due(long task){db.update("UPDATE feed_delivery_tasks SET next_retry_at=CURRENT_TIMESTAMP(6) WHERE id=?",task);}
 long cursor(long task){return db.queryForObject("SELECT last_follower_id FROM feed_delivery_tasks WHERE id=?",Long.class,task);}
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
 @Test void concurrentClaimsHaveExactlyOneOwner()throws Exception{
  long a=author("PUSH");follows.change(601,a,true);long task=accept(post(a));var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{var x=pool.submit(()->{gate.await();return store.claim();});var y=pool.submit(()->{gate.await();return store.claim();});gate.countDown();var one=x.get(10,TimeUnit.SECONDS);var two=y.get(10,TimeUnit.SECONDS);
   assertThat((one==null)^(two==null)).isTrue();worker.run(one==null?two:one);assertThat(state(task)).isEqualTo("DONE");
  }finally{pool.shutdownNow();}
 }
 @Test void expiredTokenCannotProgressFailOrRenewBeforeAndAfterTakeover(){
  long a=author("PUSH");follows.change(601,a,true);long task=accept(post(a));var old=store.claim();expire(task);
  assertThat(store.progress(old,601,0,true)).isFalse();assertThat(store.fail(old,new IllegalStateException("old"))).isFalse();
  assertThat(state(task)).isEqualTo("RUNNING");var next=store.claim();assertThat(next.token()).isEqualTo(old.token()+1);
  assertThat(store.progress(old,601,0,false)).isFalse();assertThat(store.fail(old,new IllegalStateException("old"))).isFalse();
  worker.run(next);assertThat(state(task)).isEqualTo("DONE");assertThat(store.progress(old,601,0,true)).isFalse();
 }
 @Test void finalClaimCrashBecomesDeadAndReplayPreservesIdentityWithAudit(){
  long a=author("PUSH");follows.change(601,a,true);var p=post(a);long task=accept(p);String original=payload(p.eventId());
  db.update("UPDATE feed_delivery_tasks SET attempt=4 WHERE id=?",task);var old=store.claim();assertThat(old.attempt()).isEqualTo(5);expire(task);
  assertThat(store.claim()).isNull();assertThat(state(task)).isEqualTo("DEAD");assertThat(store.replay(task,"integration-operator","reviewed failure")).isTrue();
  assertThat(store.progress(old,601,0,true)).isFalse();assertThat(store.fail(old,new IllegalStateException("late"))).isFalse();
  assertThat(db.queryForObject("SELECT attempt FROM feed_delivery_tasks WHERE id=?",Integer.class,task)).isZero();
  assertThat(db.queryForObject("SELECT payload FROM feed_delivery_tasks WHERE id=?",String.class,task)).isEqualTo(original);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM delivery_replay_audit WHERE task_id=? AND event_id=? AND new_token=old_token+1",Integer.class,task,p.eventId())).isEqualTo(1);
  runner.run();assertThat(state(task)).isEqualTo("DONE");assertThat(store.replay(task,"integration-operator","cannot replay DONE")).isFalse();
 }
 @Test void partialRedisWriteFailureCannotCommitCursorAndWholeBatchReplaysIdempotently(){
  long a=author("PUSH");follows.change(601,a,true);follows.change(602,a,true);long task=accept(post(a));
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){
   c.sync().set("feed:v2:{602}:inbox","bad-type");runner.run();assertThat(state(task)).isEqualTo("NEW");assertThat(cursor(task)).isZero();assertThat(c.sync().zcard("feed:v2:{601}:inbox")).isEqualTo(1);
   assertThat(db.queryForObject("SELECT next_retry_at>CURRENT_TIMESTAMP(6) FROM feed_delivery_tasks WHERE id=?",Boolean.class,task)).isTrue();
   db.update("UPDATE feed_delivery_tasks SET next_retry_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE id=?",task);
   runner.run();assertThat(db.queryForObject("SELECT attempt FROM feed_delivery_tasks WHERE id=?",Integer.class,task)).isEqualTo(1);
   c.sync().del("feed:v2:{602}:inbox","feed:v2:{602}:inbox:untrusted");due(task);runner.run();assertThat(state(task)).isEqualTo("DONE");
   assertThat(c.sync().zcard("feed:v2:{601}:inbox")).isEqualTo(1);assertThat(c.sync().zcard("feed:v2:{602}:inbox")).isEqualTo(1);
  }
 }
 @Test void realRedisPauseSchedulesRetryAndPreservesOriginalEvent(){
  long a=author("PUSH");follows.change(601,a,true);var p=post(a);long task=accept(p);String original=payload(p.eventId());var docker=REDIS.getDockerClient();docker.pauseContainerCmd(REDIS.getContainerId()).exec();
  try{runner.run();assertThat(state(task)).isEqualTo("NEW");assertThat(cursor(task)).isZero();}finally{docker.unpauseContainerCmd(REDIS.getContainerId()).exec();}
  due(task);runner.run();assertThat(state(task)).isEqualTo("DONE");assertThat(db.queryForObject("SELECT payload FROM feed_delivery_tasks WHERE id=?",String.class,task)).isEqualTo(original);
 }
 @Test void redisSuccessButProgressSqlFailureReplaysWithoutDuplicatingMembers()throws Exception{
  long a=author("PUSH");follows.change(601,a,true);long task=accept(post(a));
  try(var root=java.sql.DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var s=root.createStatement()){
   s.execute("CREATE TRIGGER injected_progress_failure BEFORE UPDATE ON feed_delivery_tasks FOR EACH ROW BEGIN IF NEW.last_follower_id>OLD.last_follower_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected progress failure'; END IF; END");
   try{runner.run();assertThat(state(task)).isEqualTo("NEW");assertThat(cursor(task)).isZero();}finally{s.execute("DROP TRIGGER injected_progress_failure");}
  }
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){assertThat(c.sync().zcard("feed:v2:{601}:inbox")).isEqualTo(1);due(task);runner.run();assertThat(state(task)).isEqualTo("DONE");assertThat(c.sync().zcard("feed:v2:{601}:inbox")).isEqualTo(1);}
 }
 @Test void multipleBatchesStayInOneClaimAndFixedFollowerUpperBound(){
  long a=author("PUSH");for(long reader=10001;reader<=10101;reader++)follows.change(reader,a,true);long task=accept(post(a));runner.run();
  assertThat(state(task)).isEqualTo("DONE");assertThat(cursor(task)).isEqualTo(10101);assertThat(faults.batches).hasValue(2);
  assertThat(db.queryForObject("SELECT attempt FROM feed_delivery_tasks WHERE id=?",Integer.class,task)).isEqualTo(1);
 }
 @Test void staleEpochBackfillDoesNotExpandNewHistory(){
  long a=author("PULL");var p1=post(a);var p2=post(a);var p3=post(a);var p4=post(a);follows.change(601,a,true);
  long first=db.queryForObject("SELECT MAX(id) FROM outbox_events WHERE event_type='FollowChanged'",Long.class);inbox.accept(payload(first),Long.toString(first),"FollowChanged");
  follows.offline(a,p4.postId(),1);follows.change(601,a,false);follows.change(601,a,true);
  long second=db.queryForObject("SELECT MAX(id) FROM outbox_events WHERE event_type='FollowChanged'",Long.class);inbox.accept(payload(second),Long.toString(second),"FollowChanged");runner.run();
  try(var client=io.lettuce.core.RedisClient.create("redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));var c=client.connect()){
   var members=c.sync().zrange("feed:v2:{601}:inbox",0,3);assertThat(members.stream().map(FeedMemberCodec::decode).map(FeedCandidate::postId)).containsExactly(p1.postId(),p2.postId(),p3.postId());
  }
 }
 @Test void lateOldWorkerWritesAfterTakeoverCannotOverwriteNewStateOrAuthorizeOldHistory()throws Exception{
  long a=author("PUSH");follows.change(601,a,true);var original=post(a);long task=accept(original);var old=store.claim();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  faults.beforeRedis=lease->{if(lease.token()==old.token()){entered.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}}};
  var running=CompletableFuture.runAsync(()->worker.run(old));
  try{assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();expire(task);var replacement=store.claim();worker.run(replacement);assertThat(state(task)).isEqualTo("DONE");
   post(a);post(a);post(a);post(a);follows.change(601,a,false);follows.change(601,a,true);
  }finally{release.countDown();}
  running.get(10,TimeUnit.SECONDS);assertThat(state(task)).isEqualTo("DONE");
  assertThat(feed.read(601,20,null).items()).extracting(FollowingFeed.Item::postId).doesNotContain(original.postId()).hasSize(3);
 }
}
