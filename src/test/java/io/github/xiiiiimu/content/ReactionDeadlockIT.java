package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Hardening B10: one user liking, disliking, unliking and undisliking the same post concurrently.
 * <p>Before the fix the two write paths lock the two reaction tables in opposite orders: like deletes from post_dislikes
 * and then inserts into post_likes, dislike deletes from post_likes and then inserts into post_dislikes. Under READ
 * COMMITTED a DELETE of an absent row takes no lock, so two such requests can both commit and leave the user both liking
 * and disliking the post; from that state the next like/dislike pair holds one row each and waits for the other's
 * (InnoDB deadlock, one request fails with 500).
 * <p>Requests go through HTTP (MockMvc) so a deadlock shows as the status a client would get. The per-user interact
 * bucket is raised for this class only, so the rate limiter does not hide the race; nothing else is relaxed.
 */
@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReactionDeadlockIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.stats.reconcile.enabled",()->"false");
  r.add("content.ratelimit.rules.interact.limit",()->"1000000");r.add("content.ratelimit.rules.interact.burst",()->"1000000");
 }
 @Autowired MockMvc http;@Autowired AuthService auth;@Autowired PublishService publish;@Autowired JdbcTemplate db;@Autowired RelationshipService follows;
 static final AtomicInteger SEQ=new AtomicInteger();
 record Actor(long id,String token){}

 @BeforeEach void reset(){
  db.update("DELETE FROM topic_event_ledger");db.update("DELETE FROM topic_minute_buckets");
  db.update("DELETE FROM post_dislikes");db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM publish_requests");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(1001,'PULL')");
 }
 void published(long id){
  db.update("INSERT INTO posts(id,author_id,status,body,topic) VALUES(?,1001,'DRAFT','t','b10')",id);
  publish.publish(1001,id,UUID.randomUUID().toString(),0);
 }
 /** A registered user who follows the author (posts are visible to followers), created before the post is published. */
 Actor actor(){var r=auth.register("b10_u"+SEQ.incrementAndGet()+"_"+System.nanoTime()%100000,"password-x1");follows.change(r.userId(),1001,true);return new Actor(r.userId(),r.token());}
 long deadlocks(){
  try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var s=c.createStatement();
      var rs=s.executeQuery("SELECT `COUNT` FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'")){
   rs.next();return rs.getLong(1);
  }catch(Exception e){throw new AssertionError("reading lock_deadlocks failed",e);}
 }
 int send(MockHttpServletRequestBuilder req,Actor a)throws Exception{
  return http.perform(req.header("Authorization","Bearer "+a.token())).andReturn().getResponse().getStatus();
 }
 long count(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}
 void assertConsistent(long post){
  assertThat(count("SELECT COUNT(*) FROM post_likes l JOIN post_dislikes d ON d.post_id=l.post_id AND d.user_id=l.user_id WHERE l.post_id=?",post))
   .as("no user both likes and dislikes post %d",post).isZero();
  var m=db.queryForMap("SELECT like_count,dislike_count FROM post_stats WHERE post_id=?",post);
  assertThat(((Number)m.get("like_count")).longValue()).as("like_count == COUNT(post_likes)").isEqualTo(count("SELECT COUNT(*) FROM post_likes WHERE post_id=?",post));
  assertThat(((Number)m.get("dislike_count")).longValue()).as("dislike_count == COUNT(post_dislikes)").isEqualTo(count("SELECT COUNT(*) FROM post_dislikes WHERE post_id=?",post));
 }

 /**
  * Each of 4 users runs 8 threads × 60 requests on the same post, drawn from like/dislike (mostly) and unlike/undislike.
  * Expected: every request 200, InnoDB deadlock counter unchanged, final state like XOR dislike per user, counters exact.
  */
 @Test void sameUserSamePostConcurrentReactionsNeverDeadlock()throws Exception{
  List<Actor> actors=new ArrayList<>();for(int i=0;i<4;i++)actors.add(actor());
  long post=1_001_000;published(post);
  int threadsPerUser=8,requestsPerThread=60;
  long deadlocksBefore=deadlocks();
  var statuses=new ConcurrentHashMap<Integer,AtomicLong>();
  var pool=Executors.newFixedThreadPool(actors.size()*threadsPerUser);
  var start=new CountDownLatch(1);
  List<Future<?>> futures=new ArrayList<>();
  try{
   for(Actor a:actors)for(int t=0;t<threadsPerUser;t++){long seed=a.id()*31+t;futures.add(pool.submit(()->{
    var rnd=new Random(seed);start.await();
    for(int i=0;i<requestsPerThread;i++){
     int op=rnd.nextInt(10);
     var req=op<4?put("/api/posts/"+post+"/likes"):op<8?put("/api/posts/"+post+"/dislikes")
      :op==8?delete("/api/posts/"+post+"/likes"):delete("/api/posts/"+post+"/dislikes");
     statuses.computeIfAbsent(send(req,a),k->new AtomicLong()).incrementAndGet();
    }
    return null;
   }));}
   start.countDown();
   for(var f:futures)f.get(5,TimeUnit.MINUTES);
  }finally{pool.shutdownNow();}
  long deadlocked=deadlocks()-deadlocksBefore;
  var byStatus=new TreeMap<Integer,Long>();statuses.forEach((k,v)->byStatus.put(k,v.get()));
  System.out.printf("B10 statuses=%s innodb_deadlocks=%d%n",byStatus,deadlocked);
  assertThat(deadlocked).as("InnoDB deadlocks (statuses %s)",byStatus).isZero();
  assertThat(byStatus).as("every request 200").containsOnlyKeys(200);
  assertThat(byStatus.get(200)).isEqualTo((long)actors.size()*threadsPerUser*requestsPerThread);
  assertConsistent(post);
 }

 /** Two requests at a time (one like, one dislike), repeated: the pair that most directly races. */
 @Test void alternatingLikeDislikePairsKeepLikeXorDislike()throws Exception{
  Actor a=actor();long post=1_002_000;published(post);long deadlocksBefore=deadlocks();
  var pool=Executors.newFixedThreadPool(2);var bad=new ConcurrentLinkedQueue<Integer>();
  try{
   for(int round=0;round<300;round++){
    var gate=new CyclicBarrier(2);
    var l=pool.submit(()->{gate.await();return send(put("/api/posts/"+post+"/likes"),a);});
    var d=pool.submit(()->{gate.await();return send(put("/api/posts/"+post+"/dislikes"),a);});
    for(var f:List.of(l,d)){int s=f.get(1,TimeUnit.MINUTES);if(s!=200)bad.add(s);}
    long both=count("SELECT COUNT(*) FROM post_likes l JOIN post_dislikes d ON d.post_id=l.post_id AND d.user_id=l.user_id WHERE l.post_id=?",post);
    assertThat(both).as("round %d: like XOR dislike",round).isZero();
   }
  }finally{pool.shutdownNow();}
  assertThat(bad).as("non-200 responses").isEmpty();
  assertThat(deadlocks()-deadlocksBefore).as("InnoDB deadlocks").isZero();
  assertConsistent(post);
 }
}
