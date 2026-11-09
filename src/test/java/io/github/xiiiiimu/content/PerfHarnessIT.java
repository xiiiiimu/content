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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 performance harness. Opt-in only: -Dcontent.perf.enabled=true.
 * Default CI skips this class via @EnabledIfSystemProperty — skipped ≠ passed.
 *
 * Before timing, asserts mode coverage on the seeded dataset:
 * PUSH Redis inbox non-empty + expected members; PULL SQL candidates present;
 * hybrid both paths contribute; feed result set matches the shared post corpus.
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
 @Autowired FeedRedis redis;
 @Test void baselineMixedFeedAndHotInteractions()throws Exception{
  String mode=System.getenv().getOrDefault("CONTENT_PERF_MODE","hybrid");
  int authors=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_AUTHORS","20"));
  int posts=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_POSTS","10"));
  int readers=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_READERS","40"));
  int followsN=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_FOLLOWS","8"));
  int durationSec=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_DURATION_SEC","20"));
  int clients=Integer.parseInt(System.getenv().getOrDefault("CONTENT_PERF_CLIENTS","8"));
  String phase=System.getenv().getOrDefault("CONTENT_PERF_PHASE","baseline");
  Path out=Path.of(System.getenv().getOrDefault("CONTENT_PERF_OUT","target/perf-evidence/"+mode+"-junit"));
  Files.createDirectories(out);
  Runtime rt=Runtime.getRuntime();
  long heapMax=rt.maxMemory();
  long heapUsedBefore=rt.totalMemory()-rt.freeMemory();

  recovery.rebuild("perf-operator","m8 baseline seed");
  db.update("DELETE FROM post_likes");db.update("DELETE FROM post_stats");db.update("DELETE FROM feed_delivery_tasks");db.update("DELETE FROM consumer_inbox");
  db.update("DELETE FROM follow_history");db.update("DELETE FROM follows");db.update("DELETE FROM follow_graph_version");
  db.update("DELETE FROM outbox_events");db.update("DELETE FROM posts");db.update("DELETE FROM author_feed_mode");

  List<Long> authorIds=new ArrayList<>();
  List<Long> pushAuthorIds=new ArrayList<>();List<Long> pullAuthorIds=new ArrayList<>();
  for(int a=1;a<=authors;a++){
   long id=8000+a;authorIds.add(id);
   String m=switch(mode){case "push"->"PUSH";case "pull"->"PULL";default->(a%2==0?"PUSH":"PULL");};
   if("PUSH".equals(m))pushAuthorIds.add(id);else pullAuthorIds.add(id);
   db.update("INSERT INTO author_feed_mode(author_id,mode) VALUES(?,?)",id,m);
  }
  for(int r=1;r<=readers;r++){
   long reader=9000+r;
   for(int f=0;f<followsN;f++)follows.change(reader,authorIds.get((r+f)%authors),true);
   for(int h=0;h<Math.min(5,authors);h++)follows.change(reader,authorIds.get(h),true);
  }
  for(long id:authorIds)follows.change(9001,id,true);
  drainPipeline(5_000);

  List<Long> postIds=new ArrayList<>();
  List<Long> pushPostIds=new ArrayList<>();List<Long> pullPostIds=new ArrayList<>();
  List<Long> publishVisibleMs=new ArrayList<>();
  long backlogPeak=0;
  for(int a=1;a<=authors;a++){
   long id=authorIds.get(a-1);boolean push=pushAuthorIds.contains(id);
   for(int p=1;p<=posts;p++){
    long post=id*1000L+p;db.update("INSERT INTO posts(id,author_id,status,body) VALUES(?,?, 'DRAFT',?)",post,id,"perf");
    long t0=System.nanoTime();
    var pub=publish.publish(id,post,UUID.randomUUID().toString(),0);
    inbox.accept(payload(pub.eventId()),Long.toString(pub.eventId()),"PostPublished");
    drainPipeline(2_000);
    long visibleAt=-1;
    for(int w=0;w<50;w++){
     if(feed.read(9001,20,null).items().stream().anyMatch(i->i.postId()==post)){visibleAt=(System.nanoTime()-t0)/1_000_000L;break;}
     drainPipeline(200);Thread.sleep(20);
    }
    if(visibleAt>=0)publishVisibleMs.add(visibleAt);
    postIds.add(post);
    if(push)pushPostIds.add(post);else pullPostIds.add(post);
    backlogPeak=Math.max(backlogPeak,nz(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state IN('NEW','RUNNING')",Long.class)));
   }
  }
  assertThat(publishVisibleMs).as("warmup visibility samples").isNotEmpty();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM author_feed_mode WHERE mode='PUSH'",Integer.class)).isEqualTo(pushAuthorIds.size());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM author_feed_mode WHERE mode='PULL'",Integer.class)).isEqualTo(pullAuthorIds.size());

  ModeCoverage coverage=assertModeCoverage(mode,pushPostIds,pullPostIds);
  Files.writeString(out.resolve("mode-coverage.json"),coverage.json()+"\n");

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
  long drainT0=System.nanoTime();
  drainPipeline(50_000);
  long backlogAfterDrain=nz(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state IN('NEW','RUNNING')",Long.class));
  long backlogRecoveryMs=(System.nanoTime()-drainT0)/1_000_000L;
  long heapUsedAfter=rt.totalMemory()-rt.freeMemory();
  long tasksDone=nz(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE state='DONE'",Long.class));
  long pushPosts=nz(db.queryForObject("SELECT COUNT(*) FROM posts p JOIN author_feed_mode m ON m.author_id=p.author_id WHERE m.mode='PUSH' AND p.status='PUBLISHED'",Long.class));
  double writeAmp=pushPosts==0?0:(double)tasksDone/Math.max(1,pushPosts);

  List<Long> all=new ArrayList<>(feedLat);all.addAll(likeLat);Collections.sort(all);Collections.sort(feedLat);Collections.sort(likeLat);Collections.sort(publishVisibleMs);Collections.sort(scanAmp);
  long ops=feedOps.get()+likeOps.get();
  double thr=ops/(double)durationSec;
  double errRate=errors.get()/(double)Math.max(1,ops);
  boolean clientSaturated=maxInflight.get()>=clients;

  String summary="{"
   +"\"mode\":\""+mode+"\",\"phase\":\""+phase+"\",\"valid\":true,"
   +"\"opsDefinition\":{\"feed_read\":\"FollowingFeed.read\",\"like_write\":\"InteractionService.like\",\"publish_visible_ms\":\"publish+accept+worker until feed hit\"},"
   +"\"loadModel\":{\"writeReadRatio\":\"1:9\",\"hotLikePosts\":10,\"clients\":"+clients+",\"authors\":"+authors+",\"postsPerAuthor\":"+posts+",\"readers\":"+readers+",\"followsPerReader\":"+followsN+",\"pushAuthors\":"+pushAuthorIds.size()+",\"pullAuthors\":"+pullAuthorIds.size()+"},"
   +"\"modeCoverage\":"+coverage.json()+","
   +"\"ops\":"+ops+",\"feedOps\":"+feedOps.get()+",\"likeOps\":"+likeOps.get()+",\"errors\":"+errors.get()+",\"errorRate\":"+errRate+","
   +"\"throughputOpsPerSec\":"+thr+","
   +"\"p50\":"+percentile(all,0.50)+",\"p95\":"+percentile(all,0.95)+",\"p99\":"+percentile(all,0.99)+","
   +"\"feedP95\":"+percentile(feedLat,0.95)+",\"likeP95\":"+percentile(likeLat,0.95)+","
   +"\"publishVisibleP50\":"+percentile(publishVisibleMs,0.50)+",\"publishVisibleP95\":"+percentile(publishVisibleMs,0.95)+",\"publishVisibleP99\":"+percentile(publishVisibleMs,0.99)+","
   +"\"scanAmplificationP50\":"+(percentile(scanAmp,0.50)/1000.0)+",\"scanAmplificationP95\":"+(percentile(scanAmp,0.95)/1000.0)+","
   +"\"writeAmplificationTasksPerPushPost\":"+writeAmp+","
   +"\"backlogNewOrRunningStart\":"+backlogStart+",\"backlogNewOrRunningEnd\":"+backlogEnd+",\"backlogPeakDuringSeed\":"+backlogPeak+","
   +"\"backlogAfterDrain\":"+backlogAfterDrain+",\"backlogRecoveryMs\":"+backlogRecoveryMs+","
   +"\"heapMaxBytes\":"+heapMax+",\"heapUsedBeforeBytes\":"+heapUsedBefore+",\"heapUsedAfterBytes\":"+heapUsedAfter+","
   +"\"maxInflightClients\":"+maxInflight.get()+",\"clientSaturated\":"+clientSaturated+","
   +"\"warmupPublishVisibleSamples\":"+publishVisibleMs.size()
   +"}\n";
  Files.writeString(out.resolve("summary.json"),summary);
  Files.write(out.resolve("raw.ndjson"),raw);
  Files.writeString(out.resolve("commit.txt"),Optional.ofNullable(System.getenv("GIT_COMMIT")).orElseGet(()->{
   try{return new String(Runtime.getRuntime().exec(new String[]{"git","rev-parse","HEAD"}).getInputStream().readAllBytes()).trim();}catch(Exception e){return "local";}
  }));
  Files.writeString(out.resolve("config-runtime.txt"),
   "mode="+mode+"\nphase="+phase+"\nauthors="+authors+"\npostsPerAuthor="+posts+"\nreaders="+readers+"\nfollows="+followsN+"\ndurationSec="+durationSec+"\nclients="+clients+"\nvalid=true\nheapMaxBytes="+heapMax+"\n");

  assertThat(ops).isGreaterThan(0);
  assertThat(errRate).isLessThan(0.05);
  assertThat(backlogAfterDrain).as("backlog must drain after steady").isZero();
 }
 /** Assert PUSH inbox / PULL SQL / hybrid both paths before any timed op. */
 private ModeCoverage assertModeCoverage(String mode,List<Long> pushPosts,List<Long> pullPosts){
  long now=db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class);
  long since=Math.max(0,now-2_592_000_000L);
  Long pullCandidates=db.queryForObject("""
   SELECT COUNT(*) FROM posts p JOIN follows f ON f.followee_id=p.author_id AND f.follower_id=9001 AND f.status='FOLLOWING'
   WHERE p.delivery_mode='PULL' AND p.status='PUBLISHED' AND p.published_at_ms BETWEEN ? AND ?
   """,Long.class,since,now);
  Long pushAuthority=db.queryForObject("""
   SELECT COUNT(*) FROM posts p JOIN follows f ON f.followee_id=p.author_id AND f.follower_id=9001 AND f.status='FOLLOWING'
   WHERE p.delivery_mode='PUSH' AND p.status='PUBLISHED' AND p.published_at_ms BETWEEN ? AND ?
   """,Long.class,since,now);
  long inboxCard=redis.connection().sync().zcard("feed:v2:{9001}:inbox");
  Set<Long> feedIds=new LinkedHashSet<>();
  String cursor=null;
  for(int page=0;page<20;page++){
   var resp=feed.read(9001,50,cursor);
   for(var item:resp.items())feedIds.add(item.postId());
   if(resp.nextCursor()==null)break;cursor=resp.nextCursor();
  }
  Set<Long> expected=new LinkedHashSet<>();expected.addAll(pushPosts);expected.addAll(pullPosts);
  assertThat(feedIds).as("feed result set ⊆ seeded corpus").isSubsetOf(expected);
  assertThat(feedIds).as("feed result set covers seeded corpus on sampler").containsAll(expected);

  boolean pushPath=inboxCard>0 && nz(pushAuthority)>0;
  boolean pullPath=nz(pullCandidates)>0;
  switch(mode){
   case "push" -> {
    assertThat(pullPosts).isEmpty();
    assertThat(inboxCard).as("PUSH Redis inbox non-empty").isGreaterThan(0);
    assertThat(pushAuthority).as("PUSH authority candidates").isGreaterThan(0);
    long publishTasks=nz(db.queryForObject("SELECT COUNT(*) FROM feed_delivery_tasks WHERE kind='PUBLISH' AND state='DONE'",Long.class));
    assertThat(publishTasks).as("PUSH publish tasks done").isEqualTo(pushPosts.size());
    assertThat(feedIds).containsAll(pushPosts);
   }
   case "pull" -> {
    assertThat(pushPosts).isEmpty();
    assertThat(pullCandidates).as("PULL SQL source valid").isGreaterThan(0);
    long pushPublish=nz(db.queryForObject("SELECT COUNT(*) FROM consumer_inbox WHERE decision='PUSH_TASK'",Long.class));
    assertThat(pushPublish).as("PULL mode has no PostPublished PUSH_TASK").isZero();
    assertThat(feedIds).containsAll(pullPosts);
   }
   default -> {
    assertThat(pushPosts).isNotEmpty();assertThat(pullPosts).isNotEmpty();
    assertThat(inboxCard).as("hybrid uses PUSH Redis inbox").isGreaterThan(0);
    assertThat(pullCandidates).as("hybrid uses PULL SQL source").isGreaterThan(0);
    assertThat(pushAuthority).as("hybrid push-authority present").isGreaterThan(0);
    assertThat(feedIds).contains(pushPosts.get(0),pullPosts.get(0));
    assertThat(feedIds.stream().filter(pushPosts::contains).collect(Collectors.toSet())).isNotEmpty();
    assertThat(feedIds.stream().filter(pullPosts::contains).collect(Collectors.toSet())).isNotEmpty();
   }
  }
  return new ModeCoverage(mode,inboxCard,nz(pullCandidates),nz(pushAuthority),feedIds.size(),expected.size(),pushPath,pullPath);
 }
 private void drainPipeline(int maxTicks){
  var pending=db.queryForList("SELECT id,event_type,payload FROM outbox_events o WHERE NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=o.id) ORDER BY id");
  for(var row:pending){
   long id=((Number)row.get("id")).longValue();String type=(String)row.get("event_type");String body=(String)row.get("payload");
   try{inbox.accept(body,Long.toString(id),type);}catch(Exception ignored){}
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
 record ModeCoverage(String mode,long inboxZcard,long pullCandidates,long pushAuthority,int feedDistinct,int corpusSize,boolean pushPath,boolean pullPath){
  String json(){return "{\"mode\":\""+mode+"\",\"inboxZcard\":"+inboxZcard+",\"pullCandidates\":"+pullCandidates+",\"pushAuthority\":"+pushAuthority+",\"feedDistinct\":"+feedDistinct+",\"corpusSize\":"+corpusSize+",\"pushPath\":"+pushPath+",\"pullPath\":"+pullPath+"}";}
 }
}
