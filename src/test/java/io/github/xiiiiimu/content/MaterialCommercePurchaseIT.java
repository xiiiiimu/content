package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MaterialCommercePurchaseIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>(DockerImageName.parse("adobe/s3mock:3.12.0")).withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.media.enabled",()->"true");
  r.add("content.media.s3-endpoint",()->"http://"+S3.getHost()+":"+S3.getMappedPort(9090));
  r.add("content.media.bucket",()->"content-media");
  r.add("content.media.secret-key",()->"content-secret-key-32-bytes-minimum");
  r.add("content.media.ffmpeg-image",()->"");
  r.add("content.assistant.python-url",()->"");
  r.add("content.media.scan-delay-millis",()->"3600000");
  r.add("content.sale.sync-settle",()->"true");
 }
 @Autowired JdbcTemplate db;@Autowired MaterialService materials;@Autowired PublishService publish;
 @Autowired SaleService sales;@Autowired AuthService auth;@Autowired GiveawayService giveaways;
 @Autowired RelationshipService follows;

 @BeforeEach void wipe(){
  db.update("DELETE FROM material_sale_consume_receipts");
  db.update("DELETE FROM material_refund_requests");
  db.update("DELETE FROM material_orders");
  db.update("DELETE FROM material_purchase_requests");
  db.update("DELETE FROM material_sale_campaigns");
  db.update("DELETE FROM merchant_blacklist");
  db.update("DELETE FROM account_material_decision_locks");
  db.update("DELETE FROM material_entitlements");
 }

 @Test void concurrentStockAndOneOrderPerAccount()throws Exception{
  long author=register("sale_author","password-a1");
  var mat=materials.createDraft(author,"限量夜景","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  long now=System.currentTimeMillis();
  var campaign=sales.createCampaign(author,mat.postId(),990,3,now-1_000,now+3_600_000);
  assertThat(campaign.stockCap()).isEqualTo(3);

  int buyers=8;
  long[] users=new long[buyers];
  for(int i=0;i<buyers;i++){
   users[i]=register("sale_buyer_"+i,"password-b"+i);
   follows.change(users[i],author,true);
  }
  ExecutorService pool=Executors.newFixedThreadPool(buyers);
  CountDownLatch ready=new CountDownLatch(buyers),go=new CountDownLatch(1);
  AtomicInteger ok=new AtomicInteger(),reject=new AtomicInteger();
  List<Future<?>> futures=new ArrayList<>();
  for(int i=0;i<buyers;i++){
   int idx=i;
   futures.add(pool.submit(()->{
    try{
     ready.countDown();go.await(30,TimeUnit.SECONDS);
     try{
      var p=sales.purchase(users[idx],campaign.id(),"ck-"+idx);
      if("SUCCEEDED".equals(p.state()))ok.incrementAndGet();
      else reject.incrementAndGet();
      var again=sales.purchase(users[idx],campaign.id(),"ck-"+idx);
      assertThat(again.reservationId()).isEqualTo(p.reservationId());
     }catch(FeedFailure e){reject.incrementAndGet();}
    }catch(Exception e){reject.incrementAndGet();}
   }));
  }
  assertThat(ready.await(60,TimeUnit.SECONDS)).isTrue();
  go.countDown();
  for(var f:futures)f.get(60,TimeUnit.SECONDS);
  pool.shutdown();

  Integer sold=db.queryForObject("SELECT sold_count FROM material_sale_campaigns WHERE id=?",Integer.class,campaign.id());
  Integer orders=db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE campaign_id=? AND state='PAID'",Integer.class,campaign.id());
  assertThat(sold).isEqualTo(3);
  assertThat(orders).isEqualTo(3);
  assertThat(ok.get()).isEqualTo(3);
  Integer ents=db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE source_kind='PURCHASE' AND state='ACTIVE'",Integer.class);
  assertThat(ents).isEqualTo(3);
 }

 @Test void recoverSettleIdempotentAndBlacklistRejectReleasesStock(){
  long author=register("sale_rec_a","password-a1");
  long buyer=register("sale_rec_b","password-b1");
  long blocked=register("sale_rec_c","password-c1");
  var mat=materials.createDraft(author,"恢复素材","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  follows.change(buyer,author,true);follows.change(blocked,author,true);
  long now=System.currentTimeMillis();
  var campaign=sales.createCampaign(author,mat.postId(),100,2,now-1_000,now+3_600_000);
  var p=sales.purchase(buyer,campaign.id(),"recover-1");
  assertThat(p.state()).isEqualTo("SUCCEEDED");
  sales.settle(p.reservationId());
  sales.settle(p.reservationId());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE user_id=?",Integer.class,buyer)).isEqualTo(1);

  sales.block(author,blocked,"test");
  var rejected=sales.purchase(blocked,campaign.id(),"blocked-1");
  assertThat(rejected.state()).isEqualTo("REJECTED");
  assertThat(rejected.rejectReason()).isEqualTo("BLACKLISTED");
  Integer sold=db.queryForObject("SELECT sold_count FROM material_sale_campaigns WHERE id=?",Integer.class,campaign.id());
  assertThat(sold).isEqualTo(1);
 }

 private long register(String user,String pass){
  auth.register(user,pass);
  return db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,user);
 }
}
