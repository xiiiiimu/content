package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.infrastructure.SaleProducerWarmup;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** The sale sender is handed to dispatch only after the topic route and broker connection are warm. */
class SaleProducerWarmupTest {
 @Test void installsOnlyAfterRouteAndBrokerSucceedAndRecordsEveryAttempt(){
  var routeCalls=new AtomicInteger();var installed=new AtomicInteger();var installedAfterRoutes=new AtomicInteger(-1);
  var result=SaleProducerWarmup.run(
   ()->{if(routeCalls.incrementAndGet()<3)throw new IllegalStateException("route timeout");},
   ()->{},
   ()->{installed.incrementAndGet();installedAfterRoutes.set(routeCalls.get());},
   5_000,10);
  assertThat(result.warm()).isTrue();
  assertThat(installed.get()).as("installed exactly once").isEqualTo(1);
  assertThat(installedAfterRoutes.get()).as("not before the route resolved").isEqualTo(3);
  assertThat(result.attempts()).hasSize(3);
  assertThat(result.attempts().get(0).error()).isEqualTo("IllegalStateException");
  assertThat(result.attempts().get(2).error()).isNull();
 }
 @Test void brokerFailureAlsoBlocksInstall(){
  var brokerCalls=new AtomicInteger();var installed=new AtomicInteger();
  var result=SaleProducerWarmup.run(()->{},()->{if(brokerCalls.incrementAndGet()<2)throw new IllegalStateException("broker");},installed::incrementAndGet,5_000,10);
  assertThat(result.warm()).isTrue();assertThat(result.attempts()).hasSize(2);assertThat(installed.get()).isEqualTo(1);
 }
 @Test void afterTheDeadlineKeepsRetryingInBackgroundAndInstallsOnSuccess()throws Exception{
  long start=System.nanoTime();var done=new CountDownLatch(1);
  var result=SaleProducerWarmup.run(
   ()->{if(System.nanoTime()-start<TimeUnit.MILLISECONDS.toNanos(400))throw new IllegalStateException("cold");},
   ()->{},done::countDown,100,20);
  assertThat(result.warm()).as("deadline passed: start-up continues without a sender").isFalse();
  assertThat(done.getCount()).as("never installed before the warm-up succeeded").isEqualTo(1);
  assertThat(done.await(5,TimeUnit.SECONDS)).as("installed later by the background retry").isTrue();
 }
}
