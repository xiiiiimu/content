package io.github.xiiiiimu.content.domain;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardening A4: BCrypt (about 60 ms CPU per call) runs on a fixed pool with a bounded queue instead of request threads.
 * Pool and queue full → {@link RateLimited} (429, Retry-After 1) at once, so a login burst costs bounded CPU and never
 * piles up behind the hash. A result not ready within {@code waitMillis} is abandoned with 503 AUTH_BUSY (safety net;
 * with the bounded queue the wait is at most about (queue / threads + 1) hashes).
 */
public final class PasswordHasher implements AutoCloseable {
 private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
 private final ThreadPoolExecutor pool;private final long waitMillis;
 private final java.util.concurrent.atomic.AtomicLong checks=new java.util.concurrent.atomic.AtomicLong();

 public PasswordHasher(int threads,int queue,long waitMillis){
  if(threads<1 || threads>64)throw new IllegalArgumentException("content.auth.bcrypt-threads must be in [1,64]");
  if(queue<0 || queue>10_000)throw new IllegalArgumentException("content.auth.bcrypt-queue must be in [0,10000]");
  if(waitMillis<100)throw new IllegalArgumentException("content.auth.bcrypt-wait-millis must be >= 100");
  var n=new AtomicInteger();
  ThreadFactory factory=r->{var t=new Thread(r,"bcrypt-"+n.incrementAndGet());t.setDaemon(true);return t;};
  BlockingQueue<Runnable> q=queue==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(queue);
  this.pool=new ThreadPoolExecutor(threads,threads,0,TimeUnit.MILLISECONDS,q,factory,new ThreadPoolExecutor.AbortPolicy());
  this.waitMillis=waitMillis;
 }
 public static int defaultThreads(){return defaultThreads(Runtime.getRuntime().availableProcessors());}
 /**
  * Default pool size max(2, logical cores / 2): during a login burst half the cores stay free for every other endpoint,
  * and at least two logins hash at once on a small machine. Higher login capacity comes from more instances, not a
  * bigger pool. Override with content.auth.bcrypt-threads.
  */
 static int defaultThreads(int logicalCores){return Math.max(2,logicalCores/2);}

 public String encode(String raw){return run(()->encoder.encode(raw));}
 public boolean matches(String raw,String hash){return Boolean.TRUE.equals(run(()->{checks.incrementAndGet();return encoder.matches(raw,hash);}));}
 /** Password checks run so far (counted on the pool thread, so a count means the check really went through the pool). */
 public long checks(){return checks.get();}

 private <T> T run(Callable<T> work){
  Future<T> f;
  try{f=pool.submit(work);}catch(RejectedExecutionException e){throw new RateLimited("bcrypt",1);}
  try{return f.get(waitMillis,TimeUnit.MILLISECONDS);}
  catch(TimeoutException e){f.cancel(true);throw new FeedFailure(503,"AUTH_BUSY");}
  catch(InterruptedException e){f.cancel(true);Thread.currentThread().interrupt();throw new FeedFailure(503,"AUTH_BUSY");}
  catch(ExecutionException e){
   if(e.getCause() instanceof RuntimeException r)throw r;
   throw new IllegalStateException(e.getCause());
  }
 }
 @Override public void close(){pool.shutdownNow();}
}
