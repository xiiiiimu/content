package io.github.xiiiiimu.content.domain;

import java.util.function.IntSupplier;

/**
 * Hardening B4: a polling loop that backs off while it finds no work.
 * <p>Each pass reports how much work it did. After a pass with work the next pass starts {@code baseMs} later (the old
 * fixed delay). After consecutive empty passes the pause doubles, {@code base, 2·base, 4·base, …}, up to {@code maxMs}.
 * {@link #wake()} (new work was enqueued in this process) cuts the current pause short and resets the streak, so local
 * work never waits for the backoff; work that only another instance knows about waits at most {@code maxMs}.
 * <p>The loop only decides <i>when</i> the next pass runs; what a pass claims, its leases and confirmations are unchanged.
 * A pass that throws counts as a pass without work (the caller logs it), so a failing dependency is also polled less often.
 */
public final class IdleBackoffLoop implements AutoCloseable {
 public interface Pauser {void pause(Object monitor,long millis)throws InterruptedException;}
 private final IntSupplier pass;private final long baseMs,maxMs;private final Pauser pauser;
 private final Object monitor=new Object();
 private int idleStreak;private boolean woken;private volatile boolean closed;private Thread thread;
 private final java.util.concurrent.atomic.AtomicLong passes=new java.util.concurrent.atomic.AtomicLong(),emptyPasses=new java.util.concurrent.atomic.AtomicLong();
 private final java.util.function.Consumer<RuntimeException> onError;

 public IdleBackoffLoop(IntSupplier pass,long baseMs,long maxMs,java.util.function.Consumer<RuntimeException> onError){
  this(pass,baseMs,maxMs,onError,(m,ms)->m.wait(ms));
 }
 public IdleBackoffLoop(IntSupplier pass,long baseMs,long maxMs,java.util.function.Consumer<RuntimeException> onError,Pauser pauser){
  if(baseMs<1)throw new IllegalArgumentException("base delay must be >=1 ms");
  if(maxMs<baseMs)throw new IllegalArgumentException("max idle delay must be >= base delay");
  this.pass=pass;this.baseMs=baseMs;this.maxMs=maxMs;this.onError=onError==null?e->{}:onError;this.pauser=pauser;
 }
 /** Pause before the next pass, given the work the last pass did. Updates the idle streak. */
 long nextDelay(int work){
  synchronized(monitor){
   if(work>0 || woken){idleStreak=0;return baseMs;}
   idleStreak=Math.min(idleStreak+1,62);
   long d=baseMs;
   for(int i=1;i<idleStreak && d<maxMs;i++)d=Math.min(maxMs,d*2);
   return d;
  }
 }
 /** New work is available: end the current pause now and restart the backoff from {@code baseMs}. */
 public void wake(){synchronized(monitor){woken=true;idleStreak=0;monitor.notifyAll();}}
 /** Runs one pass and the pause after it; returns false once closed. Package-visible for deterministic tests. */
 boolean step()throws InterruptedException{
  if(closed)return false;
  int work;
  synchronized(monitor){woken=false;}
  try{work=pass.getAsInt();}catch(RuntimeException e){work=0;onError.accept(e);}
  passes.incrementAndGet();if(work<=0)emptyPasses.incrementAndGet();
  long delay=nextDelay(work);
  synchronized(monitor){
   long until=System.nanoTime()+delay*1_000_000L;
   while(!woken && !closed){
    long left=(until-System.nanoTime())/1_000_000L;
    if(left<=0)break;
    pauser.pause(monitor,left);
   }
  }
  return !closed;
 }
 public synchronized IdleBackoffLoop start(String name){
  if(thread!=null)return this;
  thread=new Thread(()->{try{while(step()){}}catch(InterruptedException e){Thread.currentThread().interrupt();}},name);
  thread.setDaemon(true);thread.start();
  return this;
 }
 public long passes(){return passes.get();}
 public long emptyPasses(){return emptyPasses.get();}
 public long baseMs(){return baseMs;}
 public long maxMs(){return maxMs;}
 @Override public void close(){
  closed=true;
  synchronized(monitor){monitor.notifyAll();}
  Thread t;synchronized(this){t=thread;}
  if(t!=null)t.interrupt();
 }
}
