package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.RetentionMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/**
 * Hardening C2 R-NOTIFY: notifications and trend risk events older than the retention (default 180 days), and feedback
 * notify log rows of the same age whose notification is already gone (C-4: an author may resolve the same feedback again at
 * any time, and only the log row or the notification's dedupe key stops a second notification).
 *
 * <p>Notifications have no time index; ids are assigned at insert time, so the job walks the primary key from the oldest id
 * and stops at the first row inside the retention. A rare old row behind a younger id waits until that younger row expires.
 * Risk events use {@code ix_risk_time}. The feedback notify log is small and has no time index; its DELETE scans the primary
 * key with LIMIT. Every batch is one READ COMMITTED transaction that record-locks only the rows it deletes; live writers insert
 * new rows (and mark rows read), so they never wait on expired rows longer than one batch.
 */
public final class NotificationRetention {
 public static final long DEFAULT_RETENTION_MS=180*RetentionBounds.DAY_MS;
 public record Result(long notifications,long feedbackLog,long riskEvents) {}
 private final RetentionMapper db;private final TransactionTemplate tx;private final RetentionBounds bounds;
 public NotificationRetention(RetentionMapper db,TransactionTemplate tx,RetentionBounds bounds){this.db=db;this.tx=tx;this.bounds=bounds;}
 public Result purge(){
  long cutoff=db.nowMs()-bounds.retentionMs();
  long notifications=0;long after=0;int used=0;
  while(used<bounds.maxBatches()){
   var window=db.notificationWindow(after,bounds.batchSize());
   List<Long> expired=new java.util.ArrayList<>();
   for(var row:window){if(row.at()>=cutoff)break;expired.add(row.id());}
   if(expired.isEmpty())break;
   used++;
   Integer n=tx.execute(status->db.deleteNotifications(expired,cutoff));
   notifications+=n==null?0:n;
   after=expired.get(expired.size()-1);
   if(expired.size()<window.size() || window.size()<bounds.batchSize())break;
  }
  // After notifications, so a log row whose notification expired in this run can follow it.
  long feedbackLog=drain(()->db.deleteFeedbackNotifyLog(cutoff,bounds.batchSize()));
  long riskEvents=drain(()->db.deleteRiskEvents(cutoff,bounds.batchSize()));
  return new Result(notifications,feedbackLog,riskEvents);
 }
 /** Each table gets its own maxBatches budget per run. */
 private long drain(java.util.function.IntSupplier batch){
  long total=0;
  for(int i=0;i<bounds.maxBatches();i++){
   Integer n=tx.execute(status->batch.getAsInt());
   int deleted=n==null?0:n;total+=deleted;
   if(deleted<bounds.batchSize())break;
  }
  return total;
 }
}
