package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.TopicAggregatorMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Hardening B1: folds topic_event_ledger into topic_minute_buckets off the interaction path.
 * <p>Interaction transactions only insert a pending ledger row (aggregated_at_ms NULL), so no interaction touches a
 * topic-wide shared row. Each batch is one READ COMMITTED transaction:
 * <ol>
 *  <li>take or renew the lease on topic_aggregator_state; the row's X lock is held to commit, so batches from every
 *      instance serialize and a holder whose lease was taken over cannot commit (fencing);</li>
 *  <li>claim the oldest pending rows {@code FOR UPDATE SKIP LOCKED} (a row an in-flight unlike holds is left for later);</li>
 *  <li>add new events to their own minute's bucket; subtract revoked likes from the bucket they were counted in, never
 *      below zero; mark added rows folded and delete applied revocations;</li>
 *  <li>run the spike check for each topic that gained events (the same check the interaction transaction used to run);</li>
 *  <li>record progress: the watermark (oldest still-pending event, or batch time minus the in-flight margin), totals, lag.</li>
 * </ol>
 * A crash anywhere before commit rolls the whole batch back and its rows stay pending; a row is folded exactly when its
 * aggregated_at_ms is set in the same transaction as its bucket change, so re-running never counts twice.
 * <p>Lock order inside a batch: state row → claimed ledger rows → bucket rows (sorted by topic, minute) → risk insert. An
 * interaction transaction takes post/like rows → its own ledger row; an unlike may wait for a batch that claimed its like
 * row (one batch at most), never the other way round (SKIP LOCKED), so the two cannot deadlock.
 */
public final class TopicAggregator {
 /** {@code full}: the batch claimed as many rows as it may, so more may be waiting. */
 public record Batch(boolean leader,int folded,int revoked,long maxLagMs,boolean full) {}
 private static final Batch NOT_LEADER=new Batch(false,0,0,0,false);
 private final TopicAggregatorMapper db;private final TransactionTemplate tx;private final TrendGuardService risks;private final int spikeThreshold;
 private final String owner;private final long leaseMs;private final int batchSize;private final long inflightMarginMs;private final LongSupplier clock;
 public TopicAggregator(TopicAggregatorMapper db,TransactionTemplate tx,TrendGuardService risks,int spikeThreshold,String owner,long leaseMs,int batchSize,long inflightMarginMs,LongSupplier clock){
  if(owner==null || owner.isBlank() || owner.length()>128)throw new IllegalArgumentException("Aggregator owner must be 1..128 characters");
  if(leaseMs<1_000)throw new IllegalArgumentException("Aggregator lease must be at least 1000 ms");
  if(batchSize<1 || batchSize>10_000)throw new IllegalArgumentException("Aggregator batch must be in [1,10000]");
  if(inflightMarginMs<0)throw new IllegalArgumentException("In-flight margin must not be negative");
  this.db=db;this.tx=tx;this.risks=risks;this.spikeThreshold=spikeThreshold;this.owner=owner;this.leaseMs=leaseMs;this.batchSize=batchSize;this.inflightMarginMs=inflightMarginMs;this.clock=clock;
 }
 public static String defaultOwner(){
  String host;try{host=java.net.InetAddress.getLocalHost().getHostName();}catch(Exception e){host="unknown";}
  String id=host+":"+ProcessHandle.current().pid()+":"+UUID.randomUUID().toString().substring(0,8);
  return id.length()<=128?id:id.substring(id.length()-128);
 }
 public String owner(){return owner;}

 public Batch runBatch(){
  return tx.execute(status->{
   long now=clock.getAsLong();
   if(db.acquire(owner,now+leaseMs,now)!=1)return NOT_LEADER;
   var rows=db.claim(batchSize);
   record Key(String topic,long bucket) {}
   var adds=new TreeMap<Key,long[]>(Comparator.comparing(Key::topic).thenComparingLong(Key::bucket));
   var subs=new TreeMap<Key,long[]>(Comparator.comparing(Key::topic).thenComparingLong(Key::bucket));
   var gained=new TreeMap<String,TopicAggregatorMapper.Pending>();   // topic → latest event in this batch
   var folded=new ArrayList<String>();var revoked=new ArrayList<String>();
   long maxLag=0;
   for(var r:rows){
    var key=new Key(r.topic(),r.at()/60_000L*60_000L);
    long active=r.delta()==0?0:1;
    if(r.revokedAt()!=null){
     var s=subs.computeIfAbsent(key,k->new long[2]);s[0]+=r.delta();s[1]+=active;revoked.add(r.id());
    }else{
     var a=adds.computeIfAbsent(key,k->new long[2]);a[0]+=r.delta();a[1]+=active;folded.add(r.id());
     maxLag=Math.max(maxLag,now-r.at());
     if(r.delta()>0)gained.merge(r.topic(),r,(x,y)->y.at()>=x.at()?y:x);
    }
   }
   for(var e:adds.entrySet())db.add(e.getKey().topic(),e.getKey().bucket(),e.getValue()[0],e.getValue()[1]);
   for(var e:subs.entrySet())db.subtract(e.getKey().topic(),e.getKey().bucket(),e.getValue()[0],e.getValue()[1]);
   if(!folded.isEmpty() && db.markFolded(folded,now)!=folded.size())throw new IllegalStateException("claimed ledger rows changed under the batch lock");
   if(!revoked.isEmpty() && db.deleteRevoked(revoked)!=revoked.size())throw new IllegalStateException("claimed revocations changed under the batch lock");
   for(var hit:gained.values())emitSpikeIfNeeded(hit.topic(),hit.post(),hit.at());
   Long oldest=db.oldestPending();
   long watermark=now-inflightMarginMs;
   if(oldest!=null)watermark=Math.min(watermark,oldest);
   db.progress(Math.max(0,watermark),now,folded.size(),revoked.size(),maxLag);
   return new Batch(true,folded.size(),revoked.size(),maxLag,rows.size()>=batchSize);
  });
 }
 /** Runs batches until one claims nothing, this instance is not the lease holder, or {@code maxBatches} ran. Returns rows applied. */
 public int drain(int maxBatches){
  int total=0;
  for(int i=0;i<maxBatches;i++){
   var b=runBatch();
   total+=b.folded()+b.revoked();
   if(!b.leader() || b.folded()+b.revoked()==0)break;
  }
  return total;
 }
 public int drain(){return drain(Integer.MAX_VALUE);}
 /** TopicRetention's processed watermark (C-13): the recorded progress, never a live guess. 0 before the first batch. */
 public long processedWatermarkMs(){var s=db.state();return s==null?0:s.watermarkMs();}
 /**
  * Rebuilds buckets from the ledger since {@code since}: under the state row lock (no batch can run meanwhile) clears all
  * buckets (as before B1) and re-sums every row whose +delta is in a bucket now (folded, or revoked but not yet subtracted); then drains,
  * which folds pending rows and applies pending revocations. Returns the ledger events re-summed plus those drained.
  */
 public int rebuild(long since){
  Integer n=tx.execute(status->{
   db.lockState();
   db.clearBuckets();
   int events=db.refoldedEvents(since);
   db.refold(since);
   return events;
  });
  return (n==null?0:n)+drain();
 }
 public void release(){try{tx.executeWithoutResult(status->db.release(owner));}catch(Exception ignored){}}

 /**
  * Spike check for one topic that gained events in this batch: net events over the hour before its latest event, against
  * the threshold; one risk row per (topic, clock hour of the event). The batch lock makes the aggregator the only writer
  * of buckets, so the old per-topic serial lock is not needed; the unique business key still guards a lease handover.
  * Once this process knows the (topic, hour) row exists, later batches skip the scan and the insert (round-1 R9). "Known":
  * our insert committed (marked after commit, so a rolled-back batch never suppresses the real emit) or it hit the unique key.
  */
 void emitSpikeIfNeeded(String topic,long post,long at){
  if(spikeThreshold<=0 || risks==null)return;
  long hour=at/3_600_000L*3_600_000L;
  if(reported.contains(topic,hour))return;
  long net=db.topicNetSince(topic,Math.max(0,at-3_600_000L));
  if(net<spikeThreshold)return;
  String id=TrendGuardService.spikeEventId(topic,hour);
  boolean inserted=risks.recordInCurrentTx(id,topic,TrendGuardService.KIND_TOPIC_SPIKE,hour,post,"net_events_1h>="+spikeThreshold+";net="+net);
  if(!inserted)reported.add(topic,hour);
  else if(TransactionSynchronizationManager.isSynchronizationActive())
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
    @Override public void afterCommit(){reported.add(topic,hour);}
   });
 }
 private final ReportedSpikes reported=new ReportedSpikes();
 /** Per-process memory of reported (topic, clock hour) spike windows; keeps only the current and previous hour. */
 static final class ReportedSpikes {
  private final java.util.concurrent.ConcurrentHashMap<Long,Set<String>> byHour=new java.util.concurrent.ConcurrentHashMap<>();
  boolean contains(String topic,long hour){var s=byHour.get(hour);return s!=null && s.contains(topic);}
  void add(String topic,long hour){
   byHour.computeIfAbsent(hour,h->java.util.concurrent.ConcurrentHashMap.newKeySet()).add(topic);
   byHour.keySet().removeIf(h->h<hour-3_600_000L);
  }
 }
}
