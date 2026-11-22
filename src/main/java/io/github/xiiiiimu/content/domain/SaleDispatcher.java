package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.SaleIngressMapper.Dispatch;
import io.github.xiiiiimu.content.infrastructure.SaleLatency;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Batch MQ dispatch under the fixed 30s lease (no renewal), 10s send timeout and 0 client retries.
 * <p>One coordinator claims only for idle sender slots: at most {@link #PER_SENDER} rows per idle slot and never more than
 * the configured batch cap. Each slot sends its rows one after another, and before every send the remaining lease
 * (measured conservatively from before the claim transaction began) must cover the 10s send plus a 5s confirmation
 * reserve, otherwise that row is left SENDING for normal lease takeover. When every slot of a claim has finished, the
 * ACKed rows are confirmed in one transaction and failed rows go back to PENDING in one transaction.
 */
public final class SaleDispatcher implements AutoCloseable {
 public static final long LEASE_MS=30_000,SEND_TIMEOUT_MS=10_000,CONFIRM_RESERVE_MS=5_000;
 /** 2 sequential sends x 10s + 5s confirmation reserve = 25s, inside the 30s lease. */
 public static final int PER_SENDER=2;
 public record Report(int requested,int claimed,int sent,int failed,int budgetSkipped,int confirmMissed,int retryMissed,boolean outcomePersisted){
  static final Report EMPTY=new Report(0,0,0,0,0,0,0,true);
 }
 private record Chunk(List<Dispatch> ok,List<Dispatch> failed,List<Dispatch> skipped){}

 private final SaleIngress ingress;private final Supplier<SaleMessageSender> sender;private final SaleFaultHooks faults;
 private final int batchMax,concurrency;private final LongSupplier nanos;
 private final Semaphore slots;private final ExecutorService senders;
 private final AtomicLong budgetSkips=new AtomicLong(),confirmMisses=new AtomicLong(),outcomeFailures=new AtomicLong();
 /** Producer warm-up guard: until this many sends succeeded, claim one row for one slot (a cold first send can use most of a lease). */
 private final int slowStartSends;
 private final AtomicLong successfulSends=new AtomicLong();
 private final Map<String,AtomicLong> sendFailureTypes=new ConcurrentHashMap<>();
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SaleDispatcher.class);

 public SaleDispatcher(SaleIngress ingress,Supplier<SaleMessageSender> sender,SaleFaultHooks faults,int batchMax,int concurrency){
  this(ingress,sender,faults,batchMax,concurrency,0,System::nanoTime);
 }
 public SaleDispatcher(SaleIngress ingress,Supplier<SaleMessageSender> sender,SaleFaultHooks faults,int batchMax,int concurrency,int slowStartSends){
  this(ingress,sender,faults,batchMax,concurrency,slowStartSends,System::nanoTime);
 }
 public SaleDispatcher(SaleIngress ingress,Supplier<SaleMessageSender> sender,SaleFaultHooks faults,int batchMax,int concurrency,LongSupplier nanos){
  this(ingress,sender,faults,batchMax,concurrency,0,nanos);
 }
 public SaleDispatcher(SaleIngress ingress,Supplier<SaleMessageSender> sender,SaleFaultHooks faults,int batchMax,int concurrency,int slowStartSends,LongSupplier nanos){
  if(slowStartSends<0)throw new IllegalArgumentException("slow start sends must be >=0");
  this.slowStartSends=slowStartSends;
  if(batchMax<1||batchMax>200)throw new IllegalArgumentException("dispatch batch cap must be 1..200");
  if(concurrency<1||concurrency>16)throw new IllegalArgumentException("dispatch concurrency must be 1..16");
  this.ingress=ingress;this.sender=sender;this.faults=faults==null?new SaleFaultHooks():faults;
  this.batchMax=batchMax;this.concurrency=concurrency;this.nanos=nanos;
  this.slots=new Semaphore(concurrency);
  this.senders=Executors.newFixedThreadPool(concurrency,r->{var t=new Thread(r,"sale-dispatch-send");t.setDaemon(true);return t;});
 }
 public int batchMax(){return batchMax;}
 public int concurrency(){return concurrency;}
 public long budgetSkips(){return budgetSkips.get();}
 public long confirmMisses(){return confirmMisses.get();}
 public long outcomeFailures(){return outcomeFailures.get();}
 public int slowStartSends(){return slowStartSends;}
 public long successfulSends(){return successfulSends.get();}
 public boolean slowStarting(){return successfulSends.get()<slowStartSends;}
 /** Send failures by exception type (simple class name). */
 public Map<String,Long> sendFailureTypes(){var m=new TreeMap<String,Long>();sendFailureTypes.forEach((k,v)->m.put(k,v.get()));return m;}

 /**
  * Background coordinator: keeps idle slots fed until a claim comes back empty or {@code maxClaims} claims were made.
  * Returns how much work it saw (B4): the number of claims that took rows, plus one for every time all slots were busy,
  * so a sender that is still working is never treated as idle. 0 = the first claim came back empty.
  */
 public int pump(int maxClaims)throws InterruptedException{
  int work=0;
  for(int i=0;i<maxClaims;i++){
   var batch=dispatch();
   if(batch==null){work++;if(!slots.tryAcquire(1,TimeUnit.SECONDS))return work;slots.release();continue;}
   if(batch.isDone()&&batch.getNow(null)!=null&&batch.getNow(null).claimed()==0)return work;
   work++;
  }
  return work;
 }
 /** Synchronous single claim for tests and tools; waits for the outcome transactions. */
 public Report dispatchOnce()throws Exception{var f=dispatch();return f==null?null:f.get(2*LEASE_MS,TimeUnit.MILLISECONDS);}

 /** Claims for the currently idle slots. Returns null when every slot is busy (nothing is claimed then). */
 public CompletableFuture<Report> dispatch(){
  var send=sender.get();if(send==null)return CompletableFuture.completedFuture(Report.EMPTY);
  boolean slow=slowStarting();
  int idle=0;while(idle<(slow?1:concurrency)&&slots.tryAcquire())idle++;
  if(idle==0)return null;
  int requested=slow?1:Math.min(batchMax,PER_SENDER*idle);
  long started=nanos.getAsLong();
  List<Dispatch> claimed;
  try(var trace=SaleLatency.begin(null,"dispatch-claim",0,0);var phase=SaleLatency.phase("dispatch.claim")){claimed=ingress.claimBatch(requested);}
  catch(RuntimeException e){slots.release(idle);throw e;}
  long claimMillis=millis(started);
  int used=Math.min(idle,(claimed.size()+PER_SENDER-1)/PER_SENDER);
  slots.release(idle-used);
  if(claimed.isEmpty())return CompletableFuture.completedFuture(Report.EMPTY);
  try{faults.failIfArmed("dispatch.afterClaimCommit");}
  catch(RuntimeException e){slots.release(used);throw e;} // Simulated crash: rows stay SENDING until their lease expires.
  var futures=new ArrayList<CompletableFuture<Chunk>>();
  for(int from=0;from<claimed.size();from+=PER_SENDER){
   var rows=claimed.subList(from,Math.min(claimed.size(),from+PER_SENDER));
   futures.add(CompletableFuture.supplyAsync(()->sendChunk(send,rows,started),senders));
  }
  int fRequested=requested,fIdle=idle;
  return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
   .thenApply(v->finish(fRequested,fIdle,claimed,futures.stream().map(CompletableFuture::join).toList(),started,claimMillis))
   .whenComplete((r,e)->slots.release(used));
 }

 private Chunk sendChunk(SaleMessageSender send,List<Dispatch> rows,long started){
  var ok=new ArrayList<Dispatch>();var failed=new ArrayList<Dispatch>();var skipped=new ArrayList<Dispatch>();
  for(var d:rows){
   if(remainingMillis(started)<SEND_TIMEOUT_MS+CONFIRM_RESERVE_MS){skipped.add(d);budgetSkips.incrementAndGet();continue;}
   try(var trace=SaleLatency.begin(null,"dispatch-send",0,0)){
    SaleLatency.correlate(null,d.reservationId());
    long sendStart=nanos.getAsLong();boolean acked=false;
    try{
     try(var phase=SaleLatency.phase("mq.send")){send.send(d.reservationId());}
     acked=true;successfulSends.incrementAndGet();
     try(var phase=SaleLatency.phase("mq.ack.success")){}
     // Same meaning as the serial path: the ACK is treated as lost, so the row takes the retry path.
     faults.failIfArmed("dispatch.afterAck");
     ok.add(d);
    }catch(Exception e){
     failed.add(d);
     String type=e.getClass().getSimpleName();
     sendFailureTypes.computeIfAbsent(type,k->new AtomicLong()).incrementAndGet();
     log.warn("sale dispatch send failed type={} acked={} elapsedMs={} reservationId={} message={}",type,acked,millis(sendStart),d.reservationId(),e.getMessage());
    }
   }
  }
  return new Chunk(ok,failed,skipped);
 }

 private Report finish(int requested,int idle,List<Dispatch> claimed,List<Chunk> chunks,long started,long claimMillis){
  var ok=new ArrayList<Dispatch>();var failed=new ArrayList<Dispatch>();int skipped=0;
  for(var c:chunks){ok.addAll(c.ok());failed.addAll(c.failed());skipped+=c.skipped().size();}
  long sendMillis=millis(started)-claimMillis,outcomeStart=nanos.getAsLong();
  int confirmMissed=0,retryMissed=0;boolean persisted=true;
  try(var trace=SaleLatency.begin(null,"dispatch-confirm",0,0)){
   // Simulated crash after broker ACK and before any outcome commit: everything stays SENDING for takeover.
   faults.failIfArmed("dispatch.beforeConfirm");
   if(!ok.isEmpty()){
    SaleIngress.Outcome r;try(var phase=SaleLatency.phase("dispatch.confirm")){r=ingress.sentBatch(ok);}
    confirmMissed=r.missed().size();confirmMisses.addAndGet(confirmMissed);
   }
   if(!failed.isEmpty()){
    SaleIngress.Outcome r;try(var phase=SaleLatency.phase("dispatch.retry")){r=ingress.failedBatch(failed);}
    retryMissed=r.missed().size();
   }
  }catch(RuntimeException e){
   // A rolled-back outcome transaction is not an ACK: rows stay SENDING and the lease protocol takes over.
   persisted=false;outcomeFailures.incrementAndGet();
  }
  SaleLatency.dispatchBatch(requested,idle,claimed.stream().map(Dispatch::reservationId).toList(),ok.size(),failed.size(),skipped,
   confirmMissed,retryMissed,claimMillis,sendMillis,millis(outcomeStart),persisted);
  return new Report(requested,claimed.size(),ok.size(),failed.size(),skipped,confirmMissed,retryMissed,persisted);
 }
 private long remainingMillis(long started){return LEASE_MS-millis(started);}
 private long millis(long since){return TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong()-since);}
 @Override public void close(){senders.shutdownNow();}
}
