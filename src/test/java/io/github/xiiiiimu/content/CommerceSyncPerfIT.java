package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Hardening B4/B5 measurement on a real broker (Testcontainers RocketMQ, {@link CommerceMqFixture}). Opt-in, never in CI:
 * {@code CONTENT_REAL_MQ_IT=true}, {@code -Dcontent.b5.perf=true -Dcontent.b5.out=target/<new dir>}, and run only this
 * method ({@code -Dit.test=CommerceSyncPerfIT#perfCacheSync}); the inherited suite is not part of the measurement.
 * <p>Same sale settings as the dispatch comparison B2 tier: batch claims of 20 with concurrency 2, bridge batch 20,
 * 20 consumer threads, scan delay 50 ms. Purchases go through the real path: Redis reserve → bridge → dispatch → broker →
 * consumer settle → cache sync. Recorded per tier (60/s and 120/s for 60 s, each on a fresh campaign and fresh buyers):
 * <ul>
 * <li>MySQL {@code Com_commit} during the tier minus the idle commit rate (measured first, 30 s, everything running but no
 *   purchases) times the tier's duration, divided by orders: commits per order;</li>
 * <li>purchase return → client sees SUCCEEDED ({@code getRequest} polled every 10 ms; reads Redis first like the HTTP
 *   endpoint), and order commit ({@code paid_at_ms}) → seen, the cache-sync part of it;</li>
 * <li>settled orders per second.</li>
 * </ul>
 * The idle commit rate itself is the B4 number (empty dispatch claims are transactions too).
 * <p>Standalone (own containers and properties, same values as {@code CommerceIngressRevisionIT} except the sale settings
 * above): extending that suite would let its {@code content.sale.scan-enabled=false} win, and nothing would be dispatched.
 */
@SpringBootTest @Testcontainers
@EnabledIfEnvironmentVariable(named="CONTENT_REAL_MQ_IT",matches="true")
@EnabledIfSystemProperty(named="content.b5.perf",matches="true")
class CommerceSyncPerfIT {
 static final String TOPIC="content-sale-perf-"+UUID.randomUUID().toString().substring(0,8);
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>("adobe/s3mock:3.12.0").withExposedPorts(9090);
 @DynamicPropertySource static void perf(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.media.enabled",()->"true");r.add("content.media.s3-endpoint",()->"http://"+S3.getHost()+":"+S3.getMappedPort(9090));
  r.add("content.media.bucket",()->"content-media");r.add("content.media.secret-key",()->"media-test-secret-key-not-for-production-01");
  r.add("content.media.ffmpeg-image",()->"");r.add("content.assistant.python-url",()->"");
  r.add("content.media.scan-delay-millis",()->"3600000");r.add("content.sale.sync-settle",()->"false");
  r.add("content.sale.producer-enabled",()->"true");r.add("content.sale.consumer-enabled",()->"true");
  r.add("content.sale.namesrv",CommerceMqFixture::namesrv);r.add("content.sale.topic",()->TOPIC);
  r.add("content.sale.consumer-group",()->TOPIC);r.add("content.sale.consume-threads",()->"20");
  r.add("content.sale.scan-enabled",()->"true");r.add("content.sale.scan-delay-millis",()->"50");
  r.add("content.sale.bridge-batch-size",()->"20");r.add("content.sale.dispatch-mode",()->"batch");
  r.add("content.sale.dispatch-batch-max",()->"20");r.add("content.sale.dispatch-concurrency",()->"2");
  r.add("content.sale.latency-enabled",()->"false");
 }
 @BeforeAll static void topic()throws Exception{CommerceMqFixture.topic(TOPIC,"commerce-sync-perf-topic.log");}
 @Autowired SaleService sales;@Autowired MaterialService materials;@Autowired PublishService publish;
 @Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired SaleIngress ingress;
 record Material(long author,long post){}
 /** Published material without a campaign (same steps as CommerceIngressRevisionIT.unsold). */
 Material material(){
  String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
  long author=auth.register("jma"+suffix,"test-author-password").userId();
  var m=materials.createDraft(author,"perf","sale",List.of("test"),"PUBLIC_PREVIEW",null,true,true,new byte[]{1,2,3},"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,m.postId(),UUID.randomUUID().toString(),0);
  return new Material(author,m.postId());
 }

 static Path out(){return Path.of(System.getProperty("content.b5.out","target/b5-perf-"+System.currentTimeMillis()));}
 long comCommit(){return Long.parseLong(db.queryForObject("SHOW GLOBAL STATUS LIKE 'Com_commit'",(rs,i)->rs.getString(2)));}
 static Map<String,Object> dist(List<Long> ms){
  var s=ms.stream().sorted().toList();Map<String,Object> m=new LinkedHashMap<>();m.put("n",s.size());
  if(s.isEmpty())return m;
  for(double p:new double[]{.5,.95,.99})m.put("p"+(int)Math.round(p*100)+"_ms",s.get(Math.max(0,(int)Math.ceil(p*s.size())-1)));
  m.put("max_ms",s.get(s.size()-1));m.put("avg_ms",Math.round(s.stream().mapToLong(x->x).average().orElse(0)*10)/10.0);return m;
 }
 List<Long> buyers(int n,String tag){
  long now=System.currentTimeMillis();
  db.execute((org.springframework.jdbc.core.ConnectionCallback<Object>)c->{try(var st=c.createStatement()){
   st.execute("SET SESSION cte_max_recursion_depth=100000");
   st.execute("INSERT INTO users(username,password_hash,created_at_ms) WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM s WHERE i<"+n+") SELECT CONCAT('"+tag+"',i),'perf-no-login',"+now+" FROM s");
  }return null;});
  return db.queryForList("SELECT id FROM users WHERE username LIKE ? ORDER BY id",Long.class,tag+"%");
 }
 record Pending(long buyer,long returnedNanos){}

 Map<String,Object> tier(String name,int rate,int seconds)throws Exception{
  int n=rate*seconds;
  var m=material();long now=System.currentTimeMillis();
  var campaign=sales.createCampaign(m.author(),m.post(),100,n,now-1000,now+3600000);
  var ids=buyers(n,"perf"+name+UUID.randomUUID().toString().substring(0,6)+"u");
  var outstanding=new ConcurrentHashMap<String,Pending>();
  var returnToSeen=new ConcurrentLinkedQueue<Long>();var seenWall=new ConcurrentHashMap<String,Long>();
  var failures=new ConcurrentHashMap<String,AtomicLong>();var rejected=new AtomicLong();
  var pool=Executors.newFixedThreadPool(32);var ticker=Executors.newSingleThreadScheduledExecutor();
  var next=new AtomicInteger();var polling=new AtomicBoolean(true);
  var poller=new Thread(()->{while(polling.get()){
   for(var e:outstanding.entrySet()){
    try{var v=sales.getRequest(e.getValue().buyer(),e.getKey());
     if(!"PROCESSING".equals(v.state())){long t=System.nanoTime();seenWall.put(e.getKey(),System.currentTimeMillis());
      if("SUCCEEDED".equals(v.state()))returnToSeen.add((t-e.getValue().returnedNanos())/1_000_000L);else rejected.incrementAndGet();
      outstanding.remove(e.getKey());}}
    catch(RuntimeException x){/* 404 until the bridge persisted it is impossible: Redis has it; count anything else */failures.computeIfAbsent("poll:"+x.getClass().getSimpleName(),k->new AtomicLong()).incrementAndGet();}
   }
   try{Thread.sleep(10);}catch(InterruptedException x){return;}
  }},"perf-poller");
  poller.setDaemon(true);poller.start();
  long c0=comCommit(),t0=System.nanoTime();
  var done=new CountDownLatch(n);
  ticker.scheduleAtFixedRate(()->{int i=next.getAndIncrement();if(i>=n)return;pool.submit(()->{
   try{long buyer=ids.get(i);var v=sales.purchase(buyer,campaign.id(),"perf-"+name+"-"+i);outstanding.put(v.reservationId(),new Pending(buyer,System.nanoTime()));}
   catch(RuntimeException x){failures.computeIfAbsent("purchase:"+x.getMessage(),k->new AtomicLong()).incrementAndGet();}
   finally{done.countDown();}});},0,1_000_000L/rate,TimeUnit.MICROSECONDS);
  assertThat(done.await(seconds+120L,TimeUnit.SECONDS)).as("all purchases submitted").isTrue();
  ticker.shutdownNow();pool.shutdown();
  long submitNanos=System.nanoTime()-t0;
  long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
  while(!outstanding.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(20);
  polling.set(false);poller.join(5000);
  long elapsedNanos=System.nanoTime()-t0,commits=comCommit()-c0;
  var paid=db.queryForList("SELECT reservation_id r,paid_at_ms p FROM material_orders WHERE campaign_id=?",campaign.id());
  var paidToSeen=new ArrayList<Long>();long firstPaid=Long.MAX_VALUE,lastPaid=0;
  for(var row:paid){long p=((Number)row.get("p")).longValue();firstPaid=Math.min(firstPaid,p);lastPaid=Math.max(lastPaid,p);
   var seen=seenWall.get((String)row.get("r"));if(seen!=null)paidToSeen.add(Math.max(0,seen-p));}
  Map<String,Object> r=new LinkedHashMap<>();
  r.put("tier",name);r.put("rate",rate);r.put("seconds",seconds);r.put("purchases",n);
  r.put("orders",paid.size());r.put("rejected_seen",rejected.get());r.put("unresolved",outstanding.size());r.put("failures",new TreeMap<>(failures));
  r.put("submit_ms",submitNanos/1_000_000);r.put("elapsed_to_drained_ms",elapsedNanos/1_000_000);
  r.put("orders_per_sec_settled",paid.size()<2?0:Math.round(paid.size()*1000.0/Math.max(1,lastPaid-firstPaid)*10)/10.0);
  r.put("com_commit",commits);
  r.put("purchase_return_to_seen_succeeded",dist(List.copyOf(returnToSeen)));
  r.put("order_commit_to_seen",dist(paidToSeen));
  return r;
 }

 @Test void perfCacheSync()throws Exception{
  Files.createDirectories(out());
  // Ready: the scanner has recovered the sale runtime into Redis.
  long ready=System.nanoTime();
  while(!ingress.healthy()&&System.nanoTime()-ready<TimeUnit.SECONDS.toNanos(60))Thread.sleep(100);
  assertThat(ingress.healthy()).isTrue();
  var warm=tier("warm",20,10);
  Thread.sleep(5000);
  long i0=comCommit();long idleT0=System.nanoTime();Thread.sleep(30_000);
  double idleRate=(comCommit()-i0)/((System.nanoTime()-idleT0)/1e9);
  Map<String,Object> result=new LinkedHashMap<>();
  result.put("idle_commits_per_sec",Math.round(idleRate*100)/100.0);
  result.put("warm",warm);
  for(int rate:new int[]{60,120}){
   var t=tier("r"+rate,rate,60);
   double attributable=((Number)t.get("com_commit")).longValue()-idleRate*((Number)t.get("elapsed_to_drained_ms")).longValue()/1000.0;
   t.put("commits_attributable",Math.round(attributable));
   int orders=((Number)t.get("orders")).intValue();
   t.put("commits_per_order",orders==0?null:Math.round(attributable/orders*1000)/1000.0);
   result.put("r"+rate,t);
   Thread.sleep(10_000);
  }
  Files.writeString(out().resolve("commerce-sync-perf.json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result),StandardOpenOption.CREATE_NEW);
 }
}
