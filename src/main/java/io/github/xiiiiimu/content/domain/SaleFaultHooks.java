package io.github.xiiiiimu.content.domain;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo/load fault hooks: hold settle behind a barrier, and fail once after durable commit before MQ ACK.
 * Enabled only when content.sale.fault-hooks=true.
 */
public final class SaleFaultHooks {
 private final AtomicBoolean holdArmed=new AtomicBoolean(false);
 private volatile CountDownLatch hold=new CountDownLatch(0);
 private final AtomicBoolean failAfterCommitOnce=new AtomicBoolean(false);
 private final AtomicLong inFlight=new AtomicLong();
 private final AtomicLong peakInFlight=new AtomicLong();
 /** HTTP request in-flight (servlet filter); separate from settle in-flight. */
 private final AtomicLong httpInFlight=new AtomicLong();
 private final AtomicLong peakHttpInFlight=new AtomicLong();
 private final java.util.concurrent.ConcurrentHashMap<String,AtomicBoolean> failures=new java.util.concurrent.ConcurrentHashMap<>();
 private final java.util.concurrent.atomic.AtomicReference<String> watchedReservation=new java.util.concurrent.atomic.AtomicReference<>();
 private final AtomicLong watchedDeliveries=new AtomicLong();
 public void watchDeliveries(String id){watchedReservation.set(id);watchedDeliveries.set(0);}
 public void recordDelivery(String id){if(id!=null&&id.equals(watchedReservation.get()))watchedDeliveries.incrementAndGet();}
 public long watchedDeliveries(){return watchedDeliveries.get();}
 public void armFailure(String point){failures.put(point,new AtomicBoolean(true));}
 public void resetFailures(){failures.clear();failAfterCommitOnce.set(false);watchedReservation.set(null);watchedDeliveries.set(0);}
 public void failIfArmed(String point){var armed=failures.get(point);if(armed!=null&&armed.compareAndSet(true,false))throw new IllegalStateException("INJECTED_"+point);}

 public void armHold(){
  CountDownLatch next=new CountDownLatch(1);
  hold=next;holdArmed.set(true);
 }
 public void releaseHold(){
  holdArmed.set(false);
  CountDownLatch cur=hold;if(cur!=null)cur.countDown();
 }
 public boolean holdArmed(){return holdArmed.get();}

 public void awaitHoldIfArmed(long timeoutSeconds){
  if(!holdArmed.get())return;
  CountDownLatch cur=hold;if(cur==null)return;
  try{
   if(!cur.await(timeoutSeconds,TimeUnit.SECONDS))throw new FeedFailure(503,"SETTLE_HOLD_TIMEOUT");
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new FeedFailure(503,"SETTLE_HOLD_INTERRUPTED");}
 }

 public void armFailAfterCommitOnce(){failAfterCommitOnce.set(true);}
 /** Throws once after a successful durable settle so the consumer returns RECONSUME_LATER (pre-ACK). */
 public void maybeFailAfterCommit(){
  if(failAfterCommitOnce.compareAndSet(true,false))throw new IllegalStateException("INJECTED_FAIL_AFTER_COMMIT");
 }

 public void enterInFlight(){
  long v=inFlight.incrementAndGet();
  peakInFlight.accumulateAndGet(v,Math::max);
 }
 public void leaveInFlight(){inFlight.decrementAndGet();}
 public long inFlight(){return inFlight.get();}
 public long peakInFlight(){return peakInFlight.get();}
 public void resetPeakInFlight(){peakInFlight.set(inFlight.get());}

 public void enterHttpInFlight(){
  long v=httpInFlight.incrementAndGet();
  peakHttpInFlight.accumulateAndGet(v,Math::max);
 }
 public void leaveHttpInFlight(){httpInFlight.decrementAndGet();}
 public long httpInFlight(){return httpInFlight.get();}
 public long peakHttpInFlight(){return peakHttpInFlight.get();}
 public void resetPeakHttpInFlight(){peakHttpInFlight.set(httpInFlight.get());}
}
