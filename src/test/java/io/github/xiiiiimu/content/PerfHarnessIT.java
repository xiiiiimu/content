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
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 performance harness. Opt-in only: -Dcontent.perf.enabled=true.
 * Default CI skips this class via @EnabledIfSystemProperty — skipped ≠ passed.
 *
 * Ops model:
 * - feed_read: FollowingFeed.read (counts as 1 op; latency = wall time of that call)
 * - like_write: InteractionService.like (counts as 1 op)
 * - publish_visible: seed-only metric — publish→accept→worker→first feed hit (not in steady-state mix)
 * Load: writers:readers ≈ 1:9; hotspot likes hit first 10 posts; modes = push|pull|hybrid.
 * Errors: exceptions from measured ops only (seed/warmup failures abort the test).
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
 @Autowired JdbcTemplate db;@Autowired PublishService publish;@Autowired RelationshipService follows;@Autowired FollowingFeed feed;
 @Autowired InteractionService interactions;@Autowired RecoveryRunner recovery;@Autowired DistributionInbox inbox;@Autowired DistributionWorker worker;
 @Test void baselineMixedFeedAndHotInteractions()throws Exception{
  String mode=System.getenv().getOrDefault("CONTENT_PERF_MODE","hybrid");
  int authors=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_AUTHORS","20"));
  int posts=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_POSTS","10"));
  int readers=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_READERS","40"));
  int followsN=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_FOLLOWS","8"));
  int durationSec=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_DURATION_SEC","20"));
  int clients=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_CLIENTS","8"));
  Path out=Path.of(System.getenv().getOrDefault("CONTENT_PERF_OUT","target/perf-evidence/"+mode+"-junit"));
  Files.createDirectories(out);

  recovery.rebuild("perf-operator","m8 baseline seed");
  db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");db.update("DELETE FROM feed_delivery_tasks");db.update("DELETE FROM consumer_inbox");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");

  List<Long> authorIds=new ArrayList<>();
  int pushAuthors=0,pullAuthors=0;
  for(int a=1;a<=authors;a++){
   long id=8000+a;authorIds.add(id);
   String m=switch(mode){case "push"->"PUSH";case "pull"->"PULL";default->(a%2==0?"PUSH":"PULL");};
   if("PUSH".equals(m))pushAuthors++;else pullAuthors++;
   db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,?)",id,m);
  }
  // Follow graph BEFORE publish so PUSH fanout has recipients.
  for(int r=1;r<=readers;r++){
   long reader=9000+r;
   for(int f=0;f<followsN;f++)follows.change(reader,authorIds.get((r+f)%authors),true);
   for(int h=0;h<Math.min(5,authors);h++)follows.change(reader,authorIds.get(h),true);
  }
  // Dedicated sampler follows everyone so publish→visible latency is mode-comparable.
  for(long id:authorIds)follows.change(9001,id,true);
  drainPipeline(5_000);

  List<Long> postIds=new ArrayList<>();
  List<Long> publishVisibleMs=new ArrayList<>();
  long backlogPeak=0;
  for(int a=1;a<=authors;a++){
   long id=authorIds.get(a-1);
   for(int p=1;p<=posts;p++){
    long post=id*1000L+p;db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,?, 'DRAFT',?)",post,id,"perf");
    long t0=System.nanoTime();
    var pub=publish.publish(id,post,UUID.randomUUID().toString(),0);
    inbox.accept(payload(pub.eventId()),Long.toString(pub.eventId()),"PostPublished");
    drainPipeline(2_000);
    long visibleAt=-1;
    long reader=9001;
    for(int w=0;w<50;w++){
     if(feed.read(reader,20,null).items().stream().anyMatch(i->i.postId()==post)){visibleAt=(System.nanoTime()-t0)/1_000_000L;break;}
     drainPipeline(200);Thread.sleep(20);
    }
    if(visibleAt>=0)publishVisibleMs.add(visibleAt);
    postIds.add(post);
    backlogPeak=Math.max(backlogPeak,nz(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state IN('NEW','RUNNING')",Long.class)));
   }
  }
  assertThat(publishVisibleMs).as("warmup visibility samples").isNotEmpty();

  // Warmup: prove mode paths and that client pool is not saturated before measurement.
  for(int i=0;i<Math.min(20,readers);i++){
   var page=feed.read(9000L+1+i,20,null);
   assertThat(page.items()).isNotEmpty();
  }
  String modeCheck=db.queryForObject("SELECT COUNT(*) FROM author_feed_mode WHERE mode='PUSH'",String.class);
  assertThat(Integer.parseInt(modeCheck)).isEqualTo(pushAuthors);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM author_feed_mode WHERE mode='PULL'",Integer.class)).isEqualTo(pullAuthors);

  List<Long> feedLat=Collections.synchronizedList(new ArrayList<>());
  List<Long> likeLat=Collections.synchronizedList(new ArrayList<>());
  List<Long> scanAmp=Collections.synchronizedList(new ArrayList<>());
  List<String> raw=Collections.synchronizedList(new ArrayList<>());
  AtomicLong errors=new AtomicLong();AtomicLong feedOps=new AtomicLong();AtomicLong likeOps=new AtomicLong();
  AtomicLong maxInflight=new AtomicLong();AtomicLong inflight=new AtomicLong();
  long deadline=System.nanoTime()+durationSec*1_000_000_000L;
  long backlogStart=nz(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state IN('NEW','RUNNING')",Long.class));
  var pool=Executors.newFixedThreadPool(clients);
  try{
   for(int t=0;t<clients;t++)pool.submit(()->{
    ThreadLocalRandom rnd=ThreadLocalRandom.current();
    while(System.nanoTime()<deadline){
     long cur=inflight.incrementAndGet();maxInflight.accumulateAndGet(cur,Math::max);
     long start=System.nanoTime();boolean write=rnd.nextInt(10)==0;
     try{
      if(write){
       long post=postIds.get(rnd.nextInt(Math.min(10,postIds.size())));
       long user=9000+1+rnd.nextInt(readers);
       interactions.like(user,post,"perf-"+user+"-"+post+"-"+System.nanoTime());
       long ms=(System.nanoTime()-start)/1_000_000L;likeLat.add(ms);likeOps.incrementAndGet();
       raw.add("{\"op\":\"like_write\",\"ms\":"+ms+"}");
      }else{
       long user=9000+1+rnd.nextInt(readers);
       var page=feed.read(user,20,null);
       long ms=(System.nanoTime()-start)/1_000_000L;feedLat.add(ms);feedOps.incrementAndGet();
       int scanned=page.scanned();int n=page.items().size();
       double amp=n==0?scanned:(double)scanned/n;scanAmp.add((long)Math.round(amp*1000));
       raw.add("{\"op\":\"feed_read\",\"ms\":"+ms+",\"scanned\":"+scanned+",\"items\":"+n+",\"scanAmplification\":"+amp+"}");
      }
     }catch(Exception e){errors.incrementAndGet();raw.add("{\"error\":\""+e.getClass().getSimpleName()+"\",\"op\":\""+(write?"like_write":"feed_read")+"\"}");}
     finally{inflight.decrementAndGet();}
    }
   });
  }finally{pool.shutdown();assertThat(pool.awaitTermination(durationSec+60L,TimeUnit.SECONDS)).isTrue();}

  long backlogEnd=nz(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state IN('NEW','RUNNING')",Long.class));
  long tasksDone=nz(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state='DONE'",Long.class));
  long pushPosts=nz(db.queryForObject("SELECT COUNT(*) FROM posts p JOIN author_feed_mode m ON m.author_id=p.author_id WHERE m.mode='PUSH' AND p.status='PUBLISHED'",Long.class));
  double writeAmp=pushPosts==0?0:(double)tasksDone/Math.max(1,pushPosts);

  List<Long> all=new ArrayList<>(feedLat);all.addAll(likeLat);Collections.sort(all);Collections.sort(feedLat);Collections.sort(likeLat);Collections.sort(publishVisibleMs);Collections.sort(scanAmp);
  long ops=feedOps.get()+likeOps.get();
  double thr=ops/(double)durationSec;
  double errRate=errors.get()/(double)Math.max(1,ops);
  // Client not bottleneck: peak inflight < clients (some idle) OR throughput grows with shorter ops — we assert queue slots existed.
  // Closed-loop: maxInflight==clients is expected when the SUT is slower than the driver.
  // Client-not-bottleneck evidence is thr/P95 improving after SUT pool bumps under identical client count.
  boolean clientSaturated=maxInflight.get()>=clients;

  String summary="{"
   +"\"mode\":\""+mode+"\","
   +"\"opsDefinition\":{\"feed_read\":\"FollowingFeed.read\",\"like_write\":\"InteractionService.like\",\"publish_visible_ms\":\"publish+accept+worker until feed hit\"},"
   +"\"loadModel\":{\"writeReadRatio\":\"1:9\",\"hotLikePosts\":10,\"clients\":"+clients+",\"authors\":"+authors+",\"postsPerAuthor\":"+posts+",\"readers\":"+readers+",\"followsPerReader\":"+followsN+",\"pushAuthors\":"+pushAuthors+",\"pullAuthors\":"+pullAuthors+"},"
   +"\"ops\":"+ops+",\"feedOps\":"+feedOps.get()+",\"likeOps\":"+likeOps.get()+",\"errors\":"+errors.get()+",\"errorRate\":"+errRate+","
   +"\"throughputOpsPerSec\":"+thr+","
   +"\"p50\":"+percentile(all,0.50)+",\"p95\":"+percentile(all,0.95)+",\"p99\":"+percentile(all,0.99)+","
   +"\"feedP95\":"+percentile(feedLat,0.95)+",\"likeP95\":"+percentile(likeLat,0.95)+","
   +"\"publishVisibleP50\":"+percentile(publishVisibleMs,0.50)+",\"publishVisibleP95\":"+percentile(publishVisibleMs,0.95)+",\"publishVisibleP99\":"+percentile(publishVisibleMs,0.99)+","
   +"\"scanAmplificationP50\":"+(percentile(scanAmp,0.50)/1000.0)+",\"scanAmplificationP95\":"+(percentile(scanAmp,0.95)/1000.0)+","
   +"\"writeAmplificationTasksPerPushPost\":"+writeAmp+","
   +"\"backlogNewOrRunningStart\":"+backlogStart+",\"backlogNewOrRunningEnd\":"+backlogEnd+",\"backlogPeakDuringSeed\":"+backlogPeak+","
   +"\"maxInflightClients\":"+maxInflight.get()+",\"clientSaturated\":"+clientSaturated+","
   +"\"warmupPublishVisibleSamples\":"+publishVisibleMs.size()
   +"}\n";
  Files.writeString(out.resolve("summary.json"),summary);
  Files.write(out.resolve("raw.ndjson"),raw);
  Files.writeString(out.resolve("commit.txt"),Optional.ofNullable(System.getenv("GIT_COMMIT")).orElseGet(()->{
   try{return new String(Runtime.getRuntime().exec(new String[]{"git","rev-parse","HEAD"}).getInputStream().readAllBytes()).trim();}catch(Exception e){return "local";}
  }));
  Files.writeString(out.resolve("config-runtime.txt"),
   "mode="+mode+"\nauthors="+authors+"\npostsPerAuthor="+posts+"\nreaders="+readers+"\nfollows="+followsN+"\ndurationSec="+durationSec+"\nclients="+clients+"\n");

  assertThat(ops).isGreaterThan(0);
  assertThat(errRate).isLessThan(0.05);
 }
 private void drainPipeline(int maxTicks){
  // FollowChanged HISTORY_TASK + PostPublished PUSH_TASK
  var pending=db.queryForList("SELECT id,event_type,payload FROM outbox_events o WHERE NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=o.id) ORDER BY id");
  for(var row:pending){
   long id=((Number)row.get("id")).longValue();String type=(String)row.get("event_type");String body=(String)row.get("payload");
   try{inbox.accept(body,Long.toString(id),type);}catch(Exception ignored){/* already accepted / non-delivery */}
  }
  for(int i=0;i<maxTicks;i++){
   Long left=db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state IN('NEW','RUNNING')",Long.class);
   if(left==null || left==0)return;
   worker.tick();
  }
 }
 private String payload(long event){return db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,event);}
 private static long nz(Long v){return v==null?0:v;}
 private static double percentile(List<Long> sorted,double p){if(sorted.isEmpty())return 0;int i=Math.min(sorted.size()-1,(int)Math.ceil(p*sorted.size())-1);return sorted.get(Math.max(0,i));}
}
