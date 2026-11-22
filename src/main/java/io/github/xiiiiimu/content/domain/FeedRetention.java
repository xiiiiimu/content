package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.RetentionMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Hardening C2 R-FEED: SENT outbox rows, their inbox receipts and DONE delivery tasks, deleted together per event.
 *
 * <p><b>Why together, and in this order.</b> {@link DistributionInbox#accept} reads the outbox row before the receipt, and
 * {@link PipelineReconcile} re-accepts "SENT without receipt" and re-creates "PUSH/HISTORY receipt without task" from the
 * outbox payload. Deleting a DONE task while its receipt stays would make reconcile re-create it and fan the post out again.
 * Each batch therefore deletes outbox, then receipt, then task in one transaction; even a partial order (outbox gone, receipt
 * kept) could at worst add a detect-only {@code orphanTasks} report, never a repair.
 *
 * <p><b>What stays.</b> Anything not SENT (NEW/RUNNING/DEAD); SENT rows with an unknown send time (C-6); receipts younger
 * than the retention (the broker can still redeliver: see {@link RetentionBounds#requireCoversBroker}); SENT without receipt
 * and task-producing receipts without a task (reconciliation gaps); events whose task is DEAD, RUNNING, NEW or DONE too
 * recently; events with a replay audit row (FK) or a recorded conflict (manual handling).
 *
 * <p><b>Locks.</b> Candidates come from a plain read of {@code ix_outbox_retry}. Each batch is one READ COMMITTED transaction
 * that re-checks eligibility with {@code SELECT ... FOR UPDATE} (record locks on the selected outbox, receipt and task rows,
 * no gap locks) and deletes those rows by primary/unique key. Live writers never touch SENT outbox rows, existing receipts or
 * DONE tasks (relay claims NEW/RUNNING, replay only DEAD, the worker only NEW/RUNNING), so contention is limited to a
 * redelivered message for the same old event, which waits at most one batch.
 */
public final class FeedRetention {
 public static final long DEFAULT_RETENTION_MS=7*RetentionBounds.DAY_MS;
 public record Result(long events,long receipts,long tasks) {}
 private static final LocalDateTime START=LocalDateTime.of(1970,1,1,0,0);
 private final RetentionMapper db;private final TransactionTemplate tx;private final RetentionBounds bounds;
 public FeedRetention(RetentionMapper db,TransactionTemplate tx,RetentionBounds bounds,long brokerReservedHours){
  RetentionBounds.requireCoversBroker("Feed",bounds.retentionMs(),brokerReservedHours);
  this.db=db;this.tx=tx;this.bounds=bounds;
 }
 public Result purge(){
  long cutoff=db.nowMs()-bounds.retentionMs();
  long events=0,receipts=0,tasks=0;
  LocalDateTime afterAt=START;long afterId=0;
  for(int batch=0;batch<bounds.maxBatches();batch++){
   var candidates=db.outboxCandidates(cutoff,afterAt,afterId,bounds.batchSize());
   if(candidates.isEmpty())break;
   var last=candidates.get(candidates.size()-1);afterAt=last.retryAt();afterId=last.id();
   List<Long> ids=candidates.stream().map(RetentionMapper.OutboxKey::id).toList();
   long[] n=tx.execute(status->{
    var eligible=db.lockFeedEvents(ids,cutoff);
    if(eligible.isEmpty())return new long[3];
    return new long[]{db.deleteOutbox(eligible),db.deleteInbox(eligible),db.deleteDoneTasks(eligible)};
   });
   events+=n[0];receipts+=n[1];tasks+=n[2];
   if(candidates.size()<bounds.batchSize())break;
  }
  return new Result(events,receipts,tasks);
 }
}
