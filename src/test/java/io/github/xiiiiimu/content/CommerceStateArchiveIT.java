package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.SaleEntryRedis;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Hardening C3: sale state of ended campaigns leaves Redis (MySQL is the source of truth), clientKey identity is scoped to
 * the campaign (option B), recovery loads only the active set, and the old global clientKey format migrates in place.
 * Same containers and sale settings as CommerceIngressRevisionIT (standalone so CI does not rerun that suite).
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class CommerceStateArchiveIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @Container static final GenericContainer<?> S3=new GenericContainer<>("adobe/s3mock:3.12.0").withExposedPorts(9090);
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
  r.add("content.media.enabled",()->"true");r.add("content.media.s3-endpoint",()->"http://"+S3.getHost()+":"+S3.getMappedPort(9090));
  r.add("content.media.bucket",()->"content-media");r.add("content.media.secret-key",()->"media-test-secret-key-not-for-production-01");
  r.add("content.media.ffmpeg-image",()->"");r.add("content.assistant.python-url",()->"");
  r.add("content.media.scan-delay-millis",()->"3600000");r.add("content.sale.sync-settle",()->"false");
  r.add("content.sale.scan-enabled",()->"false");
  r.add("content.retention.sale-state.enabled",()->"false");   // driven explicitly
 }
 @Autowired SaleService sales;@Autowired MaterialService materials;@Autowired PublishService publish;@Autowired AuthService auth;
 @Autowired JdbcTemplate db;@Autowired SaleIngress ingress;@Autowired io.github.xiiiiimu.content.infrastructure.SaleRedis redis;
 @Autowired SaleStateArchiver archiver;

 static final long DAY=86_400_000L;
 @BeforeEach void reset(){
  for(String table:List.of("material_sale_stream_quarantine","material_sale_reservation_fences","material_sale_legacy_replays","material_sale_cache_jobs",
   "material_sale_legacy_streams","material_sale_consume_receipts","material_refund_requests","material_sale_dispatch","giveaway_winners","giveaway_candidates",
   "material_entitlements","material_orders","material_purchase_requests","material_sale_eligibility_blocks","material_sale_campaigns","merchant_blacklist",
   "account_material_decision_locks","giveaways","notifications"))db.update("DELETE FROM "+table);
  db.update("UPDATE material_sale_runtime SET state='RECOVERING',owner=NULL,lease_until_ms=0,redis_run_id=NULL WHERE id=1");
  redis.entry().commands().flushdb();
 }
 record Material(long author,long buyer,long post){}
 Material material(){
  String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
  long author=auth.register("ca"+suffix,"test-author-password").userId();
  long buyer=auth.register("cb"+suffix,"test-buyer-password").userId();
  var m=materials.createDraft(author,"c3","sale",List.of("test"),"PUBLIC_PREVIEW",null,true,true,new byte[]{1,2,3},"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,m.postId(),UUID.randomUUID().toString(),0);
  return new Material(author,buyer,m.postId());
 }
 SaleService.CampaignView campaign(Material m,int stock){long now=System.currentTimeMillis();return sales.createCampaign(m.author(),m.post(),100,stock,now-1000,now+3600000);}
 String field(String name){return redis.entry().commands().hget(SaleEntryRedis.STATE,name);}
 /** Bought and settled: one SUCCEEDED request with an order. */
 SaleService.PurchaseView bought(long buyer,long campaign,String key){
  var p=sales.purchase(buyer,campaign,key);ingress.drain(20);sales.settle(p.reservationId());return p;
 }
 /** The campaign stopped more than the 24 h grace period ago (MySQL is what the archiver reads). */
 void endedLongAgo(long campaign){long end=System.currentTimeMillis()-DAY-3_600_000L;db.update("UPDATE material_sale_campaigns SET start_ms=?,end_ms=? WHERE id=?",end-3_600_000L,end,campaign);}
 boolean archived(long campaign){return db.queryForObject("SELECT cache_archived_at_ms IS NOT NULL AND cache_released_at_ms IS NOT NULL FROM material_sale_campaigns WHERE id=?",Boolean.class,campaign);}
 int count(String sql,Object... args){return db.queryForObject(sql,Integer.class,args);}

 @Test void sameClientKeyInAnotherCampaignIsANewRequest(){
  var a=material();var b=material();var ca=campaign(a,2);var cb=campaign(b,2);
  var first=bought(a.buyer(),ca.id(),"shared-key");
  var second=sales.purchase(a.buyer(),cb.id(),"shared-key");
  assertThat(second.reservationId()).as("another campaign, same key: a different request").isNotEqualTo(first.reservationId());
  assertThat(second.campaignId()).isEqualTo(cb.id());
  assertThat(sales.purchase(a.buyer(),cb.id(),"shared-key").reservationId()).as("same campaign, same key: replay").isEqualTo(second.reservationId());
  ingress.drain(20);sales.settle(second.reservationId());
  assertThat(count("SELECT COUNT(*) FROM material_purchase_requests WHERE user_id=? AND client_key='shared-key'",a.buyer())).isEqualTo(2);
  assertThat(count("SELECT COUNT(*) FROM material_orders WHERE user_id=?",a.buyer())).isEqualTo(2);
  assertThat(count("SELECT COUNT(*) FROM material_sale_stream_quarantine")).as("no CLIENT_KEY_CONFLICT").isZero();
  assertThat(field(SaleEntryRedis.client(a.buyer(),ca.id(),"shared-key"))).isEqualTo(first.reservationId());
  assertThat(field(SaleEntryRedis.client(a.buyer(),cb.id(),"shared-key"))).isEqualTo(second.reservationId());
 }

 @Test void archivedCampaignQueriesAndReplaysFallBackToMySql(){
  var m=material();var c=campaign(m,2);
  var won=bought(m.buyer(),c.id(),"archive-key");
  endedLongAgo(c.id());
  archiver.archive();
  assertThat(archived(c.id())).isTrue();
  assertThat(field("r:"+won.reservationId())).as("request state left Redis").isNull();
  assertThat(field(SaleEntryRedis.client(m.buyer(),c.id(),"archive-key"))).isNull();
  assertThat(field("c:"+c.id()+":known")).isNull();
  assertThat(field("v:REQUEST:"+won.reservationId())).as("version field removed with the archive (C3-3)").isNull();
  assertThat(field("o:"+m.buyer()+":"+m.post())).as("no active campaign on the post: ownership left Redis").isNull();
  var query=sales.getRequest(m.buyer(),won.reservationId());
  assertThat(query.state()).isEqualTo("SUCCEEDED");assertThat(query.orderId()).isNotNull();
  assertThatThrownBy(()->sales.getRequest(m.author(),won.reservationId())).isInstanceOfSatisfying(FeedFailure.class,e->assertThat(e.status).isEqualTo(403));
  var replay=sales.purchase(m.buyer(),c.id(),"archive-key");
  assertThat(replay.reservationId()).as("same-key replay answered from MySQL").isEqualTo(won.reservationId());
  assertThat(replay.state()).isEqualTo("SUCCEEDED");assertThat(replay.orderId()).isEqualTo(query.orderId());
  var other=material();
  assertThatThrownBy(()->sales.purchase(other.buyer(),c.id(),"late-key")).as("archived campaign keeps its 409, not 404")
   .isInstanceOfSatisfying(FeedFailure.class,e->{assertThat(e.status).isEqualTo(409);assertThat(e.code).isEqualTo("ENDED");});
  assertThat(count("SELECT COUNT(*) FROM material_purchase_requests")).isEqualTo(1);
  assertThat(field(SaleEntryRedis.client(m.buyer(),c.id(),"archive-key"))).as("a replay does not bring state back").isNull();
 }

 @Test void archiveLeavesUnfinishedUnpersistedAndQuarantinedCampaigns(){
  var p=material();var processing=campaign(p,2);
  var held=sales.purchase(p.buyer(),processing.id(),"processing-key");ingress.drain(20);   // persisted, not settled
  var u=material();var unpersisted=campaign(u,2);
  var inStream=sales.purchase(u.buyer(),unpersisted.id(),"stream-key");                     // only in the Redis Stream
  var q=material();var quarantined=campaign(q,2);
  bought(q.buyer(),quarantined.id(),"quarantine-key");
  db.update("INSERT INTO material_sale_stream_quarantine(stream_id,payload,reason,reservation_id,campaign_id,disposition,created_at_ms,updated_at_ms) VALUES('9-0','{}','CLIENT_KEY_CONFLICT',?,?,'HOLD',0,0)",
   "c".repeat(32),quarantined.id());
  for(long id:List.of(processing.id(),unpersisted.id(),quarantined.id()))endedLongAgo(id);
  var done=material();var finished=campaign(done,2);bought(done.buyer(),finished.id(),"finished-key");endedLongAgo(finished.id());
  archiver.archive();
  assertThat(archived(finished.id())).as("control: the finished campaign is archived").isTrue();
  assertThat(archived(processing.id())).as("PROCESSING request").isFalse();
  assertThat(archived(unpersisted.id())).as("Stream entry not persisted").isFalse();
  assertThat(archived(quarantined.id())).as("unresolved quarantine").isFalse();
  assertThat(field("r:"+held.reservationId())).isNotNull();
  assertThat(field("r:"+inStream.reservationId())).isNotNull();
  assertThat(field("c:"+quarantined.id()+":known")).isEqualTo("1");
  sales.settle(held.reservationId());ingress.drain(20);sales.settle(inStream.reservationId());
  db.update("UPDATE material_sale_stream_quarantine SET disposition='NO_RELEASE'");
  archiver.archive();
  assertThat(archived(processing.id())).isTrue();assertThat(archived(unpersisted.id())).isTrue();assertThat(archived(quarantined.id())).isTrue();
  assertThat(field("r:"+held.reservationId())).isNull();
 }

 @Test void recoveryLoadsOnlyTheActiveSet(){
  var old=material();var ended=campaign(old,2);var oldBuy=bought(old.buyer(),ended.id(),"old-key");endedLongAgo(ended.id());
  archiver.archive();
  var cur=material();var live=campaign(cur,2);var liveBuy=bought(cur.buyer(),live.id(),"live-key");
  redis.entry().commands().hset(SaleEntryRedis.CONTROL,"repair","1");ingress.recover();
  assertThat(field("r:"+oldBuy.reservationId())).as("archived request not reloaded").isNull();
  assertThat(field(SaleEntryRedis.client(old.buyer(),ended.id(),"old-key"))).isNull();
  assertThat(field("c:"+ended.id()+":known")).isNull();
  assertThat(field("o:"+old.buyer()+":"+old.post())).isNull();
  assertThat(field("r:"+liveBuy.reservationId())).as("active request reloaded").isNotNull();
  assertThat(field(SaleEntryRedis.client(cur.buyer(),live.id(),"live-key"))).isEqualTo(liveBuy.reservationId());
  assertThat(field("o:"+cur.buyer()+":"+cur.post())).isEqualTo(liveBuy.reservationId());
  assertThat(field("c:"+live.id()+":known")).isEqualTo("1");
  assertThat(sales.purchase(cur.buyer(),live.id(),"live-key").reservationId()).isEqualTo(liveBuy.reservationId());
  assertThat(sales.getRequest(old.buyer(),oldBuy.reservationId()).state()).isEqualTo("SUCCEEDED");
 }

 @Test void oneOrderPerAccountPerMaterialAcrossAnArchivedCampaign(){
  var m=material();var first=campaign(m,2);var won=bought(m.buyer(),first.id(),"first-key");
  sales.closeCampaign(m.author(),first.id());endedLongAgo(first.id());
  archiver.archive();
  assertThat(field("o:"+m.buyer()+":"+m.post())).as("precondition: ownership left Redis with the archive").isNull();
  var second=campaign(m,2);
  assertThat(field("o:"+m.buyer()+":"+m.post())).as("a new campaign loads the post's owners").isEqualTo(won.reservationId());
  var again=sales.purchase(m.buyer(),second.id(),"second-key");
  assertThat(again.reservationId()).as("the owner gets the original request back").isEqualTo(won.reservationId());
  ingress.drain(20);
  assertThat(count("SELECT COUNT(*) FROM material_orders WHERE user_id=? AND post_id=?",m.buyer(),m.post())).isEqualTo(1);
  assertThat(count("SELECT COUNT(*) FROM material_purchase_requests WHERE user_id=?",m.buyer())).isEqualTo(1);
 }

 @Test void refundAfterArchiveDoesNotWriteStateBack(){
  var m=material();var c=campaign(m,2);var won=bought(m.buyer(),c.id(),"refund-key");endedLongAgo(c.id());
  archiver.archive();
  long order=sales.getRequest(m.buyer(),won.reservationId()).orderId();
  sales.forceRefund(m.buyer(),order);ingress.syncJobs(200);
  assertThat(count("SELECT COUNT(*) FROM material_sale_cache_jobs WHERE revision>applied_revision")).as("jobs settled in MySQL").isZero();
  assertThat(field("r:"+won.reservationId())).isNull();
  assertThat(field("refund:"+order)).isNull();
  assertThat(field("o:"+m.buyer()+":"+m.post())).isNull();
  assertThat(field("c:"+c.id()+":stock")).isNull();
 }

 @Test void interruptedArchiveResumesWithTheRelease(){
  var m=material();var c=campaign(m,2);var won=bought(m.buyer(),c.id(),"resume-key");endedLongAgo(c.id());
  // Interrupted after the MySQL mark, before any Redis delete.
  db.update("UPDATE material_sale_campaigns SET cache_archived_at_ms=1 WHERE id=?",c.id());
  assertThat(field("r:"+won.reservationId())).isNotNull();
  var result=archiver.archive();
  assertThat(result.archived()).as("already marked: not archived again").isZero();
  assertThat(result.released()).isEqualTo(1);
  assertThat(archived(c.id())).isTrue();
  assertThat(field("r:"+won.reservationId())).isNull();
  assertThat(field("c:"+c.id()+":known")).isNull();
  assertThat(archiver.archive().released()).as("nothing left to do").isZero();
  assertThat(sales.purchase(m.buyer(),c.id(),"resume-key").reservationId()).isEqualTo(won.reservationId());
 }

 @Test void oldGlobalClientKeysMigrateInPlace(){
  var a=material();var b=material();var ca=campaign(a,2);var cb=campaign(b,2);
  var first=bought(a.buyer(),ca.id(),"legacy-format-key");
  // Simulate state written by the previous release: global k: field, no campaign-scoped field, no format marker.
  var c=redis.entry().commands();
  c.hdel(SaleEntryRedis.STATE,SaleEntryRedis.client(a.buyer(),ca.id(),"legacy-format-key"));
  c.hset(SaleEntryRedis.STATE,SaleEntryRedis.client(a.buyer(),"legacy-format-key"),first.reservationId());
  c.hdel(SaleEntryRedis.CONTROL,"fmt");
  assertThat(sales.purchase(a.buyer(),ca.id(),"legacy-format-key").reservationId()).as("replay during migration").isEqualTo(first.reservationId());
  assertThat(archiver.migrate()).isTrue();
  assertThat(c.hget(SaleEntryRedis.CONTROL,"fmt")).isEqualTo("3");
  assertThat(field(SaleEntryRedis.client(a.buyer(),"legacy-format-key"))).as("old global field removed").isNull();
  assertThat(field(SaleEntryRedis.client(a.buyer(),ca.id(),"legacy-format-key"))).isEqualTo(first.reservationId());
  assertThat(sales.purchase(a.buyer(),ca.id(),"legacy-format-key").reservationId()).as("replay after migration").isEqualTo(first.reservationId());
  assertThat(sales.purchase(a.buyer(),cb.id(),"legacy-format-key").reservationId()).as("another campaign: new request").isNotEqualTo(first.reservationId());
 }
}
