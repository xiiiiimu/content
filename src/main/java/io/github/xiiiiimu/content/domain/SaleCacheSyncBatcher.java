package io.github.xiiiiimu.content.domain;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hardening B5: cache jobs of MQ-consumer settles are applied in batches, one MySQL transaction per batch
 * ({@link SaleIngress#syncBatch}) instead of one per job.
 * <p>A submitted key waits at most {@code intervalMs} (plus the batch itself) before it is applied. Only the timing changes:
 * every job is still the durable {@code material_sale_cache_jobs} row the settle transaction dirtied, applied under its row
 * lock with the same revision checks (MySQL {@code applied WHERE revision=}, Lua {@code v:} version), so an older job never
 * overwrites a newer cache state. Keys still queued in memory when the process stops, or a batch that fails (Redis down),
 * are not lost: the jobs stay dirty and the bridge scanner ({@link SaleIngress#syncJobs}) or recovery applies them.
 */
public final class SaleCacheSyncBatcher implements AutoCloseable {
 public record Key(String kind,String entityKey){}
 private final SaleIngress ingress;private final long intervalMs;private final int batchMax;
 private final Object monitor=new Object();
 private final LinkedHashSet<Key> pending=new LinkedHashSet<>();
 private volatile boolean closed;private Thread thread;
 private final AtomicLong batches=new AtomicLong(),jobs=new AtomicLong(),failures=new AtomicLong(),lastWarning=new AtomicLong();

 public SaleCacheSyncBatcher(SaleIngress ingress,long intervalMs,int batchMax){
  if(intervalMs<1 || intervalMs>1000)throw new IllegalArgumentException("content.sale.cache-sync-batch-millis must be in [1,1000]");
  if(batchMax<1 || batchMax>200)throw new IllegalArgumentException("content.sale.cache-sync-batch-max must be in [1,200]");
  this.ingress=ingress;this.intervalMs=intervalMs;this.batchMax=batchMax;
 }
 /** Queues keys whose jobs the caller's committed transaction dirtied. Never blocks on I/O. */
 public void submit(List<Key> keys){
  if(keys.isEmpty())return;
  synchronized(monitor){pending.addAll(keys);monitor.notifyAll();}
 }
 /** Applies everything queued so far on the calling thread, {@code batchMax} keys per transaction; returns batches run. */
 public int flush(){
  int n=0;
  for(;;){
   List<Key> batch=new ArrayList<>();
   synchronized(monitor){
    var it=pending.iterator();
    while(it.hasNext() && batch.size()<batchMax){batch.add(it.next());it.remove();}
   }
   if(batch.isEmpty())return n;
   try{ingress.syncBatch(batch);batches.incrementAndGet();jobs.addAndGet(batch.size());}
   catch(RuntimeException e){failures.incrementAndGet();warn(e);}   // jobs stay dirty: the scanner retries them
   n++;
  }
 }
 private void loop(){
  try{
   while(!closed){
    synchronized(monitor){while(pending.isEmpty() && !closed)monitor.wait();}
    if(closed)return;
    Thread.sleep(intervalMs);   // collect what arrives within the interval into the same batch
    flush();
   }
  }catch(InterruptedException e){Thread.currentThread().interrupt();}
 }
 public synchronized SaleCacheSyncBatcher start(){
  if(thread!=null)return this;
  thread=new Thread(this::loop,"sale-cache-sync");thread.setDaemon(true);thread.start();
  return this;
 }
 private void warn(RuntimeException e){
  long now=System.currentTimeMillis(),previous=lastWarning.get();
  if(now-previous>10000 && lastWarning.compareAndSet(previous,now))
   org.slf4j.LoggerFactory.getLogger(SaleCacheSyncBatcher.class).warn("Sale cache sync batch failed, the scanner retries: {}",e.getClass().getSimpleName());
 }
 public long intervalMs(){return intervalMs;}
 public long batches(){return batches.get();}
 public long jobs(){return jobs.get();}
 public long failures(){return failures.get();}
 /** Stops the thread; queued keys are left to the scanner (their jobs are durable). */
 @Override public void close(){
  closed=true;
  synchronized(monitor){monitor.notifyAll();}
  Thread t;synchronized(this){t=thread;}
  if(t!=null)t.interrupt();
 }
}
