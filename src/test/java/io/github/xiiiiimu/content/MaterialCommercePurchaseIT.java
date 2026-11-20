package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.SaleLatency;
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
  r.add("content.media.secret-key",()->"media-test-secret-key-not-for-production-01");
  r.add("content.media.ffmpeg-image",()->"");
  r.add("content.assistant.python-url",()->"");
  r.add("content.media.scan-delay-millis",()->"3600000");
  r.add("content.sale.sync-settle",()->"false");
  r.add("content.sale.latency-enabled",()->"true");
 }
 @Autowired JdbcTemplate db;@Autowired MaterialService materials;@Autowired PublishService publish;
 @Autowired SaleService sales;@Autowired AuthService auth;@Autowired GiveawayService giveaways;
 @Autowired RelationshipService follows;@Autowired InteractionService interactions;

 @BeforeEach void wipe(){
  db.update("DELETE FROM material_sale_consume_receipts");
  db.update("DELETE FROM material_refund_requests");
  db.update("DELETE FROM material_orders");
  db.update("DELETE FROM material_purchase_requests");
  db.update("DELETE FROM material_sale_eligibility_blocks");
  db.update("DELETE FROM material_sale_campaigns");
  db.update("DELETE FROM merchant_blacklist");
  db.update("DELETE FROM account_material_decision_locks");
  db.update("DELETE FROM material_entitlements");
 }

 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={3,100})
 void concurrentStockAndOneOrderPerAccount(int stock)throws Exception{
  long author=register("sale_author_"+stock,"password-a1");
  var mat=materials.createDraft(author,"限量夜景","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  long now=System.currentTimeMillis();
  var campaign=sales.createCampaign(author,mat.postId(),990,stock,now-1_000,now+3_600_000);
  assertThat(campaign.stockCap()).isEqualTo(stock);

  int buyers=stock+8;
  long[] users=new long[buyers];
  for(int i=0;i<buyers;i++){
   users[i]=register("sale_buyer_"+stock+"_"+i,"password-b"+i);
   follows.change(users[i],author,true);
  }
  int concurrency=Math.min(buyers,12);
  ExecutorService pool=Executors.newFixedThreadPool(concurrency);
  CountDownLatch ready=new CountDownLatch(concurrency),go=new CountDownLatch(1);
  AtomicInteger ok=new AtomicInteger(),reject=new AtomicInteger();
  List<Future<?>> futures=new ArrayList<>();
  for(int i=0;i<buyers;i++){
   int idx=i;
   futures.add(pool.submit(()->{
    try{
     ready.countDown();go.await(30,TimeUnit.SECONDS);
     try{
      var p=purchaseAndSettle(users[idx],campaign.id(),"ck-"+idx);
      if("SUCCEEDED".equals(p.state()))ok.incrementAndGet();
      else reject.incrementAndGet();
      var again=purchaseAndSettle(users[idx],campaign.id(),"ck-"+idx);
      assertThat(again.reservationId()).isEqualTo(p.reservationId());
      var otherKey=purchaseAndSettle(users[idx],campaign.id(),"retry-"+idx);
      assertThat(otherKey.reservationId()).isEqualTo(p.reservationId());
     }catch(FeedFailure e){reject.incrementAndGet();}
    }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
   }));
  }
  assertThat(ready.await(60,TimeUnit.SECONDS)).isTrue();
  go.countDown();
  for(var f:futures)f.get(60,TimeUnit.SECONDS);
  pool.shutdown();

  Integer sold=db.queryForObject("SELECT sold_count FROM material_sale_campaigns WHERE id=?",Integer.class,campaign.id());
  Integer orders=db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE campaign_id=? AND state='PAID'",Integer.class,campaign.id());
  assertThat(sold).isEqualTo(stock);
  assertThat(orders).isEqualTo(stock);
  assertThat(ok.get()).isEqualTo(stock);
  Integer ents=db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE source_kind='PURCHASE' AND state='ACTIVE'",Integer.class);
  assertThat(ents).isEqualTo(stock);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_consume_receipts WHERE decision='SUCCEEDED'",Integer.class)).isEqualTo(stock);
 }

 @Test void newCampaignSettlesAgainstTheVersionPinnedBeforeDefaultChanges(){
  long author=register("sale_frozen_a","password-a1");
  long buyer=register("sale_frozen_b","password-b1");
  var mat=materials.createDraft(author,"冻结素材","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  follows.change(buyer,author,true);
  long original=db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,mat.postId());
  long now=System.currentTimeMillis();
  var campaign=sales.createCampaign(author,mat.postId(),100,1,now-1000,now+3600000);
  materials.uploadVersion(author,mat.postId(),"other video".getBytes(StandardCharsets.UTF_8),"video/mp4");
  for(int i=0;i<8;i++)materials.tick();
  assertThat(db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,mat.postId())).isNotEqualTo(original);
  var purchase=purchaseAndSettle(buyer,campaign.id(),"frozen-version");
  assertThat(purchase.state()).isEqualTo("SUCCEEDED");
  assertThat(db.queryForObject("SELECT version_id FROM material_entitlements WHERE source_kind='PURCHASE' AND source_id=?",Long.class,purchase.orderId())).isEqualTo(original);
  assertThat(db.queryForObject("SELECT decision FROM material_sale_consume_receipts WHERE reservation_id=?",String.class,purchase.reservationId())).isEqualTo("SUCCEEDED");
 }

 @Test void rejectsOutsideSaleWindowAndCloseWinsAgainstPendingSettlement()throws Exception{
  long author=register("sale_window_a","password-a1");
  long buyer=register("sale_window_b","password-b1");
  var mat=materials.createDraft(author,"限时素材","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  follows.change(buyer,author,true);
  long now=System.currentTimeMillis();
  var future=sales.createCampaign(author,mat.postId(),100,1,now+60000,now+120000);
  assertThatThrownBy(()->purchaseAndSettle(buyer,future.id(),"before-start"))
   .isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("NOT_STARTED"));
  sales.closeCampaign(author,future.id());
  var past=sales.createCampaign(author,mat.postId(),100,1,now-60000,now-1000);
  assertThatThrownBy(()->purchaseAndSettle(buyer,past.id(),"after-end"))
   .isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("ENDED"));
  sales.closeCampaign(author,past.id());
  var active=sales.createCampaign(author,mat.postId(),100,1,now-1000,now+3600000);
  var pool=Executors.newSingleThreadExecutor();
  sales.faults().armHold();
  try{
   var buying=pool.submit(()->purchaseAndSettle(buyer,active.id(),"closed-before-settle"));
   org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(()->
    assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=? AND state='PROCESSING'",Integer.class,active.id())).isEqualTo(1));
   sales.closeCampaign(author,active.id());
   sales.faults().releaseHold();
   var result=buying.get(15,TimeUnit.SECONDS);
   assertThat(result.state()).isEqualTo("REJECTED");
   assertThat(result.rejectReason()).isEqualTo("CAMPAIGN_CLOSED");
   assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE campaign_id=?",Integer.class,active.id())).isZero();
   assertThat(db.queryForObject("SELECT decision FROM material_sale_consume_receipts WHERE reservation_id=?",String.class,result.reservationId())).isEqualTo("REJECTED:CAMPAIGN_CLOSED");
  }finally{sales.faults().releaseHold();pool.shutdownNow();}
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
  var p=purchaseAndSettle(buyer,campaign.id(),"recover-1");
  assertThat(p.state()).isEqualTo("SUCCEEDED");
  sales.settle(p.reservationId());
  sales.settle(p.reservationId());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE user_id=?",Integer.class,buyer)).isEqualTo(1);

  sales.block(author,blocked,"test");
  assertThatThrownBy(()->purchaseAndSettle(blocked,campaign.id(),"blocked-1"))
   .isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("BLACKLISTED"));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=? AND user_id=?",Integer.class,campaign.id(),blocked)).isZero();

  Integer sold=db.queryForObject("SELECT sold_count FROM material_sale_campaigns WHERE id=?",Integer.class,campaign.id());
  assertThat(sold).isEqualTo(1);
 }

 @Test void giveawayOwnerCannotBuyWhileOtherUsersCan()throws Exception{
  long author=register("sale_gw_a","password-a1");
  long winner=register("sale_gw_w","password-w1");
  long buyer=register("sale_gw_b","password-b1");
  var mat=materials.createDraft(author,"抽奖后开卖","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  follows.change(winner,author,true);follows.change(buyer,author,true);
  long version=db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,mat.postId());
  long now=System.currentTimeMillis();
  db.update("""
   INSERT INTO giveaways(source_comment_id,post_id,author_id,state,rule_tree_json,tool_versions_json,timezone,deadline_ms,
     winner_count,reward_version_id,reward_license,summary_text,created_at_ms,updated_at_ms)
   VALUES(?,?,?,'COMPLETED','{}','[]','Asia/Shanghai',?,?,?,?,'done',?,?)
   """, sourceComment(author,mat.postId()), mat.postId(), author, now-1000, 1, version, "DOWNLOAD", now, now);
  long giveawayId=db.queryForObject("SELECT id FROM giveaways WHERE post_id=? ORDER BY id DESC LIMIT 1",Long.class,mat.postId());
  db.update("INSERT INTO giveaway_winners(giveaway_id,account_id,draw_rank,entitlement_id,created_at_ms) VALUES(?,?,1,NULL,?)",
   giveawayId,winner,now);
  db.update("""
   INSERT INTO material_entitlements(account_id,post_id,version_id,license,source_kind,source_id,state,created_at_ms)
   VALUES(?,?,?,'DOWNLOAD','GIVEAWAY',?,'ACTIVE',?)
   """, winner, mat.postId(), version, giveawayId, now);
  var campaign=sales.createCampaign(author,mat.postId(),100,2,now-1000,now+3_600_000);
  assertThatThrownBy(()->purchaseAndSettle(winner,campaign.id(),"winner-ck"))
   .isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("ALREADY_OWNED"));
  var ok=purchaseAndSettle(buyer,campaign.id(),"buyer-ck");
  assertThat(ok.state()).isEqualTo("SUCCEEDED");
  assertThat(sales.hasOpenCampaign(mat.postId())).isTrue();
 }

 @Test void unfinishedGiveawayCanCoexistWithSale(){
  long author=register("sale_open_gw_a","password-a1");
  var mat=materials.createDraft(author,"未结束抽奖","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  long now=System.currentTimeMillis();
  db.update("""
   INSERT INTO giveaways(source_comment_id,post_id,author_id,state,rule_tree_json,tool_versions_json,timezone,deadline_ms,
     winner_count,reward_license,summary_text,created_at_ms,updated_at_ms)
   VALUES(?,?,?,'OPEN','{}','[]','Asia/Shanghai',?,1,'DOWNLOAD','open',?,?)
   """, sourceComment(author,mat.postId()), mat.postId(), author, now+60_000, now, now);
  assertThat(sales.createCampaign(author,mat.postId(),100,1,now-1000,now+3_600_000).state()).isEqualTo("OPEN");
 }

 @Test void blacklistAfterReserveRejectsAndReleasesStock()throws Exception{
  long author=register("sale_dyn_a","password-a1");
  long buyer=register("sale_dyn_b","password-b1");
  var mat=materials.createDraft(author,"动态黑名单","sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  follows.change(buyer,author,true);
  long now=System.currentTimeMillis();
  var campaign=sales.createCampaign(author,mat.postId(),100,1,now-1000,now+3_600_000);
  var pool=Executors.newSingleThreadExecutor();
  sales.faults().armHold();
  try{
   var buying=pool.submit(()->purchaseAndSettle(buyer,campaign.id(),"dyn-bl"));
   org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(()->
    assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=? AND state='PROCESSING'",Integer.class,campaign.id())).isEqualTo(1));
   sales.block(author,buyer,"after-reserve");
   sales.faults().releaseHold();
   var result=buying.get(15,TimeUnit.SECONDS);
   assertThat(result.state()).isEqualTo("REJECTED");
   assertThat(result.rejectReason()).isEqualTo("BLACKLISTED");
   assertThat(db.queryForObject("SELECT sold_count FROM material_sale_campaigns WHERE id=?",Integer.class,campaign.id())).isZero();
   assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE campaign_id=?",Integer.class,campaign.id())).isZero();
  }finally{sales.faults().releaseHold();pool.shutdownNow();}
 }

 @Test void diagnosticConnectionKeepsRealCommitAndRollback()throws Exception {
  java.nio.file.Path file=java.nio.file.Files.createTempFile(java.nio.file.Path.of("target"),"sale-jdbc-trace-",".jfr");
  try(var recording=new jdk.jfr.Recording()){
   recording.enable(SaleLatency.PhaseEvent.class);recording.start();
   try(var trace=SaleLatency.begin("jm-c1-u2","http",1,0);var connection=db.getDataSource().getConnection();var statement=connection.createStatement()){
    statement.execute("CREATE TEMPORARY TABLE sale_timing_check(id INT PRIMARY KEY)");
    connection.setAutoCommit(false);statement.executeUpdate("INSERT INTO sale_timing_check VALUES(1)");connection.commit();
    statement.executeUpdate("INSERT INTO sale_timing_check VALUES(2)");connection.rollback();
    try(var rows=statement.executeQuery("SELECT COUNT(*) FROM sale_timing_check")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
    statement.execute("DROP TEMPORARY TABLE sale_timing_check");
   }
   recording.stop();recording.dump(file);
  }
  var phases=jdk.jfr.consumer.RecordingFile.readAllEvents(file).stream().filter(e->e.getEventType().getName().equals("content.SalePhase")).map(e->e.getString("phase")).toList();
  assertThat(phases).contains("jdbc.acquire","jdbc.commit","jdbc.rollback");
 }
 private long sourceComment(long author,long post){return interactions.comment(author,post,"fixture",UUID.randomUUID().toString(),"drone",null,null,null).commentId();}
 SaleService.PurchaseView purchaseAndSettle(long user,long campaign,String key){
  var accepted=sales.purchase(user,campaign,key);
  sales.flushPending(200);
  org.awaitility.Awaitility.await()
   .ignoreExceptionsMatching(e->e instanceof FeedFailure f && "REQUEST_NOT_PERSISTED".equals(f.code))
   .atMost(java.time.Duration.ofSeconds(15)).untilAsserted(()->{
   sales.settle(accepted.reservationId());
   assertThat(sales.getRequest(user,accepted.reservationId()).state()).isNotEqualTo("PROCESSING");
  });
  return sales.getRequest(user,accepted.reservationId());
 }
 long register(String user,String pass){
  auth.register(user,pass);
  return db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,user);
 }
}
