package io.github.xiiiiimu.content.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RocketMQ producer start-up warm-up: resolve the sale topic route and connect to its brokers before the sender is
 * handed to dispatch, so the first sale sends do not pay cold-connection cost inside a 30s dispatch lease
 * (mq-dispatch-batch-20261119.md: every 11-18.5s first send happened in the warm-up window).
 * <p>Blocks the caller until warm or until {@code deadlineMs}; past the deadline start-up continues without a sender
 * (dispatch treats a missing sender as "nothing to send", rows stay PENDING) and a daemon thread keeps retrying and
 * installs the sender on the first success.
 */
public final class SaleProducerWarmup {
 private static final Logger log=LoggerFactory.getLogger(SaleProducerWarmup.class);
 public interface Step{void run()throws Exception;}
 /** error is the exception's simple class name, null for a successful attempt. */
 public record Attempt(int number,long routeMs,long brokerMs,String error){}
 public record Result(boolean warm,long elapsedMs,List<Attempt> attempts){}

 private SaleProducerWarmup(){}

 public static Result run(Step route,Step broker,Runnable install,long deadlineMs,long retryDelayMs){
  if(deadlineMs<0||retryDelayMs<1)throw new IllegalArgumentException("deadline must be >=0 and retry delay >=1");
  long start=System.nanoTime();
  var attempts=new ArrayList<Attempt>();
  while(true){
   var a=attempt(attempts.size()+1,route,broker);attempts.add(a);
   long elapsed=ms(start);
   if(a.error()==null){
    install.run();
    log.info("sale producer warm after {} attempt(s) in {}ms (last route {}ms, broker {}ms)",attempts.size(),elapsed,a.routeMs(),a.brokerMs());
    return new Result(true,elapsed,List.copyOf(attempts));
   }
   log.warn("sale producer warm-up attempt {} failed type={} routeMs={} brokerMs={}",a.number(),a.error(),a.routeMs(),a.brokerMs());
   if(elapsed+retryDelayMs>deadlineMs)break;
   if(!sleep(retryDelayMs))break;
  }
  int done=attempts.size();
  var t=new Thread(()->{
   for(int n=done+1;!Thread.currentThread().isInterrupted();n++){
    if(!sleep(retryDelayMs))return;
    var a=attempt(n,route,broker);
    if(a.error()==null){install.run();log.info("sale producer warm in background after {} attempt(s), {}ms since start",n,ms(start));return;}
    log.warn("sale producer background warm-up attempt {} failed type={}",n,a.error());
   }
  },"sale-producer-warmup");
  t.setDaemon(true);t.start();
  long elapsed=ms(start);
  log.warn("sale producer not warm after {}ms ({} attempts); dispatch has no sender until background warm-up succeeds",elapsed,done);
  return new Result(false,elapsed,List.copyOf(attempts));
 }

 private static Attempt attempt(int n,Step route,Step broker){
  long t0=System.nanoTime(),routeMs,brokerMs=0;
  try{route.run();}catch(Exception e){return new Attempt(n,ms(t0),0,e.getClass().getSimpleName());}
  routeMs=ms(t0);long t1=System.nanoTime();
  try{broker.run();}catch(Exception e){return new Attempt(n,routeMs,ms(t1),e.getClass().getSimpleName());}
  brokerMs=ms(t1);
  return new Attempt(n,routeMs,brokerMs,null);
 }
 private static boolean sleep(long ms){try{Thread.sleep(ms);return true;}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
 private static long ms(long since){return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-since);}
}
