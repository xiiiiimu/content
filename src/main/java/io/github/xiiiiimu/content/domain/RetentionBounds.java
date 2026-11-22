package io.github.xiiiiimu.content.domain;

/**
 * Shared bounds for the hardening C2 retention jobs: how old a row must be, how many rows one short transaction touches,
 * and how many batches one run may take (so a first run over a large backlog cannot monopolise the database).
 */
public record RetentionBounds(long retentionMs,int batchSize,int maxBatches) {
 public static final long HOUR_MS=3_600_000L,DAY_MS=86_400_000L;
 /** Margin required on top of the broker's message retention before an idempotency receipt may be forgotten. */
 public static final long BROKER_MARGIN_MS=24*HOUR_MS;
 public RetentionBounds{
  if(retentionMs<=0)throw new IllegalArgumentException("Retention must be positive: "+retentionMs);
  if(batchSize<1 || batchSize>10_000)throw new IllegalArgumentException("Retention batch size must be in [1,10000]: "+batchSize);
  if(maxBatches<1)throw new IllegalArgumentException("Retention max batches per run must be >= 1: "+maxBatches);
 }
 /**
  * C-14: a consumer receipt must outlive every redelivery the broker can still make. With CONSUME_FROM_FIRST_OFFSET a lost
  * offset replays everything the broker still stores (fileReservedTime), so receipts are kept at least that long plus a margin.
  */
 public static void requireCoversBroker(String job,long retentionMs,long brokerReservedHours){
  if(brokerReservedHours<1)throw new IllegalArgumentException("Broker reserved hours must be >= 1: "+brokerReservedHours);
  long min=brokerReservedHours*HOUR_MS+BROKER_MARGIN_MS;
  if(retentionMs<min)throw new IllegalArgumentException(job+" retention "+retentionMs+"ms must cover the broker's message retention ("
   +brokerReservedHours+"h) plus "+BROKER_MARGIN_MS+"ms margin = "+min+"ms");
 }
}
