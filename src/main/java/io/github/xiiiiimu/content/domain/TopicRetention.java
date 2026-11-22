package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.InteractionMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention purge for topic_minute_buckets and topic_event_ledger (round-1 R2).
 * <p>Rows older than {@code retentionMs} are deleted oldest-first in batches of {@code batchSize}, each batch in its own
 * short READ COMMITTED transaction: only the deleted rows are record-locked (no gap locks), for the duration of one batch.
 * Live writes touch the current minute, and an unlike touches its own like's bucket and ledger row. They can only meet the
 * purge on rows that are already past retention, and wait at most one batch.
 * <p>Retention must cover the longest trending window (24h) plus at least one hour of margin, so a window read never
 * misses rows. The default is 48h.
 * <p>Hardening C2 (C-13) / B1: ledger rows are deleted only before the <i>processed watermark</i>: every ledger event with
 * {@code occurred_at_ms} below it has been folded into the minute buckets. The application wires
 * {@link TopicAggregator#processedWatermarkMs()} (the aggregator's recorded progress, which already deducts the in-flight
 * margin), so the purge can never drop an event the aggregator has not counted. {@link #ALL_PROCESSED} (no watermark) is
 * for callers without asynchronous aggregation. Buckets are the aggregated output and are not bounded by the watermark.
 */
public final class TopicRetention {
 public static final long LONGEST_WINDOW_MS=86_400_000L;
 public static final long MIN_MARGIN_MS=3_600_000L;
 public static final long DEFAULT_RETENTION_MS=172_800_000L;
 public record Result(long buckets,long ledger) {}
 /** No aggregation lag: every committed ledger event is already in its bucket. */
 public static final java.util.function.LongSupplier ALL_PROCESSED=()->Long.MAX_VALUE;
 private final InteractionMapper db;private final TransactionTemplate tx;private final long retentionMs;private final int batchSize;
 private final java.util.function.LongSupplier processedWatermarkMs;
 public TopicRetention(InteractionMapper db,TransactionTemplate tx,long retentionMs,int batchSize){this(db,tx,retentionMs,batchSize,ALL_PROCESSED);}
 public TopicRetention(InteractionMapper db,TransactionTemplate tx,long retentionMs,int batchSize,java.util.function.LongSupplier processedWatermarkMs){
  if(retentionMs<LONGEST_WINDOW_MS+MIN_MARGIN_MS)
   throw new IllegalArgumentException("Topic retention "+retentionMs+"ms must cover the longest trending window ("+LONGEST_WINDOW_MS+"ms) plus "+MIN_MARGIN_MS+"ms margin");
  if(batchSize<1 || batchSize>10_000)throw new IllegalArgumentException("Topic retention batch size must be in [1,10000]");
  if(processedWatermarkMs==null)throw new IllegalArgumentException("Processed watermark supplier is required");
  this.db=db;this.tx=tx;this.retentionMs=retentionMs;this.batchSize=batchSize;this.processedWatermarkMs=processedWatermarkMs;
 }
 public long retentionMs(){return retentionMs;}
 public Result purge(long nowMs){
  long cutoff=nowMs-retentionMs;
  long buckets=drain(()->db.purgeBuckets(cutoff,batchSize));
  long ledgerCutoff=Math.min(cutoff,processedWatermarkMs.getAsLong());
  long ledger=drain(()->db.purgeLedger(ledgerCutoff,batchSize));
  return new Result(buckets,ledger);
 }
 private long drain(java.util.function.IntSupplier batch){
  long total=0;
  while(true){
   Integer n=tx.execute(status->batch.getAsInt());
   int deleted=n==null?0:n;total+=deleted;
   if(deleted<batchSize)return total;
  }
 }
}
