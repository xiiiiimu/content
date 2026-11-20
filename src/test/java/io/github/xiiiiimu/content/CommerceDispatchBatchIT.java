package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.*;
import io.github.xiiiiimu.content.infrastructure.SaleIngressMapper.Dispatch;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Batch dispatch contracts against disposable real MySQL and Redis inherited from the ingress fixture.
 * Broker-facing contracts (real ACK then crash, duplicate delivery) live in {@link CommerceDispatchBatchRealMqIT}.
 * Run only these methods: -Dit.test='CommerceDispatchBatchIT#readyTasks*+successfulAcks*+batch*'.
 */
class CommerceDispatchBatchIT extends CommerceIngressRevisionIT {
 private void twoReady(){
  var f=fixture(false,"PUBLIC_PREVIEW");
  long other=auth.register("jmb"+UUID.randomUUID().toString().replace("-","").substring(0,12),"test-buyer-password").userId();
  sales.purchase(f.buyer(),f.campaign().id(),"dispatch-first");
  sales.purchase(other,f.campaign().id(),"dispatch-second");
  ingress.drain("dispatch-fixture",2,0);
 }
 /** n durable PENDING dispatch rows from n distinct buyers on one campaign with stock n. */
 List<String> ready(int n){
  String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
  long author=auth.register("jma"+suffix,"test-author-password").userId();
  var material=materials.createDraft(author,"批量发送","sale",List.of("test"),"PUBLIC_PREVIEW",null,true,true,new byte[]{1,2,3},"video/mp4","PORTRAIT");
  for(int i=0;i<8;i++)materials.tick();
  publish.publish(author,material.postId(),UUID.randomUUID().toString(),0);
  long now=System.currentTimeMillis();
  var campaign=sales.createCampaign(author,material.postId(),100,n,now-1000,now+3600000);
  var ids=new ArrayList<String>();
  for(int i=0;i<n;i++){
   long buyer=auth.register("jmc"+UUID.randomUUID().toString().replace("-","").substring(0,12),"test-buyer-password").userId();
   ids.add(sales.purchase(buyer,campaign.id(),"batch-"+i).reservationId());
  }
  ingress.drain("dispatch-fixture",n,0);
  assertThat(count("state='PENDING'")).isEqualTo(n);
  return ids;
 }
 int count(String where){return db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch WHERE "+where,Integer.class);}
 Dispatch row(String id){return journal.dispatch(id);}
 void expire(String id){db.update("UPDATE material_sale_dispatch SET lease_until_ms=0 WHERE reservation_id=?",id);}
 private long commits(Runnable operation)throws Exception{return phases(operation,"jdbc.commit");}
 private long phases(Runnable operation,String name)throws Exception{
  Path path=Files.createTempFile(Path.of("target"),"dispatch-batch-",".jfr");
  try(var recording=new Recording()){
   recording.enable(SaleLatency.PhaseEvent.class);recording.start();
   try(var trace=SaleLatency.begin(null,"dispatch-batch-contract",0,0)){operation.run();}
   recording.stop();recording.dump(path);
  }
  return RecordingFile.readAllEvents(path).stream().filter(e->e.getEventType().getName().equals("content.SalePhase"))
   .filter(e->e.getString("kind").equals("dispatch-batch-contract")&&e.getString("phase").equals(name)).count();
 }
 private Map<String,Long> phasesByKind(Runnable operation)throws Exception{
  Path path=Files.createTempFile(Path.of("target"),"dispatch-batch-",".jfr");
  try(var recording=new Recording()){
   recording.enable(SaleLatency.PhaseEvent.class);recording.start();operation.run();recording.stop();recording.dump(path);
  }
  var out=new TreeMap<String,Long>();
  for(var e:RecordingFile.readAllEvents(path))if(e.getEventType().getName().equals("content.SalePhase"))out.merge(e.getString("kind")+"/"+e.getString("phase"),1L,Long::sum);
  return out;
 }
 /** Records sends; fails ids in {@code failing}; optionally blocks until released. */
 static final class StubSender implements SaleMessageSender {
  final List<String> sent=new CopyOnWriteArrayList<>();final Set<String> failing=ConcurrentHashMap.newKeySet();
  volatile CountDownLatch gate=new CountDownLatch(0);final CountDownLatch entered=new CountDownLatch(1);
  @Override public void send(String id)throws Exception{
   entered.countDown();if(!gate.await(20,TimeUnit.SECONDS))throw new TimeoutException("test gate");
   sent.add(id);if(failing.contains(id))throw new IllegalStateException("stub send failure");
  }
 }
 SaleDispatcher dispatcher(SaleMessageSender sender,int batchMax,int concurrency){return new SaleDispatcher(ingress,()->sender,sales.faults(),batchMax,concurrency);}

 @Test void readyTasksAreClaimedInOneRealTransaction()throws Exception{
  twoReady();var claimed=new ArrayList<Dispatch>();
  long count=commits(()->claimed.addAll(ingress.claimBatch(20)));
  assertThat(claimed).hasSize(2).allSatisfy(d->{assertThat(d.state()).isEqualTo("SENDING");assertThat(d.token()).isEqualTo(1);assertThat(d.attempts()).isEqualTo(1);});
  assertThat(count).as("two ready tasks share one committed claim transaction, not two plus an empty claim").isEqualTo(1);
 }
 @Test void successfulAcksAreConfirmedInOneRealTransaction()throws Exception{
  twoReady();var claims=List.of(ingress.claim(),ingress.claim());
  long count=commits(()->ingress.sentBatch(claims));
  assertThat(db.queryForObject("SELECT COUNT(*) FROM material_sale_dispatch WHERE state='SENT'",Integer.class)).isEqualTo(2);
  assertThat(count).as("successful ACKs share one confirmation transaction").isEqualTo(1);
 }

 @Test void batchConcurrentExecutorsNeverHoldTheSameLiveLease()throws Exception{
  var ids=ready(12);var seen=new ConcurrentLinkedQueue<String>();var start=new CountDownLatch(1);
  var pool=Executors.newFixedThreadPool(4);
  try{
   var futures=new ArrayList<Future<?>>();
   for(int t=0;t<4;t++)futures.add(pool.submit(()->{start.await();for(;;){var got=ingress.claimBatch(2);if(got.isEmpty())return null;got.forEach(d->seen.add(d.reservationId()));}}));
   start.countDown();for(var f:futures)f.get(60,TimeUnit.SECONDS);
  }finally{pool.shutdownNow();}
  assertThat(seen).as("every ready row is claimed exactly once while its lease is live").hasSize(12).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
  assertThat(count("state='SENDING' AND token=1 AND attempts=1")).isEqualTo(12);
 }
 @Test void batchExpiredLeaseIsTakenOverAndOldTokenCannotConfirm()throws Exception{
  var ids=ready(2);var first=ingress.claimBatch(2);expire(ids.get(0));
  var takeover=ingress.claimBatch(2);
  assertThat(takeover).singleElement().satisfies(d->{assertThat(d.reservationId()).isEqualTo(ids.get(0));assertThat(d.token()).isEqualTo(2);assertThat(d.attempts()).isEqualTo(2);});
  var stale=ingress.sentBatch(first);
  assertThat(stale.applied()).as("only the row whose token/lease is still ours is confirmed").containsExactly(ids.get(1));
  assertThat(stale.missed()).as("partial miss is reported per row, not as whole-batch success").containsExactly(ids.get(0));
  assertThat(row(ids.get(0))).satisfies(d->{assertThat(d.state()).isEqualTo("SENDING");assertThat(d.token()).isEqualTo(2);});
  assertThat(ingress.sentBatch(takeover).applied()).containsExactly(ids.get(0));
  assertThat(count("state='SENT'")).isEqualTo(2);
  assertThat(ingress.failedBatch(first).applied()).as("an old token cannot send the row back to PENDING either").isEmpty();
  assertThat(count("state='SENT'")).isEqualTo(2);
 }
 @Test void batchExpiredSendingKeepsAShareAgainstAlwaysDuePending()throws Exception{
  var ids=ready(6);
  // Deterministic: the expired row has the largest id, so id order alone (all available_at_ms=0) would rank it last.
  var stuck=db.queryForObject("SELECT MAX(reservation_id) FROM material_sale_dispatch",String.class);
  db.update("UPDATE material_sale_dispatch SET state='SENDING',attempts=1,token=1,lease_until_ms=0 WHERE reservation_id=?",stuck);
  var next=ingress.claimBatch(2);
  assertThat(next).extracting(Dispatch::reservationId).as("expired SENDING is not starved by PENDING rows with available_at_ms=0").contains(stuck).hasSize(2);
  assertThat(ids).contains(stuck);
 }
 @Test void batchCrashAfterClaimCommitLeavesSendingForTakeover()throws Exception{
  var ids=ready(2);var sender=new StubSender();
  try(var d=dispatcher(sender,20,1)){
   sales.faults().armFailure("dispatch.afterClaimCommit");
   assertThatThrownBy(d::dispatchOnce).hasMessageContaining("INJECTED_dispatch.afterClaimCommit");
   assertThat(sender.sent).as("nothing is sent after a crash between claim commit and send").isEmpty();
   assertThat(count("state='SENDING' AND token=1")).isEqualTo(2);
   assertThat(d.dispatchOnce().claimed()).as("live leases are not re-claimed").isZero();
   ids.forEach(this::expire);
   var r=d.dispatchOnce();
   assertThat(r.claimed()).isEqualTo(2);assertThat(r.sent()).isEqualTo(2);
   assertThat(count("state='SENT' AND token=2 AND attempts=2")).isEqualTo(2);
  }
 }
 @Test void batchPartialSendFailureConfirmsAndRetriesInOneTransactionEach()throws Exception{
  var ids=ready(2);var sender=new StubSender();sender.failing.add(ids.get(1));
  try(var d=dispatcher(sender,20,1)){
   var report=new SaleDispatcher.Report[1];
   var byKind=phasesByKind(()->{try{report[0]=d.dispatchOnce();}catch(Exception e){throw new RuntimeException(e);}});
   assertThat(report[0]).satisfies(r->{assertThat(r.claimed()).isEqualTo(2);assertThat(r.sent()).isEqualTo(1);assertThat(r.failed()).isEqualTo(1);assertThat(r.outcomePersisted()).isTrue();});
   assertThat(row(ids.get(0)).state()).isEqualTo("SENT");
   assertThat(row(ids.get(1))).satisfies(r->{assertThat(r.state()).isEqualTo("PENDING");assertThat(r.attempts()).isEqualTo(1);assertThat(r.token()).isEqualTo(1);});
   // Signed arithmetic: the 250ms backoff may already have elapsed when this runs.
   long delay=db.queryForObject("SELECT CAST(available_at_ms AS SIGNED)-CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED) FROM material_sale_dispatch WHERE reservation_id=?",Long.class,ids.get(1));
   assertThat(delay).as("original backoff min(60000,250*2^min(attempts-1,8)) = 250ms for attempt 1").isLessThanOrEqualTo(250L).isGreaterThan(-60000L);
   // Claim order follows reservation_id (random), not purchase order.
   assertThat(sender.sent).containsExactlyInAnyOrder(ids.get(0),ids.get(1));
   assertThat(byKind).as("one claim commit; one confirm and one retry transaction on the sender side")
    .containsEntry("dispatch-claim/jdbc.commit",1L).containsEntry("dispatch-confirm/jdbc.commit",2L)
    .containsEntry("dispatch-confirm/dispatch.confirm",1L).containsEntry("dispatch-confirm/dispatch.retry",1L)
    .containsEntry("dispatch-send/mq.send",2L);
  }
 }
 @Test void batchConfirmTransactionFailureDoesNotFakeTheAck()throws Exception{
  var ids=ready(2);var sender=new StubSender();
  try(var d=dispatcher(sender,20,1)){
   sales.faults().armFailure("dispatch.beforeConfirmCommit");
   var r=d.dispatchOnce();
   assertThat(r.sent()).isEqualTo(2);assertThat(r.outcomePersisted()).isFalse();
   assertThat(count("state='SENT'")).as("rolled-back confirmation leaves no SENT row").isZero();
   assertThat(count("state='SENDING' AND token=1")).isEqualTo(2);
   ids.forEach(this::expire);
   assertThat(d.dispatchOnce().sent()).isEqualTo(2);
   assertThat(sender.sent).as("takeover resends with the same event identity").containsExactlyInAnyOrder(ids.get(0),ids.get(1),ids.get(0),ids.get(1));
   assertThat(count("state='SENT' AND token=2")).isEqualTo(2);
  }
 }
 @Test void batchFullCapacityDoesNotClaimMoreThanIdleSendersCanCarry()throws Exception{
  ready(5);var sender=new StubSender();sender.gate=new CountDownLatch(1);
  try(var d=dispatcher(sender,20,1)){
   var first=d.dispatch();
   assertThat(sender.entered.await(10,TimeUnit.SECONDS)).isTrue();
   assertThat(count("state='SENDING'")).as("cap 20, concurrency 1: one sender slot carries at most 2").isEqualTo(2);
   assertThat(d.dispatch()).as("no idle sender slot means no claim").isNull();
   assertThat(count("state='SENDING'")).isEqualTo(2);
   sender.gate.countDown();assertThat(first.get(30,TimeUnit.SECONDS).sent()).isEqualTo(2);
  }
  try(var d=dispatcher(sender,20,4)){
   assertThat(d.dispatchOnce().claimed()).as("concurrency 4 would allow 8; only 3 remain").isEqualTo(3);
  }
  assertThat(count("state='SENT'")).isEqualTo(5);
 }
 @Test void batchInsufficientLeaseBudgetLeavesSendingWithoutSending()throws Exception{
  var ids=ready(2);var sender=new StubSender();var clock=new AtomicLong(0);
  var probe=new SaleMessageSender(){public void send(String id)throws Exception{clock.addAndGet(TimeUnit.SECONDS.toNanos(9));sender.send(id);}};
  try(var d=new SaleDispatcher(ingress,()->probe,sales.faults(),20,1,clock::get)){
   var r=d.dispatchOnce();
   // t=0 claim; first send starts with 30s left (>=15s) and takes 9s; second would start with 21s left -> sends; jump past budget below.
   assertThat(r.sent()).isEqualTo(2);
  }
  ready(2);clock.set(0);
  var late=new SaleMessageSender(){public void send(String id)throws Exception{clock.addAndGet(TimeUnit.SECONDS.toNanos(16));sender.send(id);}};
  sender.sent.clear();
  try(var d=new SaleDispatcher(ingress,()->late,sales.faults(),20,1,clock::get)){
   var r=d.dispatchOnce();
   assertThat(r.claimed()).isEqualTo(2);
   assertThat(r.sent()).isEqualTo(1);
   assertThat(r.budgetSkipped()).as("second send would start with 14s left < 10s send + 5s confirm").isEqualTo(1);
   assertThat(r.failed()).isZero();
   assertThat(sender.sent).hasSize(1);
   String skipped=db.queryForObject("SELECT reservation_id FROM material_sale_dispatch WHERE state='SENDING'",String.class);
   assertThat(row(skipped)).as("skipped row is neither faked as success nor as failure").satisfies(x->{assertThat(x.state()).isEqualTo("SENDING");assertThat(x.token()).isEqualTo(1);});
   assertThat(d.budgetSkips()).isEqualTo(1);
  }
  assertThat(ids).allSatisfy(id->assertThat(row(id).state()).isEqualTo("SENT"));
 }
 @Test void batchSlowStartClaimsOneRowUntilTheFirstSendsSucceed()throws Exception{
  var ids=ready(6);var sender=new StubSender();
  // Producer warm-up guard: a cold sender must not take 8 leases at once (C tier: one cold batch of 8 used 24.9s).
  try(var d=new SaleDispatcher(ingress,()->sender,sales.faults(),20,4,2)){
   assertThat(d.dispatchOnce().claimed()).as("first claim after start: one row").isEqualTo(1);
   assertThat(d.dispatchOnce().claimed()).as("still slow-starting until 2 sends succeeded").isEqualTo(1);
   assertThat(d.dispatchOnce().claimed()).as("then the normal capacity bound applies").isEqualTo(4);
  }
  assertThat(ids).allSatisfy(id->assertThat(row(id).state()).isEqualTo("SENT"));
 }
 @Test void batchSlowStartDoesNotAdvanceOnFailedSends()throws Exception{
  var ids=ready(3);var sender=new StubSender();sender.failing.add(ids.get(0));sender.failing.add(ids.get(1));sender.failing.add(ids.get(2));
  try(var d=new SaleDispatcher(ingress,()->sender,sales.faults(),20,4,1)){
   assertThat(d.dispatchOnce().claimed()).isEqualTo(1);
   ids.forEach(id->db.update("UPDATE material_sale_dispatch SET available_at_ms=0 WHERE reservation_id=?",id));
   assertThat(d.dispatchOnce().claimed()).as("a failed send is not a warm send").isEqualTo(1);
  }
 }
 @Test void batchSendFailureRecordsTheExceptionType()throws Exception{
  var ids=ready(1);var sender=new StubSender();sender.failing.add(ids.get(0));
  try(var d=dispatcher(sender,20,1)){
   assertThat(d.dispatchOnce().failed()).isEqualTo(1);
   assertThat(d.sendFailureTypes()).as("send failure cause is evidence, not swallowed").containsEntry("IllegalStateException",1L);
  }
 }
}
