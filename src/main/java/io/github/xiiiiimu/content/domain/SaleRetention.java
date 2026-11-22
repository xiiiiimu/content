package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.RetentionMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/**
 * Hardening C2 R-SALE: SENT MQ dispatch rows and consume receipts of terminal purchase requests, deleted together.
 *
 * <p>A dispatch row goes only when it was SENT before the cutoff, its request is SUCCEEDED/REJECTED, and its receipt (if any)
 * is also older than the cutoff; the receipt goes in the same transaction. Keeping the pair together means no receipt is left
 * without the dispatch row that finds it. Kept: PENDING/SENDING, SENT with unknown ACK time (C-6), requests still PROCESSING,
 * receipts without a dispatch row (pre-V20 data). Purchase requests, orders and refunds are business records and never touched.
 *
 * <p>Safety: settlement is idempotent on the request state ({@code SaleService} returns once the request is not PROCESSING),
 * the receipt is a second guard. If an old Stream entry were processed again the bridge would re-enqueue the request
 * ({@code SaleBridgeBatch} enqueues known requests): one extra MQ message that the consumer ignores. Receipts are kept at least
 * as long as the broker can redeliver ({@link RetentionBounds#requireCoversBroker}).
 *
 * <p>Locks: candidates come from a plain read of {@code ix_sale_dispatch_pending_due} ({@code available_at_ms} is never later
 * than the send). Each batch is one READ COMMITTED transaction that re-checks with {@code FOR UPDATE OF} the dispatch and
 * receipt rows only (request rows are read, not locked) and deletes them by primary key. Live dispatch work only touches
 * PENDING/SENDING rows, so there is no contention with the send path.
 */
public final class SaleRetention {
 public static final long DEFAULT_RETENTION_MS=7*RetentionBounds.DAY_MS;
 public record Result(long dispatch,long receipts) {}
 private final RetentionMapper db;private final TransactionTemplate tx;private final RetentionBounds bounds;
 public SaleRetention(RetentionMapper db,TransactionTemplate tx,RetentionBounds bounds,long brokerReservedHours){
  RetentionBounds.requireCoversBroker("Sale",bounds.retentionMs(),brokerReservedHours);
  this.db=db;this.tx=tx;this.bounds=bounds;
 }
 public Result purge(){
  long cutoff=db.nowMs()-bounds.retentionMs();
  long dispatch=0,receipts=0;long afterAt=-1;String afterId="";
  for(int batch=0;batch<bounds.maxBatches();batch++){
   var candidates=db.dispatchCandidates(cutoff,afterAt,afterId,bounds.batchSize());
   if(candidates.isEmpty())break;
   var last=candidates.get(candidates.size()-1);afterAt=last.at();afterId=last.id();
   List<String> ids=candidates.stream().map(RetentionMapper.DispatchKey::id).toList();
   long[] n=tx.execute(status->{
    var eligible=db.lockSaleRows(ids,cutoff);
    if(eligible.isEmpty())return new long[2];
    long r=db.deleteReceipts(eligible);
    return new long[]{db.deleteDispatch(eligible),r};
   });
   dispatch+=n[0];receipts+=n[1];
   if(candidates.size()<bounds.batchSize())break;
  }
  return new Result(dispatch,receipts);
 }
}
