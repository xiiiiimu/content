package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.SaleLatency;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import static org.assertj.core.api.Assertions.*;

/** Production asynchronous acceptance contract, using real MySQL/Redis and object storage. */
@SpringBootTest @org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CommerceIngressRevisionIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379)
  // Keep the container/network alive while SIGKILL restarts the Redis process.
  // Restarting the container itself reallocates Docker Desktop's ephemeral host port.
  .withCommand("sh","-c","while true; do redis-server --appendonly yes --maxmemory-policy noeviction; sleep 0.1; done");
 @Container static final GenericContainer<?> S3=new GenericContainer<>("adobe/s3mock:3.12.0").withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.media.enabled",()->"true");r.add("content.media.s3-endpoint",()->"http://"+S3.getHost()+":"+S3.getMappedPort(9090));
  r.add("content.media.bucket",()->"content-media");r.add("content.media.secret-key",()->"content-secret-key-32-bytes-minimum");
  r.add("content.media.ffmpeg-image",()->"");r.add("content.assistant.python-url",()->"");
  r.add("content.media.scan-delay-millis",()->"3600000");r.add("content.sale.sync-settle",()->"false");
  r.add("content.sale.scan-enabled",()->"false");r.add("content.sale.latency-enabled",()->"true");
 }
 @Autowired SaleService sales;@Autowired MaterialService materials;@Autowired PublishService publish;
 @Autowired AuthService auth;@Autowired RelationshipService follows;@Autowired JdbcTemplate db;
 @Autowired GiveawayService giveaways;@Autowired InteractionService interactions;
 @Autowired org.springframework.test.web.servlet.MockMvc http;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 @Autowired SaleIngress ingress;
 @Autowired io.github.xiiiiimu.content.infrastructure.SaleRedis redis;
 @Autowired io.github.xiiiiimu.content.infrastructure.SaleIngressMapper journal;
 @Autowired io.github.xiiiiimu.content.infrastructure.SaleBridgeMapper batches;
 @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
 @BeforeEach void resetIsolatedContainers(){
  sales.faults().resetFailures();sales.faults().releaseHold();
  for(String table:List.of("material_sale_stream_quarantine","material_sale_reservation_fences")){
   if(db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,table)>0)db.update("DELETE FROM "+table);
  }
  for(String table:List.of("material_sale_legacy_replays","material_sale_cache_jobs","material_sale_legacy_streams","material_sale_consume_receipts","material_refund_requests","material_sale_dispatch","giveaway_winners","giveaway_candidates","material_entitlements","material_orders","material_purchase_requests","material_sale_eligibility_blocks","material_sale_campaigns","merchant_blacklist","account_material_decision_locks","giveaways","notifications"))db.update("DELETE FROM "+table);
  db.update("UPDATE material_sale_runtime SET state='RECOVERING',owner=NULL,lease_until_ms=0,redis_run_id=NULL WHERE id=1");
  // This is the disposable Testcontainers Redis, never the application's business Redis.
  redis.entry().commands().flushdb();
 }
 record Fixture(long author,long buyer,long post,SaleService.CampaignView campaign){}
 Fixture fixture(boolean follow,String mode){
  String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
  long author=auth.register("jma"+suffix,"test-author-password").userId();
  long buyer=auth.register("jmb"+suffix,"test-buyer-password").userId();
  var material=materials.createDraft(author,"权限测试","sale",List.of("test"),mode,null,true,true,
   new byte[]{1,2,3},"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,material.postId(),UUID.randomUUID().toString(),0);
  if(follow)follows.change(buyer,author,true);
  long now=System.currentTimeMillis();
  return new Fixture(author,buyer,material.postId(),sales.createCampaign(author,material.postId(),100,2,now-1000,now+3600000));
 }
 @Test void loggedInNonFollowerWithRestrictedPreviewCanBuyWithoutEntrySql()throws Exception{
  var f=fixture(false,"AUTHOR_ONLY");
  Path recording=Files.createTempFile(Path.of("target"),"zero-sql-entry-",".jfr");
  try(var events=new Recording()){
   events.enable(SaleLatency.PhaseEvent.class);events.start();
   try(var scope=SaleLatency.begin("jm-c"+f.campaign.id()+"-u"+f.buyer,"http",f.campaign.id(),0)){
    var result=sales.purchase(f.buyer,f.campaign.id(),"non-follower");
    assertThat(result.state()).isEqualTo("PROCESSING");
   }
   events.stop();events.dump(recording);
  }
  assertThat(RecordingFile.readAllEvents(recording).stream().filter(e->e.getEventType().getName().equals("content.SalePhase"))
   .map(e->e.getString("phase")).filter(p->p.startsWith("sql.")).toList()).isEmpty();
 }
 @Test void reservationIsOnlyInRedisUntilRealStreamIsDrained(){
  var f=fixture(true,"PUBLIC_PREVIEW");var result=sales.purchase(f.buyer,f.campaign.id(),"stream-first");
  assertThat(result.state()).isEqualTo("PROCESSING");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,result.reservationId())).isZero();
  assertThatThrownBy(()->sales.settle(result.reservationId())).isInstanceOfSatisfying(FeedFailure.class,
   e->{assertThat(e.status).isEqualTo(503);assertThat(e.code).isEqualTo("REQUEST_NOT_PERSISTED");});
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_consume_receipts",Integer.class)).isZero();
  assertThat(sales.getRequest(f.buyer,result.reservationId()).state()).isEqualTo("PROCESSING");
  sales.flushPending(200);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,result.reservationId())).isEqualTo(1);
  sales.settle(result.reservationId());
  assertThat(sales.getRequest(f.buyer,result.reservationId()).state()).isEqualTo("SUCCEEDED");
 }
 @Test void purchaseSettlementAndRealDrawSerializeOnTheSameAccountMaterial()throws Exception{
  var f=fixture(true,"PUBLIC_PREVIEW");
  interactions.like(f.buyer,f.post,"drone");
  var comment=interactions.comment(f.author,f.post,"并发开奖","race-"+f.post,"drone",null,null,null);
  long deadline=System.currentTimeMillis()+1000;
  var command=new AssistantParser.GiveawayCommand("{\"op\":\"ALL\",\"children\":[{\"type\":\"LIKE\"}]}","Asia/Shanghai",null,deadline,1,"DEFAULT","DOWNLOAD","race");
  giveaways.createFromAssistant(f.author,f.post,comment.commentId(),command);
  long giveaway=db.queryForObject("SELECT id FROM giveaways WHERE source_comment_id=?",Long.class,comment.commentId());
  var r=sales.purchase(f.buyer,f.campaign.id(),"draw-race");ingress.drain(10);
  while(System.currentTimeMillis()<=deadline)Thread.sleep(20);
  giveaways.seal(giveaway);
  var barrier=new java.util.concurrent.CyclicBarrier(2);
  var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{
   var settle=workers.submit(()->{barrier.await();sales.settle(r.reservationId());return true;});
   var draw=workers.submit(()->{barrier.await();giveaways.draw(giveaway);return true;});
   settle.get(20,java.util.concurrent.TimeUnit.SECONDS);draw.get(20,java.util.concurrent.TimeUnit.SECONDS);
  }finally{workers.shutdownNow();}
  var result=sales.getRequest(f.buyer,r.reservationId());
  assertThat(result.state()).isIn("SUCCEEDED","REJECTED");
  if(result.state().equals("REJECTED"))assertThat(result.rejectReason()).isEqualTo("ALREADY_OWNED");
  int paid=db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE post_id=? AND state='PAID'",Integer.class,f.post);
  assertThat(paid).isEqualTo(result.state().equals("SUCCEEDED")?1:0);
  assertThat(db.queryForObject("SELECT sold_count FROM material_sale_campaigns WHERE id=?",Integer.class,f.campaign.id())).isEqualTo(paid);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE account_id=? AND post_id=? AND source_kind='GIVEAWAY' AND state='ACTIVE'",Integer.class,f.buyer,f.post)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_refund_requests",Integer.class)).isZero();
  assertThatThrownBy(()->sales.purchase(f.buyer,f.campaign.id(),"after-draw")).isInstanceOf(FeedFailure.class).hasMessageContaining("ALREADY_OWNED");
 }
 @Test void activePurchaseDownloadsAfterUnfollowEvenWhenShareModeIsAuthorOnly(){
  var f=fixture(true,"AUTHOR_ONLY");var result=sales.purchase(f.buyer,f.campaign.id(),"paid-download");
  sales.flushPending(200);sales.settle(result.reservationId());
  assertThat(sales.getRequest(f.buyer,result.reservationId()).state()).isEqualTo("SUCCEEDED");
  long bought=db.queryForObject("SELECT version_id FROM material_entitlements WHERE source_kind='PURCHASE' AND source_id=?",Long.class,sales.getRequest(f.buyer,result.reservationId()).orderId());
  materials.uploadVersion(f.author,f.post,new byte[]{4,5,6},"video/mp4");for(int i=0;i<8;i++)materials.tick();
  long newer=db.queryForObject("SELECT default_version_id FROM material_profiles WHERE post_id=?",Long.class,f.post);
  assertThat(newer).isNotEqualTo(bought);
  follows.change(f.buyer,f.author,false);
  String raw=db.queryForObject("SELECT raw_object_key FROM material_versions WHERE id=?",String.class,bought);
  assertThat(java.net.URLDecoder.decode(materials.signOriginal(f.buyer,f.post,null,60000,null).url(),java.nio.charset.StandardCharsets.UTF_8)).contains(raw);
  assertThatThrownBy(()->materials.signOriginal(f.buyer,f.post,newer,60000,null)).isInstanceOf(FeedFailure.class);
  follows.offline(f.author,f.post,db.queryForObject("SELECT version FROM posts WHERE id=?",Long.class,f.post));
  assertThatThrownBy(()->materials.signOriginal(f.buyer,f.post,null,60000,null)).isInstanceOf(FeedFailure.class);
 }
 @Test void ordinaryApprovedRefundRemovesPurchaseEligibilityAndCannotBecomeWinningRefund(){
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"ordinary-refund");
  ingress.drain(10);sales.settle(r.reservationId());long order=sales.getRequest(f.buyer,r.reservationId()).orderId();
  assertThatThrownBy(()->sales.requestGiveawayRefund(f.buyer,order)).isInstanceOf(FeedFailure.class);
  long refund=sales.requestRefund(f.buyer,order);sales.approveRefund(f.author,refund);sales.approveRefund(f.author,refund);
  assertThat(db.queryForObject("SELECT refund_reason FROM material_orders WHERE id=?",String.class,order)).isEqualTo("BUYER_REQUESTED");
  assertThat(sales.purchaseEligibleAccounts(f.author,f.post,null,null)).doesNotContain(f.buyer);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE source_kind='PURCHASE' AND state='ACTIVE' AND post_id=?",Integer.class,f.post)).isZero();
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);
 }
 @Test void opaqueClientKeysHaveIdenticalExactIdentityInRedisAndMySql(){
  var f=fixture(false,"PUBLIC_PREVIEW");
  var one=sales.purchase(f.buyer,f.campaign.id(),"Opaque-Key");ingress.drain(10);sales.settle(one.reservationId());
  var other=fixture(false,"AUTHOR_ONLY");
  var two=sales.purchase(f.buyer,other.campaign.id(),"opaque-key");ingress.drain(10);sales.settle(two.reservationId());
  var third=fixture(false,"PUBLIC_PREVIEW");
  var three=sales.purchase(f.buyer,third.campaign.id(),"Opaque-Key ");ingress.drain(10);sales.settle(three.reservationId());
  assertThat(Set.of(one.reservationId(),two.reservationId(),three.reservationId())).hasSize(3);
  for(var r:List.of(one,two,three))assertThat(sales.getRequest(f.buyer,r.reservationId()).state()).isEqualTo("SUCCEEDED");
 }
 @Test void legacyCrossCampaignClientReplayIsAuditedWithoutCreatingAnotherRequest(){
  var original=fixture(false,"PUBLIC_PREVIEW");var first=sales.purchase(original.buyer,original.campaign.id(),"legacy-shared-key");
  ingress.drain(10);sales.settle(first.reservationId());
  var other=fixture(false,"AUTHOR_ONLY");String orphan=UUID.randomUUID().toString().replace("-","");
  String base="sale:{"+other.campaign.id()+"}:";
  redis.entry().commands().hset(base+"r:"+orphan,Map.of("state","PROCESSING","user",Long.toString(original.buyer)));
  redis.entry().commands().xadd(base+"pending",Map.of("reservationId",orphan,"userId",Long.toString(original.buyer),"clientKey","legacy-shared-key"));
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.CONTROL,"repair","1");
  ingress.recover();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders",Integer.class)).isEqualTo(1);
  assertThat(sales.purchase(original.buyer,other.campaign.id(),"legacy-shared-key").reservationId()).isEqualTo(first.reservationId());
  assertThat(redis.remainingStock(other.campaign.id())).isEqualTo(2);
  assertThat(db.queryForObject("SELECT canonical_reservation_id FROM material_sale_legacy_replays WHERE legacy_reservation_id=?",String.class,orphan)).isEqualTo(first.reservationId());
  assertThat(sales.getRequest(original.buyer,orphan).reservationId()).isEqualTo(first.reservationId());
  assertThatThrownBy(()->sales.getRequest(other.buyer,orphan)).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.status).isEqualTo(403));
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.CONTROL,"repair","1");ingress.recover();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_legacy_replays",Integer.class)).isEqualTo(1);
  assertThat(redis.entry().commands().xlen(base+"pending")).isEqualTo(1);
 }
 @Test void brokerAckConfirmationDoesNotWaitForTheConsumerRequestRowLock()throws Exception{
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"ack-with-consumer-lock");ingress.drain(10);
  var claim=ingress.claim();assertThat(claim).isNotNull();
  var locked=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
  var consumer=workers.submit(()->transactions.executeWithoutResult(status->{
   db.queryForObject("SELECT reservation_id FROM material_purchase_requests WHERE reservation_id=? FOR UPDATE",String.class,r.reservationId());locked.countDown();
   try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test lock was not released");}
   catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
  }));
  assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
  var sender=workers.submit(()->ingress.sent(claim));
  try{
   sender.get(2,java.util.concurrent.TimeUnit.SECONDS);
   assertThat(journal.dispatch(r.reservationId()).state()).isEqualTo("SENT");
   assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders",Integer.class)).isZero();
  }finally{release.countDown();consumer.get(5,java.util.concurrent.TimeUnit.SECONDS);sender.get(5,java.util.concurrent.TimeUnit.SECONDS);workers.shutdownNow();}
  sales.settle(r.reservationId());assertThat(sales.getRequest(f.buyer,r.reservationId()).state()).isEqualTo("SUCCEEDED");
 }
 @Test void winningKeepsPaidOrderAndRequiresIdempotentUserRequestedRefund()throws Exception{
  var f=fixture(true,"PUBLIC_PREVIEW");var p=sales.purchase(f.buyer,f.campaign.id(),"winner-refund");
  sales.flushPending(200);sales.settle(p.reservationId());long order=sales.getRequest(f.buyer,p.reservationId()).orderId();
  sales.closeCampaign(f.author,f.campaign.id());
  interactions.like(f.buyer,f.post,"drone");
  var comment=interactions.comment(f.author,f.post,"开奖测试","draw-"+f.post,"drone",null,null,null);
  long deadline=System.currentTimeMillis()+1000;
  var command=new AssistantParser.GiveawayCommand("{\"op\":\"ALL\",\"children\":[{\"type\":\"LIKE\"}]}","Asia/Shanghai",null,deadline,1,"DEFAULT","DOWNLOAD","test");
  giveaways.createFromAssistant(f.author,f.post,comment.commentId(),command);
  long giveaway=db.queryForObject("SELECT id FROM giveaways WHERE source_comment_id=?",Long.class,comment.commentId());
  while(System.currentTimeMillis()<=deadline)Thread.sleep(20);
  giveaways.seal(giveaway);giveaways.draw(giveaway);giveaways.draw(giveaway);
  assertThat(db.queryForObject("SELECT state FROM material_orders WHERE id=?",String.class,order)).isEqualTo("PAID");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_refund_requests WHERE order_id=?",Integer.class,order)).isZero();
  String path="/api/orders/"+order+"/giveaway-refund-requests";
  var first=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).principal(()->Long.toString(f.buyer)))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
  long request=json.readTree(first.getResponse().getContentAsString()).path("refundRequestId").asLong();
  var repeated=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).principal(()->Long.toString(f.buyer)))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
  assertThat(json.readTree(repeated.getResponse().getContentAsString()).path("refundRequestId").asLong()).isEqualTo(request);
  sales.approveRefund(f.author,request);sales.approveRefund(f.author,request);
  assertThat(db.queryForObject("SELECT refund_reason FROM material_orders WHERE id=?",String.class,order)).isEqualTo("GIVEAWAY_REFUND");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE post_id=? AND account_id=? AND source_kind='GIVEAWAY' AND state='ACTIVE'",Integer.class,f.post,f.buyer)).isEqualTo(1);
  assertThat(sales.purchaseEligibleAccounts(f.author,f.post,null,null)).contains(f.buyer);
 }
 @Test void rollbackBeforeBridgeCommitLeavesPendingAndReplayPersistsExactlyOnce(){
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"bridge-rollback");
  sales.faults().armFailure("stream.beforeCommit");
  assertThatThrownBy(()->ingress.drain("crashed",1,0)).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch",Integer.class)).isZero();
  ingress.drain("takeover",10,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch",Integer.class)).isEqualTo(1);
  sales.settle(r.reservationId());sales.settle(r.reservationId());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders",Integer.class)).isEqualTo(1);
 }
 @Test void bridgeBatchCommitsTwoReadyMessagesInOneTransaction()throws Exception{
  var f=fixture(false,"PUBLIC_PREVIEW");
  long second=auth.register("jmb"+UUID.randomUUID().toString().replace("-","").substring(0,12),"test-buyer-password").userId();
  sales.purchase(f.buyer,f.campaign.id(),"batch-first");
  sales.purchase(second,f.campaign.id(),"batch-second");
  Path recording=Files.createTempFile(Path.of("target"),"bridge-batch-commits-",".jfr");
  try(var events=new Recording()){
   events.enable(SaleLatency.PhaseEvent.class);events.start();
   ingress.drain("batch-commit-contract",2,0);
   events.stop();events.dump(recording);
  }
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(2);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch",Integer.class)).isEqualTo(2);
  long commits=RecordingFile.readAllEvents(recording).stream()
   .filter(e->e.getEventType().getName().equals("content.SalePhase"))
   .filter(e->e.getString("kind").startsWith("persist")&&e.getString("phase").equals("jdbc.commit")).count();
  assertThat(commits).as("a ready two-message batch uses one real JDBC commit, not per-message commits").isEqualTo(1);
 }
 @Test void malformedStreamMessageIsAuditedBeforeAckWithoutBlockingValidBatch(){
  var f=fixture(false,"PUBLIC_PREVIEW");
  String id=redis.entry().commands().xadd(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,
   Map.of("request","not-json","generation",Long.toString(journal.runtime().generation())));
  var good=sales.purchase(f.buyer,f.campaign.id(),"after-malformed");
  assertThatCode(()->ingress.drain("bad-and-good",20,0)).doesNotThrowAnyException();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,good.reservationId())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_stream_quarantine WHERE stream_id=? AND disposition='HOLD'",Integer.class,id)).isEqualTo(1);
  assertThat(redis.entry().commands().xpending(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.GROUP).getCount()).isZero();
  assertThatThrownBy(()->sales.purchase(auth.register("jmb"+UUID.randomUUID().toString().replace("-","").substring(0,12),"test-buyer-password").userId(),f.campaign.id(),"paused-unknown"))
   .isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.status).isEqualTo(503));
 }
 @Test void trustedOrphanConflictIsAuditedAndReleasedExactlyOnceAfterAuditCommit(){
  var f=fixture(false,"PUBLIC_PREVIEW");var orphan=sales.purchase(f.buyer,f.campaign.id(),"orphan-key");
  var other=fixture(false,"PUBLIC_PREVIEW");
  String canonical="a".repeat(32);long at=System.currentTimeMillis();
  db.update("INSERT INTO material_purchase_requests(reservation_id,campaign_id,post_id,merchant_id,user_id,client_key,price_cents,state,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,100,'PROCESSING',?,?)",
   canonical,other.campaign.id(),other.post,other.author,f.buyer,"orphan-key",at,at);
  sales.faults().armFailure("quarantine.afterRelease");
  assertThatThrownBy(()->ingress.drain("release-crash",20,0)).isInstanceOf(IllegalStateException.class);
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);
  ingress.drain("release-retry",20,0);
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_stream_quarantine WHERE canonical_reservation_id=? AND disposition='RELEASED'",Integer.class,canonical)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,orphan.reservationId())).isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch WHERE reservation_id=?",Integer.class,orphan.reservationId())).isZero();
  assertThat(db.queryForObject("SELECT state FROM material_sale_reservation_fences WHERE reservation_id=?",String.class,orphan.reservationId())).isEqualTo("BLOCKED");
 }
 @Test void quarantineAuditFailureKeepsBadPendingButValidMessagesCommit(){
  var f=fixture(false,"PUBLIC_PREVIEW");
  redis.entry().commands().xadd(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,Map.of("request","not-json","generation",Long.toString(journal.runtime().generation())));
  var good=sales.purchase(f.buyer,f.campaign.id(),"audit-fail-good");sales.faults().armFailure("quarantine.beforeCommit");
  assertThatThrownBy(()->ingress.drain("audit-fail",20,0)).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,good.reservationId())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_stream_quarantine",Integer.class)).isZero();
  assertThat(redis.entry().commands().xpending(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.GROUP).getCount()).isEqualTo(1);
  ingress.drain("audit-retry",20,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_stream_quarantine",Integer.class)).isEqualTo(1);
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={1,20,57})
 void bridgeReadsOnlyConfiguredBatchAndDoesNotWaitForTheTail(int size){
  var f=fixture(false,"PUBLIC_PREVIEW");sales.closeCampaign(f.author,f.campaign.id());long at=System.currentTimeMillis();
  var campaign=sales.createCampaign(f.author,f.post,100,size+1,at-1000,at+3600000);
  for(int i=0;i<size+1;i++){
   long buyer=auth.register("jmb"+UUID.randomUUID().toString().replace("-","").substring(0,12),"test-buyer-password").userId();
   sales.purchase(buyer,campaign.id(),"bounded-"+i);
  }
  var bridge=new SaleBridgeBatch(batches,journal,dbMapper(),redis.entry(),transactions,sales.faults(),size);
  bridge.drain("configured-"+size,200,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(size);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch",Integer.class)).isEqualTo(size);
  bridge.drain("tail-"+size,200,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(size+1);
 }
 @Autowired io.github.xiiiiimu.content.infrastructure.SaleMapper saleMapper;
 private io.github.xiiiiimu.content.infrastructure.SaleMapper dbMapper(){return saleMapper;}
 @Test void duplicateMessagesWithinOneBatchAreNotDuplicateRequestsOrOrders(){
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"same-event");
  var original=redis.entry().commands().xrange(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,io.lettuce.core.Range.unbounded()).get(0);
  redis.entry().commands().xadd(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,original.getBody());
  ingress.drain("duplicate-batch",20,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch",Integer.class)).isEqualTo(1);
  sales.settle(r.reservationId());sales.settle(r.reservationId());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders",Integer.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE source_kind='PURCHASE'",Integer.class)).isEqualTo(1);
 }
 @Test void wholeBatchRollbackAndPendingTakeoverAreAtomic(){
  var f=fixture(false,"PUBLIC_PREVIEW");long other=auth.register("jmb"+UUID.randomUUID().toString().replace("-","").substring(0,12),"test-buyer-password").userId();
  var one=sales.purchase(f.buyer,f.campaign.id(),"rollback-one");var two=sales.purchase(other,f.campaign.id(),"rollback-two");
  sales.faults().armFailure("stream.beforeCommit");
  assertThatThrownBy(()->ingress.drain("batch-crashed",20,0)).isInstanceOf(IllegalStateException.class);
  for(String table:List.of("material_purchase_requests","material_sale_dispatch","material_sale_reservation_fences"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
  assertThat(redis.entry().read("not-expired",20,30000)).isEmpty();
  ingress.drain("abandoned-takeover",20,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(2);
  sales.settle(one.reservationId());sales.settle(two.reservationId());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE state='PAID'",Integer.class)).isEqualTo(2);
  assertThat(redis.remainingStock(f.campaign.id())).isZero();
 }
 @Test void auditCommitBeforeAckCrashAndDuplicateAuditStayIdempotent(){
  var f=fixture(false,"PUBLIC_PREVIEW");String id=redis.entry().commands().xadd(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,Map.of("request","{invalid","generation",Long.toString(journal.runtime().generation())));
  sales.faults().armFailure("quarantine.afterCommit");
  assertThatThrownBy(()->ingress.drain("audit-committed",20,0)).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_stream_quarantine WHERE stream_id=?",Integer.class,id)).isEqualTo(1);
  ingress.drain("ack-recovery",20,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_stream_quarantine",Integer.class)).isEqualTo(1);
  assertThat(redis.entry().commands().xpending(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.GROUP).getCount()).isZero();
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);
 }
 @Test void conflictPayloadPointingAtPaidOrderNeverReleasesOrChangesThatOrder(){
  var f=fixture(false,"PUBLIC_PREVIEW");var accepted=sales.purchase(f.buyer,f.campaign.id(),"real-paid");ingress.drain(20);sales.settle(accepted.reservationId());
  var current=redis.entry().request(accepted.reservationId());
  var forged=new io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.Request(current.reservationId(),current.campaignId(),current.postId(),current.merchantId(),current.userId(),"forged-client",current.priceCents(),"PROCESSING",null,null,current.createdAtMs());
  String id=redis.entry().commands().xadd(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,Map.of("request",redis.entry().encode(forged),"generation",Long.toString(journal.runtime().generation())));
  ingress.drain("paid-collision",20,0);
  assertThat(db.queryForObject("SELECT disposition FROM material_sale_stream_quarantine WHERE stream_id=?",String.class,id)).isEqualTo("HOLD");
  assertThat(db.queryForObject("SELECT canonical_reservation_id FROM material_sale_stream_quarantine WHERE stream_id=?",String.class,id)).isEqualTo(accepted.reservationId());
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE state='PAID'",Integer.class)).isEqualTo(1);
  assertThat(sales.getRequest(f.buyer,accepted.reservationId()).state()).isEqualTo("SUCCEEDED");
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.CONTROL,"repair","1");ingress.recover();
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(1);
  assertThat(sales.getRequest(f.buyer,accepted.reservationId()).state()).isEqualTo("SUCCEEDED");
  assertThat(redis.entry().commands().hget(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.CONTROL,"quarantine_pause")).isEqualTo("1");
 }
 @Test void staleGenerationCannotCommitAndRecoveryPersistsTheOriginalRequest(){
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"stale-batch");long generation=journal.runtime().generation()+1;
  db.update("UPDATE material_sale_runtime SET generation=? WHERE id=1",generation);redis.entry().fence(generation);
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STATE,"generation",Long.toString(generation));redis.entry().publish(generation,redis.entry().runId());
  assertThatThrownBy(()->ingress.drain("stale-owner",20,0)).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("STALE_INGRESS_GENERATION"));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_reservation_fences",Integer.class)).isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_stream_quarantine",Integer.class)).isZero();
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.CONTROL,"repair","1");ingress.recover();
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,r.reservationId())).isEqualTo(1);
  sales.settle(r.reservationId());assertThat(sales.getRequest(f.buyer,r.reservationId()).state()).isEqualTo("SUCCEEDED");
 }
 @Test void conflictingIdentityInsideBatchIsAuditedRatherThanIdempotentSuccess(){
  var f=fixture(false,"PUBLIC_PREVIEW");var first=sales.purchase(f.buyer,f.campaign.id(),"in-batch-key");var original=redis.entry().request(first.reservationId());String conflict="b".repeat(32);
  var different=new io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.Request(conflict,original.campaignId(),original.postId(),original.merchantId(),original.userId(),original.clientKey(),original.priceCents(),"PROCESSING",null,null,original.createdAtMs());
  String id=redis.entry().commands().xadd(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,Map.of("request",redis.entry().encode(different),"generation",Long.toString(journal.runtime().generation())));
  ingress.drain("batch-identity-conflict",20,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(1);
  assertThat(db.queryForObject("SELECT canonical_reservation_id FROM material_sale_stream_quarantine WHERE stream_id=? AND reason='CLIENT_KEY_CONFLICT'",String.class,id)).isEqualTo(first.reservationId());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch WHERE reservation_id=?",Integer.class,conflict)).isZero();
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(1); // No real reservation existed for the forged ID.
 }
 @Test void executedAckWithLostResponseCanReplayAuditWithoutDuplicatingIt(){
  var f=fixture(false,"PUBLIC_PREVIEW");redis.entry().commands().xadd(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,Map.of("request","bad-json","generation",Long.toString(journal.runtime().generation())));
  sales.faults().armFailure("quarantine.afterAck");
  assertThatThrownBy(()->ingress.drain("ack-response-lost",20,0)).isInstanceOf(IllegalStateException.class);
  assertThat(redis.entry().commands().xpending(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.GROUP).getCount()).isZero();
  // Controlled replay in this disposable Redis after the real XACK executed.
  redis.entry().commands().xgroupSetid(io.lettuce.core.XReadArgs.StreamOffset.from(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,"0-0"),io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.GROUP);
  ingress.drain("ack-response-replay",20,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_stream_quarantine",Integer.class)).isEqualTo(1);
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);
 }
 @Test void recoveryAfterAuditCommitReconcilesReleaseAndStillFencesLateOrder(){
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"recover-orphan");var other=fixture(false,"PUBLIC_PREVIEW");
  String canonical="c".repeat(32);long at=System.currentTimeMillis();
  db.update("INSERT INTO material_purchase_requests(reservation_id,campaign_id,post_id,merchant_id,user_id,client_key,price_cents,state,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,100,'PROCESSING',?,?)",canonical,other.campaign.id(),other.post,other.author,f.buyer,"recover-orphan",at,at);
  sales.faults().armFailure("quarantine.afterCommit");
  assertThatThrownBy(()->ingress.drain("audit-before-recovery",20,0)).isInstanceOf(IllegalStateException.class);
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(1);
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.CONTROL,"repair","1");ingress.recover();
  assertThat(db.queryForObject("SELECT disposition FROM material_sale_stream_quarantine WHERE reservation_id=?",String.class,r.reservationId())).isEqualTo("RELEASED");
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);
  assertThat(redis.entry().request(r.reservationId()).state()).isEqualTo("REJECTED");
  assertThatThrownBy(()->sales.settle(r.reservationId())).isInstanceOf(FeedFailure.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE reservation_id=?",Integer.class,r.reservationId())).isZero();
  ingress.recover();assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);
 }
 @Test void mixedGenerationsCommitCurrentMessagesAndLeaveOldPendingForRecovery(){
  var f=fixture(false,"PUBLIC_PREVIEW");var old=sales.purchase(f.buyer,f.campaign.id(),"mixed-old");long generation=journal.runtime().generation()+1;
  db.update("UPDATE material_sale_runtime SET generation=? WHERE id=1",generation);redis.entry().fence(generation);
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STATE,"generation",Long.toString(generation));redis.entry().publish(generation,redis.entry().runId());
  long buyer=auth.register("jmb"+UUID.randomUUID().toString().replace("-","").substring(0,12),"test-buyer-password").userId();var current=sales.purchase(buyer,f.campaign.id(),"mixed-current");
  ingress.drain("mixed-generations",20,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,current.reservationId())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,old.reservationId())).isZero();
  assertThat(redis.entry().commands().xpending(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.GROUP).getCount()).isEqualTo(1);
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.CONTROL,"repair","1");ingress.recover();
  sales.settle(old.reservationId());sales.settle(current.reservationId());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE state='PAID'",Integer.class)).isEqualTo(2);
  assertThat(redis.remainingStock(f.campaign.id())).isZero();
 }
 @Test void incompleteRedisReleaseProofHoldsInsteadOfCreditingAgain()throws Exception{
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"partial-release");var other=fixture(false,"PUBLIC_PREVIEW");long at=System.currentTimeMillis();
  db.update("INSERT INTO material_purchase_requests(reservation_id,campaign_id,post_id,merchant_id,user_id,client_key,price_cents,state,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,100,'PROCESSING',?,?)","d".repeat(32),other.campaign.id(),other.post,other.author,f.buyer,"partial-release",at,at);
  sales.faults().armFailure("quarantine.afterCommit");assertThatThrownBy(()->ingress.drain("partial-before-apply",20,0)).isInstanceOf(IllegalStateException.class);
  // Simulate an interrupted Redis script with an unconfirmed release marker.
  String key=io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STATE;
  var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(redis.entry().commands().hget(key,"r:"+r.reservationId()));
  node.put("state","REJECTED");node.put("rejectReason","INGRESS_QUARANTINED");
  redis.entry().commands().hset(key,Map.of("r:"+r.reservationId(),node.toString(),"released:"+r.reservationId(),"APPLYING"));
  ingress.drain("partial-release-retry",20,0);
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT disposition FROM material_sale_stream_quarantine WHERE reservation_id=?",String.class,r.reservationId())).isEqualTo("HOLD");
  assertThat(sales.getRequest(f.buyer,r.reservationId()).state()).isEqualTo("PROCESSING");
  assertThatThrownBy(()->sales.purchase(f.buyer,other.campaign.id(),"partial-release")).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.status).isEqualTo(503));
 }
 @Test void bridgeBatchCommitBeforeAckCrashKeepsTheWholeBatchDurable(){
  var f=fixture(false,"PUBLIC_PREVIEW");
  long second=auth.register("jmb"+UUID.randomUUID().toString().replace("-","").substring(0,12),"test-buyer-password").userId();
  sales.purchase(f.buyer,f.campaign.id(),"batch-ack-first");
  sales.purchase(second,f.campaign.id(),"batch-ack-second");
  sales.faults().armFailure("stream.afterCommit");
  assertThatThrownBy(()->ingress.drain("batch-ack-crash",2,0)).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class))
   .as("both requests commit atomically before any stream acknowledgement").isEqualTo(2);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch",Integer.class)).isEqualTo(2);
  assertThat(redis.entry().commands().xpending(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,
   io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.GROUP).getCount()).isEqualTo(2);
 }
 @Test void commitBeforeStreamAckAndAbandonedConsumerAreRecoverable(){
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"lost-stream-ack");
  var claimed=redis.entry().read("departed",1,30000);assertThat(claimed).hasSize(1);
  assertThat(redis.entry().read("other",1,30000)).isEmpty();
  sales.faults().armFailure("stream.afterCommit");
  assertThatThrownBy(()->ingress.drain("crashed-after-commit",1,0)).isInstanceOf(IllegalStateException.class);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests",Integer.class)).isEqualTo(1);
  ingress.drain("replayed",10,0);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch",Integer.class)).isEqualTo(1);
  assertThat(redis.entry().commands().xpending(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.GROUP).getCount()).isZero();
  assertThat(sales.getRequest(f.buyer,r.reservationId()).reservationId()).isEqualTo(r.reservationId());
 }
 @Test void failedSettlementRollsBackEveryRowAndCrossCampaignReplayDoesNotReserveAgain(){
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"settle-rollback");ingress.drain(10);
  sales.faults().armFailure("settle.beforeCommit");
  assertThatThrownBy(()->sales.settle(r.reservationId())).isInstanceOf(IllegalStateException.class);
  for(String table:List.of("material_orders","material_entitlements","material_sale_consume_receipts"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
  assertThat(db.queryForObject("SELECT sold_count FROM material_sale_campaigns WHERE id=?",Integer.class,f.campaign.id())).isZero();
  sales.settle(r.reservationId());sales.closeCampaign(f.author,f.campaign.id());
  long now=System.currentTimeMillis();var next=sales.createCampaign(f.author,f.post,100,2,now-1000,now+3600000);
  assertThat(sales.purchase(f.buyer,next.id(),"other-campaign").reservationId()).isEqualTo(r.reservationId());
  assertThat(redis.remainingStock(next.id())).isEqualTo(2);
 }
 @Test void staleDispatchTokenCannotCompleteOrRetryAfterTakeover(){
  var f=fixture(false,"PUBLIC_PREVIEW");sales.purchase(f.buyer,f.campaign.id(),"dispatch-fence");ingress.drain(10);
  var old=ingress.claim();assertThat(old).isNotNull();
  db.update("UPDATE material_sale_dispatch SET lease_until_ms=0 WHERE reservation_id=?",old.reservationId());
  ingress.sent(old);ingress.failed(old);assertThat(journal.dispatch(old.reservationId()).state()).isEqualTo("SENDING");
  var current=ingress.claim();assertThat(current.token()).isGreaterThan(old.token());
  ingress.sent(old);ingress.failed(old);assertThat(journal.dispatch(old.reservationId()).token()).isEqualTo(current.token());
  ingress.sent(current);assertThat(journal.dispatch(old.reservationId()).state()).isEqualTo("SENT");
 }
 @Test void controlRetryFinalBlacklistCheckAndRepeatedReleaseAreConsistent(){
  var f=fixture(false,"PUBLIC_PREVIEW");
  sales.faults().armFailure("controls.beforeApply");
  assertThatThrownBy(()->sales.block(f.author,f.buyer,"test")).isInstanceOf(IllegalStateException.class);
  var r=sales.purchase(f.buyer,f.campaign.id(),"before-blacklist-sync");ingress.drain(10);sales.settle(r.reservationId());sales.settle(r.reservationId());
  assertThat(sales.getRequest(f.buyer,r.reservationId()).rejectReason()).isEqualTo("BLACKLISTED");
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);
  ingress.syncJobs(100);
  assertThatThrownBy(()->sales.purchase(f.buyer,f.campaign.id(),"blocked")).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("BLACKLISTED"));
  sales.unblock(f.author,f.buyer);
  assertThat(sales.purchase(f.buyer,f.campaign.id(),"unblocked").state()).isEqualTo("PROCESSING");
 }
 @Test void redisLossPausesAndRestoresCommittedReservationWithoutInventingLostRequest(){
  var f=fixture(false,"PUBLIC_PREVIEW");var durable=sales.purchase(f.buyer,f.campaign.id(),"durable");ingress.drain(1);
  long other=auth.register("jmb"+UUID.randomUUID().toString().replace("-","").substring(0,12),"other-test-password").userId();
  var volatileOnly=sales.purchase(other,f.campaign.id(),"volatile");
  redis.entry().commands().flushdb();
  assertThatThrownBy(()->sales.purchase(other,f.campaign.id(),"retry")).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.status).isEqualTo(503));
  ingress.recover();
  assertThat(sales.getRequest(f.buyer,durable.reservationId()).state()).isEqualTo("PROCESSING");
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(1);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,volatileOnly.reservationId())).isZero();
  var retried=sales.purchase(other,f.campaign.id(),"volatile");ingress.drain(10);
  sales.settle(durable.reservationId());sales.settle(retried.reservationId());
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders",Integer.class)).isEqualTo(2);
 }
 @Test void aofRestartPreservesUnbridgedStreamAndReservationIdentity()throws Exception{
  var f=fixture(false,"PUBLIC_PREVIEW");var r=sales.purchase(f.buyer,f.campaign.id(),"aof-restart");
  assertThat(REDIS.execInContainer("redis-cli","WAITAOF","1","0","5000").getExitCode()).isZero();
  String oldRun=redis.entry().runId();
  assertThat(REDIS.execInContainer("sh","-c","kill -9 $(redis-cli --raw INFO server | sed -n 's/^process_id://p' | tr -d '\\r')").getExitCode()).isZero();
  org.awaitility.Awaitility.await().ignoreExceptions().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(()->{
   assertThat(redis.entry().commands().ping()).isEqualTo("PONG");assertThat(redis.entry().runId()).isNotEqualTo(oldRun);
  });
  assertThatThrownBy(()->sales.purchase(f.buyer,f.campaign.id(),"aof-restart")).isInstanceOfSatisfying(FeedFailure.class,
   e->assertThat(e.status).isEqualTo(503));
  ingress.recover();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,r.reservationId())).isEqualTo(1);
  assertThat(sales.purchase(f.buyer,f.campaign.id(),"aof-restart").reservationId()).isEqualTo(r.reservationId());
  sales.settle(r.reservationId());assertThat(sales.getRequest(f.buyer,r.reservationId()).state()).isEqualTo("SUCCEEDED");
 }
 @Test void failedRecoveryLeaseCanBeTakenOverAndOldGenerationCannotInstall(){
  var f=fixture(false,"PUBLIC_PREVIEW");
  redis.entry().commands().hset(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.CONTROL,"repair","1");
  sales.faults().armFailure("recovery.beforeInstall");
  assertThatThrownBy(ingress::recover).isInstanceOf(IllegalStateException.class);
  long old=journal.runtime().generation();
  assertThatThrownBy(()->sales.purchase(f.buyer,f.campaign.id(),"during-recovery")).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.status).isEqualTo(503));
  db.update("UPDATE material_sale_runtime SET lease_until_ms=0 WHERE id=1");ingress.recover();
  assertThat(journal.runtime().generation()).isGreaterThan(old);
  assertThatThrownBy(()->redis.entry().install(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.BASE+"stage:"+old,old)).isInstanceOf(IllegalStateException.class);
  assertThat(sales.purchase(f.buyer,f.campaign.id(),"after-recovery").state()).isEqualTo("PROCESSING");
 }
 @Test void wrongStreamTypeDoesNotDecrementStockAndOfflinePreventsFinalOrder(){
  var f=fixture(false,"PUBLIC_PREVIEW");
  redis.entry().commands().del(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM);
  redis.entry().commands().set(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STREAM,"corrupt-test-value");
  assertThatThrownBy(()->sales.purchase(f.buyer,f.campaign.id(),"wrong-type")).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.status).isEqualTo(503));
  assertThat(redis.remainingStock(f.campaign.id())).isEqualTo(2);ingress.recover();
  var r=sales.purchase(f.buyer,f.campaign.id(),"offline");
  follows.offline(f.author,f.post,db.queryForObject("SELECT version FROM posts WHERE id=?",Long.class,f.post));
  ingress.drain(10);sales.settle(r.reservationId());ingress.syncJobs(100);
  assertThat(sales.getRequest(f.buyer,r.reservationId()).state()).isEqualTo("REJECTED");
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders",Integer.class)).isZero();
  assertThat(db.queryForObject("SELECT state FROM material_sale_campaigns WHERE id=?",String.class,f.campaign.id())).isEqualTo("CLOSED");
  long now=System.currentTimeMillis();
  assertThatThrownBy(()->sales.createCampaign(f.author,f.post,100,2,now-1000,now+3600000))
   .isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.code).isEqualTo("POST_OFFLINE"));
 }
}
