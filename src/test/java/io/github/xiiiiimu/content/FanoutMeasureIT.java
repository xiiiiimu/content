package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.FeedRedis;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Hardening B6: measurement only (follow storm R6, push fan-out R7). Opt-in, never in CI:
 * {@code -Dcontent.b6.enabled=true -Dcontent.b6.out=target/<new dir>}. Writes one JSON file per scenario; asserts only that the
 * scenario completed (all follows applied, every follower received the post), never a performance threshold.
 * <ul>
 * <li>R6: one author, 1000 fresh accounts follow it with 4/16/64 threads. Meanwhile the author publishes every 100 ms.
 *   Recorded: follow throughput and latency, publish latency (with a no-storm baseline), InnoDB row lock waits/time,
 *   and sampled blocked locks by table/index.</li>
 * <li>R7: a PUSH author with 10k and 100k followers (seeded by SQL) publishes one post; the delivery task is driven on one
 *   thread (one PUBLISH task is processed serially in batches of 100 in production too). Recorded: publish → task DONE
 *   (the last follower's Redis write precedes it), last follower's feed shows the post, SQL statements (Questions) and
 *   Redis commands (total_commands_processed) during the fan-out, per follower and per second.</li>
 * </ul>
 * Background scanners are off so the measurement thread is the only worker; the Testcontainers MySQL is not tuned like the
 * compose instance (absolute latencies are this environment's).
 */
@SpringBootTest @Testcontainers
@EnabledIfSystemProperty(named="content.b6.enabled",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FanoutMeasureIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4").withUsername("root").withPassword("b6-measure-only"); // root: reads performance_schema lock tables
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.pipeline.scan-enabled",()->"false");r.add("content.trending.aggregator.enabled",()->"false");
  r.add("content.retention.feed.enabled",()->"false");r.add("content.retention.follow-history.enabled",()->"false");r.add("content.stats.reconcile.enabled",()->"false");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired FollowingFeed feed;
 @Autowired DistributionInbox inbox;@Autowired DistributionWorker worker;@Autowired FeedRedis redis;@Autowired RecoveryRunner recovery;

 static Path out(){return Path.of(System.getProperty("content.b6.out","target/b6-measure-"+System.currentTimeMillis()));}
 static void write(String name,Map<String,Object> json)throws Exception{
  Files.createDirectories(out());
  var file=out().resolve(name+".json");
  Files.writeString(file,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(json),StandardOpenOption.CREATE_NEW);
 }
 long status(String name){return Long.parseLong(db.queryForObject("SHOW GLOBAL STATUS LIKE ?",(rs,i)->rs.getString(2),name));}
 long redisCommands(){
  var info=redis.connection().sync().info("stats");
  for(var line:info.split("\r?\n"))if(line.startsWith("total_commands_processed:"))return Long.parseLong(line.substring(25).trim());
  throw new IllegalStateException("no total_commands_processed");
 }
 static Map<String,Object> dist(List<Long> nanos){
  var ms=nanos.stream().map(n->n/1_000_000.0).sorted().toList();
  Map<String,Object> m=new LinkedHashMap<>();m.put("n",ms.size());
  if(ms.isEmpty())return m;
  m.put("p50_ms",pct(ms,.5));m.put("p95_ms",pct(ms,.95));m.put("p99_ms",pct(ms,.99));m.put("max_ms",ms.get(ms.size()-1));
  m.put("avg_ms",Math.round(ms.stream().mapToDouble(d->d).average().orElse(0)*100)/100.0);
  return m;
 }
 static double pct(List<Double> sorted,double p){return Math.round(sorted.get(Math.max(0,(int)Math.ceil(p*sorted.size())-1))*100)/100.0;}
 static final AtomicLong POST_IDS=new AtomicLong(70_000_000L);
 long publishOne(long author){
  long post=POST_IDS.incrementAndGet();
  db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,?,'DRAFT','b6')",post,author);
  long t0=System.nanoTime();
  publish.publish(author,post,UUID.randomUUID().toString(),0);
  return System.nanoTime()-t0;
 }

 @Test @Order(1) void r6FollowStorm()throws Exception{
  for(int threads:new int[]{4,16,64}){
   long author=60_000+threads;
   db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,'PULL')",author);
   var baseline=new ArrayList<Long>();
   for(int i=0;i<20;i++){baseline.add(publishOne(author));Thread.sleep(20);}
   long firstReader=1_000_000L*threads;int readers=1000;
   var followNanos=new ConcurrentLinkedQueue<Long>();var publishNanos=new ConcurrentLinkedQueue<Long>();
   var blocked=new ConcurrentHashMap<String,AtomicLong>();var failures=new AtomicLong();
   var done=new AtomicBoolean();var next=new AtomicLong(firstReader);
   long waits0=status("Innodb_row_lock_waits"),time0=status("Innodb_row_lock_time");
   var pool=Executors.newFixedThreadPool(threads+2);
   try{
    var start=new CountDownLatch(1);
    pool.submit(()->{start.await();while(!done.get()){publishNanos.add(publishOne(author));Thread.sleep(100);}return null;});
    pool.submit(()->{start.await();while(!done.get()){
     for(var row:db.queryForList("SELECT r.OBJECT_NAME o,r.INDEX_NAME i FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks r ON r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID"))
      blocked.computeIfAbsent(row.get("o")+"/"+row.get("i"),k->new AtomicLong()).incrementAndGet();
     Thread.sleep(50);}return null;});
    var workers=new ArrayList<Future<?>>();
    for(int t=0;t<threads;t++)workers.add(pool.submit(()->{start.await();for(;;){
     long reader=next.getAndIncrement();if(reader>=firstReader+readers)return null;
     long t0=System.nanoTime();
     try{follows.change(reader,author,true);followNanos.add(System.nanoTime()-t0);}catch(RuntimeException e){failures.incrementAndGet();}
    }}));
    long wall0=System.nanoTime();start.countDown();
    for(var f:workers)f.get(10,TimeUnit.MINUTES);
    long wallNanos=System.nanoTime()-wall0;done.set(true);
    pool.shutdown();assertThat(pool.awaitTermination(1,TimeUnit.MINUTES)).isTrue();
    long applied=db.queryForObject("SELECT COUNT(*) FROM follows WHERE followee_id=? AND status='FOLLOWING'",Long.class,author);
    assertThat(applied).as("every follow applied").isEqualTo(readers);
    Map<String,Object> r=new LinkedHashMap<>();
    r.put("threads",threads);r.put("readers",readers);r.put("failures",failures.get());
    r.put("wall_ms",wallNanos/1_000_000);r.put("follows_per_sec",Math.round(readers*1e9/wallNanos*10)/10.0);
    r.put("follow_latency",dist(List.copyOf(followNanos)));
    r.put("publish_baseline_no_storm",dist(baseline));r.put("publish_during_storm",dist(List.copyOf(publishNanos)));
    r.put("innodb_row_lock_waits",status("Innodb_row_lock_waits")-waits0);r.put("innodb_row_lock_time_ms",status("Innodb_row_lock_time")-time0);
    r.put("sampled_blocked_locks_by_table_index",new TreeMap<>(blocked));
    write("r6-follow-storm-t"+threads,r);
   }finally{pool.shutdownNow();}
  }
 }

 @Test @Order(2) void r7PushFanout()throws Exception{
  // Feed Redis must be initialised (generation) before delivery writes, as PerfHarnessIT does; without it every batch is
  // fenced ("Redis recovery generation changed"). Run 095608 lacked this: its R7 never completed.
  recovery.rebuild("b6-operator","b6 r7 before fan-out");
  for(int followers:new int[]{10_000,100_000}){
   long author=61_000+followers/1000;
   db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,'PUSH')",author);
   long first=10_000_000L*(followers/10_000);long last=first+followers-1;
   long seed0=System.nanoTime();
   db.execute((org.springframework.jdbc.core.ConnectionCallback<Object>)c->{
    try(var s=c.createStatement()){
     s.execute("SET SESSION cte_max_recursion_depth=200000");
     s.execute("INSERT INTO follows(follower_id,followee_id,status,follow_epoch,follow_cutoff_seq,history_since_ms,history_limit) "
      +"WITH RECURSIVE n(i) AS (SELECT "+first+" UNION ALL SELECT i+1 FROM n WHERE i<"+last+") SELECT i,"+author+",'FOLLOWING',1,0,0,0 FROM n");
    }
    return null;});
   long seedMs=(System.nanoTime()-seed0)/1_000_000;
   long post=POST_IDS.incrementAndGet();
   db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,?,'DRAFT','b6')",post,author);
   long q0=status("Questions"),r0=redisCommands();
   long t0=System.nanoTime();
   var pub=publish.publish(author,post,UUID.randomUUID().toString(),0);
   long publishNanos=System.nanoTime()-t0;
   inbox.accept(db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,pub.eventId()),Long.toString(pub.eventId()),"PostPublished");
   long acceptedNanos=System.nanoTime()-t0;
   int ticks=0;
   while(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE event_id=? AND state<>'DONE'",Long.class,pub.eventId())>0){
    worker.tick();ticks++;
    if(System.nanoTime()-t0>TimeUnit.MINUTES.toNanos(30))throw new IllegalStateException("fan-out not done in 30 min");
   }
   long doneNanos=System.nanoTime()-t0;
   long questions=status("Questions")-q0,commands=redisCommands()-r0;
   boolean lastSees=feed.read(last,20,null).items().stream().anyMatch(i->i.postId()==post);
   boolean firstSees=feed.read(first,20,null).items().stream().anyMatch(i->i.postId()==post);
   assertThat(lastSees&&firstSees).as("first and last follower see the post").isTrue();
   double sec=doneNanos/1e9;
   Map<String,Object> r=new LinkedHashMap<>();
   r.put("followers",followers);r.put("seed_ms",seedMs);
   r.put("publish_tx_ms",publishNanos/1_000_000.0);r.put("publish_to_task_created_ms",acceptedNanos/1_000_000.0);
   r.put("publish_to_last_follower_written_ms",doneNanos/1_000_000);r.put("worker_ticks",ticks);
   r.put("sql_statements",questions);r.put("sql_per_follower",Math.round(questions*1000.0/followers)/1000.0);r.put("sql_per_sec",Math.round(questions/sec));
   r.put("redis_commands",commands);r.put("redis_per_follower",Math.round(commands*1000.0/followers)/1000.0);r.put("redis_per_sec",Math.round(commands/sec));
   r.put("followers_per_sec",Math.round(followers/sec));
   r.put("note","Questions and Redis counters are server-wide; the only other clients are this test's own polling (1 COUNT per tick) and the two feed reads");
   write("r7-push-fanout-"+followers,r);
  }
 }
}
