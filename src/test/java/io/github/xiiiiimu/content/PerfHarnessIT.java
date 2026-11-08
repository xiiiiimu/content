package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
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
import java.util.concurrent.ThreadLocalRandom;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 performance harness. Not part of default CI — enable with -Dcontent.perf.enabled=true.
 * Emits raw latencies and summary under target/perf-evidence/.
 */
@SpringBootTest @Testcontainers
@EnabledIfSystemProperty(named="content.perf.enabled", matches="true")
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PerfHarnessIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired FollowingFeed feed;@Autowired InteractionService interactions;@Autowired RecoveryRunner recovery;
 @Test void baselineMixedFeedAndHotInteractions()throws Exception{
  String mode=System.getenv().getOrDefault("CONTENT_PERF_MODE","hybrid");
  int authors=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_AUTHORS","20"));
  int posts=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_POSTS","10"));
  int readers=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_READERS","40"));
  int followsN=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_FOLLOWS","8"));
  int durationSec=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_DURATION_SEC","20"));
  Path out=Path.of(System.getenv().getOrDefault("CONTENT_PERF_OUT","target/perf-evidence/"+mode+"-junit"));
  Files.createDirectories(out);
  recovery.rebuild("perf-operator","m8 baseline seed");
  db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");
  List<Long> authorIds=new ArrayList<>();List<Long> postIds=new ArrayList<>();
  for(int a=1;a<=authors;a++){
   long id=8000+a;authorIds.add(id);
   String m=switch(mode){case "push"->"PUSH";case "pull"->"PULL";default->(a%2==0?"PUSH":"PULL");};
   db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,?)",id,m);
   for(int p=1;p<=posts;p++){
    long post=id*1000L+p;db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,?, 'DRAFT',?)",post,id,"perf");
    publish.publish(id,post,UUID.randomUUID().toString(),0);postIds.add(post);
   }
  }
  for(int r=1;r<=readers;r++){
   long reader=9000+r;
   for(int f=0;f<followsN;f++)follows.change(reader,authorIds.get((r+f)%authors),true);
   // hot authors
   for(int h=0;h<Math.min(5,authors);h++)follows.change(reader,authorIds.get(h),true);
  }
  List<Long> latencies=Collections.synchronizedList(new ArrayList<>());
  List<String> raw=Collections.synchronizedList(new ArrayList<>());
  AtomicCounter errors=new AtomicCounter();AtomicCounter ops=new AtomicCounter();
  long deadline=System.nanoTime()+durationSec*1_000_000_000L;
  var pool=Executors.newFixedThreadPool(8);
  try{
   for(int t=0;t<8;t++)pool.submit(()->{
    ThreadLocalRandom rnd=ThreadLocalRandom.current();
    while(System.nanoTime()<deadline){
     long start=System.nanoTime();boolean write=rnd.nextInt(10)==0;
     try{
      if(write){long post=postIds.get(rnd.nextInt(Math.min(10,postIds.size())));long user=9000+1+rnd.nextInt(readers);interactions.like(user,post,"perf");}
      else{long user=9000+1+rnd.nextInt(readers);feed.read(user,20,null);}
      long ms=(System.nanoTime()-start)/1_000_000L;latencies.add(ms);ops.inc();
      raw.add("{\"op\":\""+(write?"like":"feed")+"\",\"ms\":"+ms+"}");
     }catch(Exception e){errors.inc();raw.add("{\"error\":\""+e.getClass().getSimpleName()+"\"}");}
    }
   });
  }finally{pool.shutdown();pool.awaitTermination(durationSec+30L,TimeUnit.SECONDS);}
  Collections.sort(latencies);
  double p95=percentile(latencies,0.95),p99=percentile(latencies,0.99);
  double thr=ops.get()/ (double)durationSec;
  String summary="{\"mode\":\""+mode+"\",\"ops\":"+ops.get()+",\"errors\":"+errors.get()+",\"throughputOpsPerSec\":"+thr+",\"p50\":"+percentile(latencies,0.50)+",\"p95\":"+p95+",\"p99\":"+p99+",\"authors\":"+authors+",\"posts\":"+posts+",\"readers\":"+readers+"}\n";
  Files.writeString(out.resolve("summary.json"),summary);
  Files.write(out.resolve("raw.ndjson"),raw);
  Files.writeString(out.resolve("commit.txt"),Optional.ofNullable(System.getenv("GIT_COMMIT")).orElse("local"));
  assertThat(ops.get()).isGreaterThan(0);
  assertThat(errors.get()/(double)Math.max(1,ops.get())).isLessThan(0.05);
 }
 private static double percentile(List<Long> sorted,double p){if(sorted.isEmpty())return 0;int i=Math.min(sorted.size()-1,(int)Math.ceil(p*sorted.size())-1);return sorted.get(Math.max(0,i));}
 static final class AtomicCounter{private final java.util.concurrent.atomic.AtomicLong v=new java.util.concurrent.atomic.AtomicLong();void inc(){v.incrementAndGet();}long get(){return v.get();}}
}
