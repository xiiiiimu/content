package io.github.xiiiiimu.content.domain;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Hardening B4: idle backoff sequence, reset on work, wake, and failing passes. */
class IdleBackoffLoopTest {
 @Test void emptyPassesDoubleThePauseUpToTheCap(){
  var loop=new IdleBackoffLoop(()->0,100,800,null);
  var pauses=new ArrayList<Long>();
  for(int i=0;i<6;i++)pauses.add(loop.nextDelay(0));
  assertThat(pauses).containsExactly(100L,200L,400L,800L,800L,800L);
 }

 @Test void aPassWithWorkRestartsFromTheBaseDelay(){
  var loop=new IdleBackoffLoop(()->0,100,800,null);
  for(int i=0;i<5;i++)loop.nextDelay(0);
  assertThat(loop.nextDelay(3)).as("work found").isEqualTo(100);
  assertThat(loop.nextDelay(0)).as("first empty pass after work").isEqualTo(100);
  assertThat(loop.nextDelay(0)).isEqualTo(200);
 }

 @Test void capEqualToBaseKeepsTheOldFixedPolling(){
  var loop=new IdleBackoffLoop(()->0,1000,1000,null);
  for(int i=0;i<10;i++)assertThat(loop.nextDelay(0)).isEqualTo(1000);
 }

 @Test void wakeEndsTheCurrentPauseAndResetsTheStreak()throws Exception{
  var passTimes=new ConcurrentLinkedQueue<Long>();
  try(var loop=new IdleBackoffLoop(()->{passTimes.add(System.nanoTime());return 0;},100,2000,null).start("idle-backoff-test")){
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(loop.passes()<5 && System.nanoTime()<deadline)Thread.sleep(10);   // 100+200+400+800 ms: now in a long pause
   assertThat(loop.passes()).isGreaterThanOrEqualTo(5);
   long before=loop.passes();
   long woke=System.nanoTime();
   loop.wake();
   while(loop.passes()==before && System.nanoTime()<deadline)Thread.sleep(1);
   long latencyMs=(passTimes.stream().filter(t->t>=woke).findFirst().orElseThrow()-woke)/1_000_000L;
   assertThat(latencyMs).as("next pass after wake, while the backoff pause was up to 1600 ms").isLessThan(100);
   while(passTimes.stream().filter(t->t>=woke).count()<2 && System.nanoTime()<deadline)Thread.sleep(1);
   var after=passTimes.stream().filter(t->t>=woke).limit(2).toList();
   assertThat((after.get(1)-after.get(0))/1_000_000L).as("streak restarted: the pause after the woken pass is the base 100 ms, not 3200 ms").isBetween(90L,400L);
  }
 }

 @Test void aFailingPassCountsAsEmptyAndIsReported(){
  var errors=new AtomicInteger();
  var loop=new IdleBackoffLoop(()->{throw new IllegalStateException("db down");},10,80,e->errors.incrementAndGet());
  assertThatCode(()->{loop.step();loop.step();}).doesNotThrowAnyException();
  assertThat(errors).hasValue(2);
  assertThat(loop.emptyPasses()).isEqualTo(2);
  assertThat(loop.nextDelay(0)).as("third empty pass").isEqualTo(40);
 }

 @Test void invalidBoundsAreRejected(){
  assertThatThrownBy(()->new IdleBackoffLoop(()->0,0,10,null)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new IdleBackoffLoop(()->0,100,50,null)).isInstanceOf(IllegalArgumentException.class);
 }
}
