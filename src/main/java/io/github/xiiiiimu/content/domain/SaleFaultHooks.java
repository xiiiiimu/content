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
}
