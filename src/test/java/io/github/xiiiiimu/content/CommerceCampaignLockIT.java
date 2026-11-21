package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Campaign-row lock contracts (docs/commerce-campaign-lock-analysis.md, options (a)+(b)).
 * The settle transaction pauses at "settle.beforeStockUpdate" (right before its stock UPDATE); while it is paused the
 * campaign row must not be held, so other settles, close, takedown, refunds and bridge inserts are not serialized
 * behind the order writes, and the final conditional UPDATE decides.
 * Run only the lock* methods: -Dit.test='CommerceCampaignLockIT#lock*' (inherited purchase tests run in their own class).
 */
class CommerceCampaignLockIT extends MaterialCommercePurchaseIT {
 static final String PAUSE="settle.beforeStockUpdate";
 @Autowired DataSource dataSource;
 final ExecutorService pool=Executors.newCachedThreadPool();
 record Sale(long author,long post,long campaign,long[] buyers){}

 /** A failed assertion can leave the async settle/close/refund in flight; let it finish so it cannot write into the next test's wipe. */
 @AfterEach void cleanup()throws InterruptedException{
  sales.faults().releasePause(PAUSE);pool.shutdown();
  if(!pool.awaitTermination(60,TimeUnit.SECONDS))pool.shutdownNow();
  sales.faults().resetFailures();
 }

 Sale sale(String tag,int stock,int buyers){
  long author=register("cl"+tag+"a","password-a1");
  var mat=materials.createDraft(author,"锁"+tag,"sale",List.of("sale"),"PUBLIC_PREVIEW",null,true,true,
   "video".getBytes(StandardCharsets.UTF_8),"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,mat.postId(),UUID.randomUUID().toString(),0);
  long[] ids=new long[buyers];
  for(int i=0;i<buyers;i++){ids[i]=register("cl"+tag+"b"+i,"password-b1");follows.change(ids[i],author,true);}
  long now=System.currentTimeMillis();
  var c=sales.createCampaign(author,mat.postId(),100,stock,now-1000,now+3600000);
  return new Sale(author,mat.postId(),c.id(),ids);
 }
 /** Accepted and durably persisted (bridge) but not yet settled. */
 String persisted(long user,long campaign,String key){
  var accepted=sales.purchase(user,campaign,key);
  org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).until(()->{
   sales.flushPending(200);
   return db.queryForObject("SELECT COUNT(*) FROM material_purchase_requests WHERE reservation_id=?",Integer.class,accepted.reservationId())==1;
  });
  return accepted.reservationId();
 }
 Future<Object> settleAsync(String rid){return pool.submit(()->{sales.settle(rid);return null;});}
 Future<Object> pausedSettle(String rid)throws Exception{
  sales.faults().armPause(PAUSE);
  var f=settleAsync(rid);
  assertThat(sales.faults().awaitPaused(PAUSE,20)).as("settle reached the point right before its stock UPDATE").isTrue();
  return f;
 }
 String state(String rid){return db.queryForObject("SELECT CONCAT(state,':',COALESCE(reject_reason,'')) FROM material_purchase_requests WHERE reservation_id=?",String.class,rid);}
 int orders(String rid){return db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE reservation_id=?",Integer.class,rid);}
 int sold(long campaign){return db.queryForObject("SELECT sold_count FROM material_sale_campaigns WHERE id=?",Integer.class,campaign);}
 Connection rc()throws SQLException{var c=dataSource.getConnection();c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);return c;}
 int rootLockWaits()throws SQLException{
  try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")){r.next();return r.getInt(1);}
 }

 @Test void lockSettleDoesNotHoldTheCampaignRowWhileWritingTheOrder()throws Exception{
  var s=sale("rw",3,3);
  String a=persisted(s.buyers()[0],s.campaign(),"a"),b=persisted(s.buyers()[1],s.campaign(),"b");
  var paused=pausedSettle(a);
  try{
   try(var c=rc();var st=c.prepareStatement("SELECT id FROM material_sale_campaigns WHERE id=? FOR UPDATE NOWAIT")){
    st.setLong(1,s.campaign());
    assertThatCode(()->{try(var r=st.executeQuery()){r.next();}}).as("campaign row is free while the paused settle writes its order").doesNotThrowAnyException();
    c.rollback();
   }
   try(var c=rc();var st=c.createStatement()){
    st.execute("SET SESSION innodb_lock_wait_timeout=2");
    assertThatCode(()->st.executeUpdate("INSERT INTO material_purchase_requests(reservation_id,campaign_id,post_id,merchant_id,user_id,client_key,price_cents,state,created_at_ms,updated_at_ms)"
     +" VALUES ('"+"f".repeat(32)+"',"+s.campaign()+","+s.post()+","+s.author()+","+s.buyers()[2]+",'bridge-probe',100,'PROCESSING',1,1)"))
     .as("a bridge-style request insert does not wait for the settle in progress").doesNotThrowAnyException();
    c.rollback();
   }
   assertThatCode(()->settleAsync(b).get(10,TimeUnit.SECONDS)).as("another buyer's settle on the same campaign completes meanwhile").doesNotThrowAnyException();
   assertThat(state(b)).isEqualTo("SUCCEEDED:");
  }finally{sales.faults().releasePause(PAUSE);}
  paused.get(20,TimeUnit.SECONDS);
  assertThat(state(a)).isEqualTo("SUCCEEDED:");
  assertThat(sold(s.campaign())).isEqualTo(2);
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE campaign_id=? AND state='PAID'",Integer.class,s.campaign())).isEqualTo(2);
 }

 @Test void lockCloseCommitsDuringSettleAndTheFinalUpdateRejectsWithoutOrder()throws Exception{
  var s=sale("cc",2,1);
  String a=persisted(s.buyers()[0],s.campaign(),"a");
  var paused=pausedSettle(a);
  try{
   var close=pool.submit(()->sales.closeCampaign(s.author(),s.campaign()));
   assertThatCode(()->close.get(10,TimeUnit.SECONDS)).as("close is not blocked by a settle that has not reached its stock UPDATE").doesNotThrowAnyException();
  }finally{sales.faults().releasePause(PAUSE);}
  paused.get(20,TimeUnit.SECONDS);
  assertThat(state(a)).isEqualTo("REJECTED:CAMPAIGN_CLOSED");
  assertThat(orders(a)).as("inserted order rolled back").isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE source_kind='PURCHASE' AND post_id=?",Integer.class,s.post())).isZero();
  assertThat(sold(s.campaign())).isZero();
  assertThat(db.queryForObject("SELECT decision FROM material_sale_consume_receipts WHERE reservation_id=?",String.class,a)).isEqualTo("REJECTED:CAMPAIGN_CLOSED");
 }

 @Test void lockSoldOutAtTheFinalUpdateRollsBackAndRejects()throws Exception{
  var s=sale("so",2,1);
  String a=persisted(s.buyers()[0],s.campaign(),"a");
  var paused=pausedSettle(a);
  try{
   // Another settlement takes the last unit while this one is writing (simulated on the row itself).
   try(var c=rc();var st=c.createStatement()){
    st.execute("SET SESSION innodb_lock_wait_timeout=2");
    assertThatCode(()->st.executeUpdate("UPDATE material_sale_campaigns SET sold_count=stock_cap WHERE id="+s.campaign()))
     .as("stock can change while the paused settle writes").doesNotThrowAnyException();
    c.commit();
   }
  }finally{sales.faults().releasePause(PAUSE);}
  paused.get(20,TimeUnit.SECONDS);
  assertThat(state(a)).isEqualTo("REJECTED:SOLD_OUT");
  assertThat(orders(a)).isZero();
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_entitlements WHERE source_kind='PURCHASE' AND post_id=?",Integer.class,s.post())).isZero();
  assertThat(db.queryForObject("SELECT decision FROM material_sale_consume_receipts WHERE reservation_id=?",String.class,a)).isEqualTo("REJECTED:SOLD_OUT");
  assertThat(sold(s.campaign())).isEqualTo(2);
 }

 @Test void lockTakedownDuringSettleDoesNotDeadlock()throws Exception{
  var s=sale("td",2,1);
  String a=persisted(s.buyers()[0],s.campaign(),"a");
  long version=db.queryForObject("SELECT version FROM posts WHERE id=?",Long.class,s.post());
  var paused=pausedSettle(a);
  Future<?> offline;
  try{
   offline=pool.submit(()->{follows.offline(s.author(),s.post(),version);return null;});
   org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(()->rootLockWaits()>0||offline.isDone());
  }finally{sales.faults().releasePause(PAUSE);}
  assertThatCode(()->paused.get(30,TimeUnit.SECONDS)).as("settle is not chosen as a deadlock victim").doesNotThrowAnyException();
  assertThatCode(()->offline.get(30,TimeUnit.SECONDS)).as("takedown is not chosen as a deadlock victim").doesNotThrowAnyException();
  assertThat(db.queryForObject("SELECT status FROM posts WHERE id=?",String.class,s.post())).isEqualTo("OFFLINE");
  assertThat(db.queryForObject("SELECT state FROM material_sale_campaigns WHERE id=?",String.class,s.campaign())).isEqualTo("CLOSED");
  String result=state(a);
  assertThat(result).isIn("SUCCEEDED:","REJECTED:CAMPAIGN_CLOSED","REJECTED:POST_OFFLINE");
  assertThat(orders(a)).isEqualTo(result.startsWith("SUCCEEDED")?1:0);
  assertThat(sold(s.campaign())).isEqualTo(orders(a));
 }

 @Test void lockRefundDuringSettleKeepsSoldCountConsistent()throws Exception{
  var s=sale("rf",3,2);
  var first=purchaseAndSettle(s.buyers()[0],s.campaign(),"r");
  assertThat(first.state()).isEqualTo("SUCCEEDED");
  String a=persisted(s.buyers()[1],s.campaign(),"a");
  var paused=pausedSettle(a);
  try{
   var refund=pool.submit(()->sales.forceRefund(s.buyers()[0],first.orderId()));
   assertThatCode(()->refund.get(10,TimeUnit.SECONDS)).as("refund stock return is not serialized behind the order writes").doesNotThrowAnyException();
  }finally{sales.faults().releasePause(PAUSE);}
  paused.get(20,TimeUnit.SECONDS);
  assertThat(state(a)).isEqualTo("SUCCEEDED:");
  assertThat(sold(s.campaign())).as("sold_count equals PAID orders after refund + settle interleave")
   .isEqualTo(db.queryForObject("SELECT COUNT(*) FROM material_orders WHERE campaign_id=? AND state='PAID'",Integer.class,s.campaign())).isEqualTo(1);
 }
}
