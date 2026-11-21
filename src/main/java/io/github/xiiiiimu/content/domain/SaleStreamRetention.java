package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.RetentionMapper;
import io.github.xiiiiimu.content.infrastructure.SaleEntryRedis;

/**
 * Hardening C2 R-STREAM (C-12): acknowledged sale ingress Stream entries, their trusted {@code t:} copies, and orphan recovery
 * staging hashes.
 *
 * <p><b>Stream.</b> An entry is ACKed only after its request is committed to MySQL ({@code SaleBridgeBatch}) or after it was
 * copied into {@code material_sale_stream_quarantine}; nothing reads an acknowledged entry again. The job deletes entries
 * older than the retention (default one day) that every group was delivered and acknowledged, strictly before the oldest
 * pending id, so neither an unacknowledged entry nor anything XAUTOCLAIM may take over is touched.
 *
 * <p><b>t: copies.</b> Deleted with their entry, except for entries whose quarantine row is still HOLD/RELEASE_PENDING: the
 * release script compares {@code t:<streamId>} with the stored trusted copy and would otherwise never release, keeping the
 * campaign paused. The quarantine lookup runs <i>after</i> the candidates are read: quarantine commits before the ACK, so an
 * entry that is already acknowledged has its quarantine row visible. Copies kept that way stay until a recovery rebuilds state.
 *
 * <p><b>Stages.</b> A recovery builds {@code stage:<generation>} and renames it into the live state; a failed recovery leaves a
 * full-size hash behind. Generations below the runtime's are dropped, and the runtime's own generation once it is RUNNING
 * (its stage was installed). A newer generation (a recovery that just began) is never touched.
 *
 * <p>No SQL transaction spans Redis I/O; each Redis step is one atomic script.
 */
public final class SaleStreamRetention {
 public static final long DEFAULT_RETENTION_MS=RetentionBounds.DAY_MS;
 /** Lua unpack limits the number of arguments per script call. */
 public static final int MAX_BATCH=1000;
 public record Result(long entries,long trusted,long stages) {}
 private final RetentionMapper db;private final SaleEntryRedis redis;private final RetentionBounds bounds;
 public SaleStreamRetention(RetentionMapper db,SaleEntryRedis redis,RetentionBounds bounds){
  if(bounds.batchSize()>MAX_BATCH)throw new IllegalArgumentException("Stream retention batch size must be <= "+MAX_BATCH);
  this.db=db;this.redis=redis;this.bounds=bounds;
 }
 public Result purge(){
  long olderThan=db.nowMs()-bounds.retentionMs();
  long entries=0,trusted=0;
  for(int batch=0;batch<bounds.maxBatches();batch++){
   var ids=redis.acknowledgedBefore(olderThan,bounds.batchSize());
   if(ids.isEmpty())break;
   var keep=db.unresolvedQuarantine(ids);
   long[] n=redis.dropAcknowledged(ids,keep);
   entries+=n[0];trusted+=n[1];
   if(ids.size()<bounds.batchSize())break;
  }
  return new Result(entries,trusted,dropOrphanStages());
 }
 private long dropOrphanStages(){
  var generations=redis.stageGenerations();
  if(generations.isEmpty())return 0;
  var runtime=db.saleRuntime();
  if(runtime==null)return 0;
  long dropped=0;
  for(long g:generations)if(g<runtime.generation() || (g==runtime.generation() && "RUNNING".equals(runtime.state())))dropped+=redis.dropStage(g);
  return dropped;
 }
}
