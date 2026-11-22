package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

/**
 * Hardening B2 (R3): post_stats moves by ±1 from affected rows; no interaction runs COUNT(*). The offline reconciler finds
 * and repairs drift, and running it alongside live interactions never reports drift that is not there.
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PostStatsIncrementIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.stats.reconcile.enabled",()->"false");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired InteractionService interactions;@Autowired RelationshipService follows;@Autowired StatsReconciler reconciler;
 final StatementDigests digests=new StatementDigests(MYSQL);
 static final String[] COUNTED={"post_likes","post_dislikes","post_comments","post_reports"};
 @BeforeEach void reset(){
  db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");db.update("DELETE FROM post_reports");
  db.update("DELETE FROM post_dislikes");db.update("DELETE FROM post_comments");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 void published(long id){
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(?,1001,'DRAFT','t','statsb2')",id);
  publish.publish(1001,id,UUID.randomUUID().toString(),0);
 }
 void followers(long from,int n){for(int i=0;i<n;i++)follows.change(from+i,1001,true);}
 long[] stored(long post){var m=db.queryForMap("SELECT like_count,dislike_count,comment_count,report_count FROM post_stats WHERE post_id=?",post);
  return new long[]{((Number)m.get("like_count")).longValue(),((Number)m.get("dislike_count")).longValue(),((Number)m.get("comment_count")).longValue(),((Number)m.get("report_count")).longValue()};}
 long[] counted(long post){long[] c=new long[4];for(int i=0;i<4;i++)c[i]=db.queryForObject("SELECT COUNT(*) FROM "+COUNTED[i]+" WHERE post_id=?",Long.class,post);return c;}
 void assertExact(long post,String when){assertThat(stored(post)).as("post_stats == COUNT(*) %s",when).containsExactly(counted(post));}
 long countExecutions(){long n=0;for(String t:COUNTED)n+=digests.executions("SELECT COUNT ( * ) FROM `"+t+"`%");return n;}

 @Test void interactionsRunNoCount(){
  followers(4001,6);
  published(160101);
  interactions.like(4001,160101,null);                       // first interaction builds the stats row (one COUNT pass)
  digests.reset();
  interactions.like(4002,160101,null);interactions.like(4003,160101,null);
  interactions.unlike(4002,160101,null);
  interactions.dislike(4003,160101);interactions.undislike(4003,160101);interactions.dislike(4004,160101);
  interactions.comment(4005,160101,"hi","b2-"+UUID.randomUUID(),null);
  interactions.report(4006,160101,"spam",null,null);
  assertThat(countExecutions()).as("COUNT(*) executions on the counted tables during 8 interactions").isZero();
  assertExact(160101,"after the interactions");
 }

 @Test void everyFlipMovesTheCounterExactlyOnce(){
  followers(4101,3);
  published(160201);
  var like=interactions.like(4101,160201,null);assertThat(like.likeCount()).isEqualTo(1);
  assertThat(interactions.like(4101,160201,null).likeCount()).as("repeat like").isEqualTo(1);
  assertThat(interactions.unlike(4102,160201,null).likeCount()).as("unlike without a like").isEqualTo(1);
  assertThat(interactions.dislike(4101,160201).dislikeCount()).as("dislike removes the like").isEqualTo(1);
  assertThat(interactions.stats(160201).likes()).isZero();
  assertThat(interactions.dislike(4101,160201).dislikeCount()).as("repeat dislike").isEqualTo(1);
  assertThat(interactions.like(4101,160201,null).likeCount()).as("like removes the dislike").isEqualTo(1);
  assertThat(interactions.stats(160201).dislikes()).isZero();
  assertThat(interactions.undislike(4101,160201).dislikeCount()).as("undislike without a dislike").isZero();
  var first=interactions.report(4102,160201,"spam",null,null);var again=interactions.report(4102,160201,"spam",null,null);
  assertThat(first.reportCount()).isEqualTo(1);assertThat(again.replay()).isTrue();assertThat(again.reportCount()).as("report replay").isEqualTo(1);
  String key="b2-"+UUID.randomUUID();
  assertThat(interactions.comment(4103,160201,"c",key,null).commentCount()).isEqualTo(1);
  assertThat(interactions.comment(4103,160201,"c",key,null).commentCount()).as("comment replay").isEqualTo(1);
  assertExact(160201,"after every kind of flip and replay");
 }

 @Test void missingStatsRowIsBuiltFromTheExistingRowsOnce(){
  followers(4201,1);
  published(160301);
  for(long u=1;u<=5;u++)db.update("INSERT INTO post_likes(post_id,user_id,created_at_ms) VALUES(160301,?,0)",900000+u);   // legacy likes, no stats row
  assertThat(interactions.like(4201,160301,null).likeCount()).isEqualTo(6);
  assertExact(160301,"after the first interaction");
 }

 @Test void concurrentInteractionsOnOnePostKeepTheCounterExact()throws Exception{
  int users=64;followers(5001,users);
  published(160401);
  runConcurrently(160401,5001,users,16,600,null);
  assertExact(160401,"after 600 concurrent interactions");
 }

 @Test void reconcilerFindsAndRepairsDrift(){
  followers(4301,3);
  published(160501);published(160502);
  interactions.like(4301,160501,null);interactions.like(4302,160501,null);interactions.comment(4303,160501,"c","b2-"+UUID.randomUUID(),null);
  interactions.like(4301,160502,null);
  db.update("UPDATE post_stats SET like_count=10,comment_count=0 WHERE post_id=160501");   // drift: +8 likes, -1 comment
  var r=reconciler.reconcileAll();
  assertThat(r.checked()).isEqualTo(2);
  assertThat(r.corrected()).isEqualTo(1);
  assertThat(r.likeDrift()).isEqualTo(8);assertThat(r.commentDrift()).isEqualTo(-1);
  assertExact(160501,"after reconcile");assertExact(160502,"untouched post");
  assertThat(reconciler.reconcileAll().corrected()).as("nothing left to repair").isZero();
 }

 @Test void reconcilerRunningAlongsideLiveInteractionsReportsNoDrift()throws Exception{
  int users=64;followers(5101,users);
  published(160601);
  interactions.like(5101,160601,null);
  var passes=new java.util.concurrent.atomic.AtomicLong();var checked=new java.util.concurrent.atomic.AtomicLong();var drift=new java.util.concurrent.atomic.AtomicLong();
  runConcurrently(160601,5101,users,8,400,()->{
   var r=reconciler.reconcileAll();passes.incrementAndGet();checked.addAndGet(r.checked());drift.addAndGet(r.totalDrift());
   try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();}
  });
  assertThat(checked.get()).as("posts checked by %d reconcile passes during the load",passes.get()).isGreaterThan(0);
  assertThat(drift.get()).as("drift reported while interactions were running").isZero();
  assertExact(160601,"after load + reconcile");
 }

 /** Random like/unlike/dislike/undislike from {@code users} accounts; {@code sidecar} (if any) loops on its own thread meanwhile. */
 void runConcurrently(long post,long firstUser,int users,int threads,int operations,Runnable sidecar)throws Exception{
  var pool=Executors.newFixedThreadPool(threads+1);var stop=new AtomicBoolean();
  try{
   Future<?> side=sidecar==null?null:pool.submit(()->{while(!stop.get())sidecar.run();return null;});
   var futures=new ArrayList<Future<?>>();
   int per=operations/threads;
   for(int t=0;t<threads;t++){long seed=t;futures.add(pool.submit(()->{
    var rnd=new Random(seed);
    // Each thread owns its users: a like and a dislike by the same user on the same post can deadlock (like locks
    // post_dislikes then post_likes, dislike the reverse); that pre-existing issue is recorded in the round B report.
    int own=users/threads;
    for(int i=0;i<per;i++){
     long u=firstUser+seed*own+rnd.nextInt(own);
     switch(rnd.nextInt(4)){
      case 0->interactions.like(u,post,null);
      case 1->interactions.unlike(u,post,null);
      case 2->interactions.dislike(u,post);
      default->interactions.undislike(u,post);
     }
    }
    return null;}));}
   for(var f:futures)f.get(120,TimeUnit.SECONDS);
   stop.set(true);
   if(side!=null)side.get(120,TimeUnit.SECONDS);
  }finally{stop.set(true);pool.shutdownNow();}
 }
}
