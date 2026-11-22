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
import org.springframework.beans.factory.annotation.Autowired;
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

 // ---- Hardening B4: idle backoff of the sender loop (lease and confirmation semantics are covered above, unchanged) ----
 static final long IDLE_BASE_MS=100,IDLE_MAX_MS=800;
 /** One claim transaction plus a stub send: the time a pass needs once it starts, on top of the pause before it. */
 static final long PASS_ALLOWANCE_MS=300;
 static final class TimedSender implements SaleMessageSender {
  final Map<String,Long> sentAt=new ConcurrentHashMap<>();
  @Override public void send(String id){sentAt.putIfAbsent(id,System.currentTimeMillis());}
 }
 IdleBackoffLoop senderLoop(SaleDispatcher d){
  return new IdleBackoffLoop(()->{try{return d.pump(20);}catch(InterruptedException e){Thread.currentThread().interrupt();return 0;}},IDLE_BASE_MS,IDLE_MAX_MS,null);
 }
 /** Rows other tests left PENDING would be real work for the loop; this test needs an idle start. */
 void noOutstandingDispatch(){db.update("UPDATE material_sale_dispatch SET state='SENT' WHERE state IN ('PENDING','SENDING')");}
 long sendLatency(TimedSender sender,String id,long enqueuedAt)throws InterruptedException{
  long deadline=System.currentTimeMillis()+10_000;
  while(!sender.sentAt.containsKey(id) && System.currentTimeMillis()<deadline)Thread.sleep(5);
  assertThat(sender.sentAt).as("row was sent").containsKey(id);
  return sender.sentAt.get(id)-enqueuedAt;
 }
 @Test void batchIdleSenderBacksOffAndStillSendsUnsignalledWorkWithinTheCap()throws Exception{
  noOutstandingDispatch();var sender=new TimedSender();
  try(var d=dispatcher(sender,20,1);var loop=senderLoop(d).start("b4-idle-loop")){
   Thread.sleep(3000);
   assertThat(loop.emptyPasses()).as("empty claims in 3 s idle; fixed %d ms polling would make about 30",IDLE_BASE_MS).isLessThanOrEqualTo(8);
   var id=ready(1).get(0);                       // committed PENDING, and nobody tells the loop (as if another instance enqueued it)
   long latency=sendLatency(sender,id,System.currentTimeMillis());
   assertThat(latency).as("enqueue → send during backoff, cap %d ms + pass %d ms",IDLE_MAX_MS,PASS_ALLOWANCE_MS).isLessThanOrEqualTo(IDLE_MAX_MS+PASS_ALLOWANCE_MS);
  }
 }
 @Test void batchWakeSendsNewWorkWithoutWaitingForTheBackoff()throws Exception{
  noOutstandingDispatch();var sender=new TimedSender();
  try(var d=dispatcher(sender,20,1);var loop=senderLoop(d).start("b4-wake-loop")){
   Thread.sleep(2000);                          // pauses have reached the cap
   var id=ready(1).get(0);
   long enqueued=System.currentTimeMillis();
   loop.wake();                                 // what the bridge cycle does after it persisted entries
   assertThat(sendLatency(sender,id,enqueued)).as("enqueue → send after wake").isLessThanOrEqualTo(PASS_ALLOWANCE_MS);
  }
 }

 // ---- Hardening B5: cache sync after settle. Lives here for the commit counter; the batcher under test is a local instance
 // (not started unless a test starts it), swapped into SaleService and restored afterwards. ----
 @Autowired SaleCacheSyncBatcher cacheSyncBean;
 /** B5: commits made on the calling thread, whatever trace kind (settle and the scanner open their own traces). */
 long threadCommits(Runnable operation)throws Exception{
  Path path=Files.createTempFile(Path.of("target"),"b5-commits-",".jfr");long me=Thread.currentThread().getId();
  try(var recording=new Recording()){
   recording.enable(SaleLatency.PhaseEvent.class);recording.start();
   try(var trace=SaleLatency.begin(null,"b5-cache-sync-contract",0,0)){operation.run();}
   recording.stop();recording.dump(path);
  }
  return RecordingFile.readAllEvents(path).stream().filter(e->e.getEventType().getName().equals("content.SalePhase"))
   .filter(e->e.getString("phase").equals("jdbc.commit")&&e.getThread()!=null&&e.getThread().getJavaThreadId()==me).count();
 }

 record Sale(Fixture f,List<Long> buyers){}
 Sale sale(int buyers){
  var m=unsold(false,"PUBLIC_PREVIEW");long now=System.currentTimeMillis();
  var c=sales.createCampaign(m.author(),m.post(),100,buyers+2,now-1000,now+3600000);
  var ids=new ArrayList<Long>();ids.add(m.buyer());
  for(int i=1;i<buyers;i++){String sfx=UUID.randomUUID().toString().replace("-","").substring(0,12);ids.add(auth.register("jmx"+sfx,"test-buyer-password").userId());}
  return new Sale(new Fixture(m.author(),m.buyer(),m.post(),c),ids);
 }
 String purchased(Sale s,long buyer,String key){var id=sales.purchase(buyer,s.f().campaign().id(),key).reservationId();ingress.drain("b5-"+key,20,0);return id;}
 String redisState(String id){var r=redis.entry().request(id);return r==null?null:r.state();}
 String owner(long user,long post){return redis.entry().commands().hget(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STATE,"o:"+user+":"+post);}
 int dirtyJobs(){return db.queryForObject("SELECT COUNT(*) FROM material_sale_cache_jobs WHERE revision>applied_revision",Integer.class);}
 int ownerJobRows(long user,long post){return db.queryForObject("SELECT COUNT(*) FROM material_sale_cache_jobs WHERE kind='OWNER' AND entity_key=?",Integer.class,user+":"+post);}
 <T> T withBatcher(SaleCacheSyncBatcher local,java.util.concurrent.Callable<T> body)throws Exception{
  sales.setCacheSync(local);
  try{return body.call();}finally{local.close();sales.setCacheSync(cacheSyncBean);}
 }

 @Test void b5SuccessfulSettleSyncsTheCacheInOneTransaction()throws Exception{
  var s=sale(1);long buyer=s.buyers().get(0);var id=purchased(s,buyer,"b5-ok");
  long c=threadCommits(()->sales.settle(id));
  assertThat(c).as("settle transaction + one cache-sync transaction (REQUEST also writes the owner, no separate OWNER job)").isEqualTo(2);
  assertThat(redisState(id)).isEqualTo("SUCCEEDED");
  assertThat(owner(buyer,s.f().post())).isEqualTo(id);
  assertThat(ownerJobRows(buyer,s.f().post())).as("a successful settle no longer queues an OWNER job").isZero();
 }
 @Test void b5RejectedSettleSyncsOnlyTheRequest()throws Exception{
  var s=sale(1);long buyer=s.buyers().get(0);var id=purchased(s,buyer,"b5-reject");
  sales.block(s.f().author(),buyer,"b5");
  long c=threadCommits(()->sales.settle(id));
  assertThat(redisState(id)).isEqualTo("REJECTED");
  assertThat(c).as("settle transaction + one REQUEST sync").isEqualTo(2);
  assertThat(ownerJobRows(buyer,s.f().post())).as("ownership did not change, so no OWNER job").isZero();
  assertThat(redis.remainingStock(s.f().campaign().id())).as("Redis stock restored by the REQUEST sync").isEqualTo(3);
 }
 @Test void b5AlreadyOwnedRejectionStillRepairsAMissingOwner()throws Exception{
  var s=sale(1);long buyer=s.buyers().get(0);long post=s.f().post();
  var first=purchased(s,buyer,"b5-first");sales.settle(first);
  redis.entry().commands().hdel(io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.STATE,"o:"+buyer+":"+post);   // stale Redis owner
  var second=purchased(s,buyer,"b5-second");sales.settle(second);
  assertThat(sales.getRequest(buyer,second).rejectReason()).isEqualTo("ALREADY_OWNED");
  assertThat(owner(buyer,post)).as("OWNER job repairs the missing owner").isEqualTo(first);
 }
 @Test void b5QueuedSettlesShareOneCacheSyncTransaction()throws Exception{
  var s=sale(5);var ids=new ArrayList<String>();
  for(long b:s.buyers())ids.add(purchased(s,b,"b5-q"+b));
  var batcher=new SaleCacheSyncBatcher(ingress,1000,200);
  withBatcher(batcher,()->{
   long settles=threadCommits(()->ids.forEach(sales::settleQueued));
   assertThat(settles).as("5 settle transactions, no per-order cache transaction").isEqualTo(5);
   assertThat(ids).as("cache sync is deferred to the batch").allSatisfy(id->assertThat(redisState(id)).isEqualTo("PROCESSING"));
   long flush=threadCommits(batcher::flush);
   assertThat(flush).as("one transaction for all 5 REQUEST jobs").isEqualTo(1);
   return null;
  });
  for(int i=0;i<ids.size();i++){assertThat(redisState(ids.get(i))).isEqualTo("SUCCEEDED");assertThat(owner(s.buyers().get(i),s.f().post())).isEqualTo(ids.get(i));}
  assertThat(dirtyJobs()).isZero();
 }
 @Test void b5BackgroundBatchAppliesWithinTheInterval()throws Exception{
  var s=sale(1);long buyer=s.buyers().get(0);var id=purchased(s,buyer,"b5-bg");
  withBatcher(new SaleCacheSyncBatcher(ingress,100,200).start(),()->{
   sales.settleQueued(id);long settled=System.currentTimeMillis();
   while(!"SUCCEEDED".equals(redisState(id))&&System.currentTimeMillis()-settled<5000)Thread.sleep(2);
   assertThat(redisState(id)).isEqualTo("SUCCEEDED");
   assertThat(System.currentTimeMillis()-settled).as("settle commit -> Redis shows SUCCEEDED, interval 100 ms + one batch").isLessThanOrEqualTo(400);
   return null;
  });
 }
 @Test void b5ScannerSyncsDirtyJobsInOneTransaction()throws Exception{
  var s=sale(4);var ids=new ArrayList<String>();
  for(long b:s.buyers())ids.add(purchased(s,b,"b5-scan"+b));
  withBatcher(new SaleCacheSyncBatcher(ingress,1000,200),()->{ids.forEach(sales::settleQueued);return null;});   // closed unflushed: jobs stay durable
  assertThat(dirtyJobs()).isEqualTo(4);
  assertThat(threadCommits(()->ingress.syncJobs(200))).as("the scanner applies all dirty jobs in one transaction").isEqualTo(1);
  assertThat(ids).allSatisfy(id->assertThat(redisState(id)).isEqualTo("SUCCEEDED"));
  assertThat(dirtyJobs()).isZero();
 }
 @Test void b5BatchAndScannerRacingConvergeToMySql()throws Exception{
  var s=sale(10);var ids=new ArrayList<String>();
  for(long b:s.buyers())ids.add(purchased(s,b,"b5-race"+b));
  var batcher=new SaleCacheSyncBatcher(ingress,1000,200);
  withBatcher(batcher,()->{
   ids.forEach(sales::settleQueued);
   var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
   try{
    var a=pool.submit(()->{start.await();return batcher.flush();});
    var b=pool.submit(()->{start.await();ingress.syncJobs(200);return 0;});
    start.countDown();a.get(60,TimeUnit.SECONDS);b.get(60,TimeUnit.SECONDS);
   }finally{pool.shutdownNow();}
   return null;
  });
  assertThat(dirtyJobs()).isZero();
  assertThat(ids).allSatisfy(id->assertThat(redisState(id)).isEqualTo("SUCCEEDED"));
  assertThat(redis.remainingStock(s.f().campaign().id())).isEqualTo(2);
 }
 @Test void b5BatchNeverOverwritesANewerCacheVersion()throws Exception{
  var s=sale(1);long buyer=s.buyers().get(0);var id=purchased(s,buyer,"b5-version");
  var batcher=new SaleCacheSyncBatcher(ingress,1000,200);
  withBatcher(batcher,()->{
   sales.settleQueued(id);
   // A newer version of this job already reached Redis (e.g. applied by another instance): revision 99, PROCESSING.
   long generation=db.queryForObject("SELECT generation FROM material_sale_runtime WHERE id=1",Long.class);
   var processing=new io.github.xiiiiimu.content.infrastructure.SaleEntryRedis.Request(id,s.f().campaign().id(),s.f().post(),s.f().author(),buyer,"b5-version",100,"PROCESSING",null,null,0);
   assertThat(redis.entry().apply(generation,new io.github.xiiiiimu.content.infrastructure.SaleIngressMapper.CacheJob("REQUEST",id,99,0),Map.of(),processing,null,false,0)).isTrue();
   batcher.flush();
   return null;
  });
  assertThat(redisState(id)).as("older revision skipped by the version check").isEqualTo("PROCESSING");
 }
}
