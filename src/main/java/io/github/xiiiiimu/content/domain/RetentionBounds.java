package io.github.xiiiiimu.content.domain;

/**
 * Shared bounds for the hardening C2 retention jobs: how old a row must be, how many rows one short transaction touches,
 * and how many batches one run may take (so a first run over a large backlog cannot monopolise the database).
 */
public record RetentionBounds(long retentionMs,int batchSize,int maxBatches) {
 public static final long HOUR_MS=3_600_000L,DAY_MS=86_400_000L;
 public RetentionBounds{
  if(retentionMs<=0)throw new IllegalArgumentException("Retention must be positive: "+retentionMs);
  if(batchSize<1 || batchSize>10_000)throw new IllegalArgumentException("Retention batch size must be in [1,10000]: "+batchSize);
  if(maxBatches<1)throw new IllegalArgumentException("Retention max batches per run must be >= 1: "+maxBatches);
 }
}
