package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SQL for the hardening C2 retention jobs. Every cutoff is epoch milliseconds from the database clock ({@link #nowMs()});
 * DATETIME columns are compared through FROM_UNIXTIME in the same session time zone as CURRENT_TIMESTAMP that wrote them.
 * Candidate scans are plain (non-locking) reads; eligibility is re-checked under FOR UPDATE inside each batch transaction.
 */
@Mapper
public interface RetentionMapper {
 String IDS="<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>";
 String CUT="FROM_UNIXTIME(#{cutoff}/1000.0)";

 @Select("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)") long nowMs();

 // ---- feed: outbox -> inbox -> DONE task ----
 record OutboxKey(long id,LocalDateTime retryAt) {}
 /** SENT rows only. next_retry_at <= every claim <= sent_at, so next_retry_at < cutoff is necessary for sent_at < cutoff and the scan stays on ix_outbox_retry. */
 @Select("SELECT id,next_retry_at AS retryAt FROM outbox_events FORCE INDEX(ix_outbox_retry) WHERE status='SENT' AND next_retry_at<"+CUT
  +" AND (next_retry_at>#{afterAt} OR (next_retry_at=#{afterAt} AND id>#{afterId})) ORDER BY next_retry_at,id LIMIT #{n}")
 List<OutboxKey> outboxCandidates(@Param("cutoff")long cutoff,@Param("afterAt")LocalDateTime afterAt,@Param("afterId")long afterId,@Param("n")int n);
 /**
  * Locks and returns the events whose outbox row, inbox receipt and (if any) DONE task may all go:
  * SENT and received before the cutoff; a task-producing decision must have its task DONE before the cutoff
  * (a missing task is a reconciliation gap and is kept); no replay audit and no recorded conflict.
  */
 @Select("<script>SELECT o.id FROM outbox_events o JOIN consumer_inbox i ON i.consumer_name='feed-v1' AND i.event_id=o.id"
  +" LEFT JOIN feed_delivery_tasks t ON t.event_id=o.id"
  +" WHERE o.id IN "+IDS+" AND o.status='SENT' AND o.sent_at&lt;"+CUT+" AND i.processed_at&lt;"+CUT
  +" AND ((t.id IS NULL AND i.decision NOT IN ('PUSH_TASK','HISTORY_TASK')) OR (t.state='DONE' AND COALESCE(t.completed_at,t.last_attempt_at)&lt;"+CUT+"))"
  +" AND NOT EXISTS(SELECT 1 FROM outbox_replay_audit a WHERE a.event_id=o.id)"
  +" AND NOT EXISTS(SELECT 1 FROM feed_event_conflicts c WHERE c.consumer_name='feed-v1' AND c.event_id=o.id)"
  +" ORDER BY o.id FOR UPDATE</script>")
 List<Long> lockFeedEvents(@Param("ids")List<Long> ids,@Param("cutoff")long cutoff);
 @Delete("<script>DELETE FROM outbox_events WHERE status='SENT' AND id IN "+IDS+"</script>") int deleteOutbox(@Param("ids")List<Long> ids);
 @Delete("<script>DELETE FROM consumer_inbox WHERE consumer_name='feed-v1' AND event_id IN "+IDS+"</script>") int deleteInbox(@Param("ids")List<Long> ids);
 @Delete("<script>DELETE FROM feed_delivery_tasks WHERE state='DONE' AND event_id IN "+IDS+"</script>") int deleteDoneTasks(@Param("ids")List<Long> ids);

 // ---- follow_history: stale epochs ----
 record HistoryKey(long follower,long followee,long epoch,long post) {}
 @Select("SELECT follower_id AS follower,followee_id AS followee,follow_epoch AS epoch,post_id AS post FROM follow_history"
  +" WHERE (follower_id,followee_id,follow_epoch,post_id)>(#{k.follower},#{k.followee},#{k.epoch},#{k.post}) ORDER BY follower_id,followee_id,follow_epoch,post_id LIMIT #{n}")
 List<HistoryKey> historyWindow(@Param("k")HistoryKey after,@Param("n")int n);
 /** A follow epoch is dead once the relationship moved to a later epoch or is no longer FOLLOWING (re-following always bumps the epoch). */
 @Delete("<script>DELETE h FROM follow_history h JOIN follows f ON f.follower_id=h.follower_id AND f.followee_id=h.followee_id"
  +" WHERE (f.follow_epoch&gt;h.follow_epoch OR f.status&lt;&gt;'FOLLOWING') AND (h.follower_id,h.followee_id,h.follow_epoch,h.post_id) IN"
  +"<foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k.follower},#{k.followee},#{k.epoch},#{k.post})</foreach></script>")
 int deleteStaleHistory(@Param("keys")List<HistoryKey> keys);

 // ---- commerce: SENT dispatch + consume receipt ----
 record DispatchKey(String id,long at) {}
 /** available_at_ms <= claim <= sent_at_ms, so available_at_ms < cutoff is necessary and the scan stays on ix_sale_dispatch_pending_due. */
 @Select("SELECT reservation_id AS id,available_at_ms AS at FROM material_sale_dispatch FORCE INDEX(ix_sale_dispatch_pending_due)"
  +" WHERE state='SENT' AND available_at_ms<#{cutoff} AND (available_at_ms>#{afterAt} OR (available_at_ms=#{afterAt} AND reservation_id>#{afterId}))"
  +" ORDER BY available_at_ms,reservation_id LIMIT #{n}")
 List<DispatchKey> dispatchCandidates(@Param("cutoff")long cutoff,@Param("afterAt")long afterAt,@Param("afterId")String afterId,@Param("n")int n);
 /** Request terminal, dispatch SENT before the cutoff, and its receipt (if any) also older: dispatch and receipt go together or not at all. */
 @Select("<script>SELECT d.reservation_id FROM material_sale_dispatch d JOIN material_purchase_requests r ON r.reservation_id=d.reservation_id"
  +" LEFT JOIN material_sale_consume_receipts c ON c.reservation_id=d.reservation_id"
  +" WHERE d.reservation_id IN "+IDS+" AND d.state='SENT' AND d.sent_at_ms&lt;#{cutoff} AND r.state IN ('SUCCEEDED','REJECTED')"
  +" AND (c.reservation_id IS NULL OR c.created_at_ms&lt;#{cutoff}) ORDER BY d.reservation_id FOR UPDATE OF d,c</script>")
 List<String> lockSaleRows(@Param("ids")List<String> ids,@Param("cutoff")long cutoff);
 @Delete("<script>DELETE FROM material_sale_consume_receipts WHERE reservation_id IN "+IDS+"</script>") int deleteReceipts(@Param("ids")List<String> ids);
 @Delete("<script>DELETE FROM material_sale_dispatch WHERE state='SENT' AND reservation_id IN "+IDS+"</script>") int deleteDispatch(@Param("ids")List<String> ids);
 @Select("<script>SELECT stream_id FROM material_sale_stream_quarantine WHERE disposition IN ('HOLD','RELEASE_PENDING') AND stream_id IN "+IDS+"</script>")
 List<String> unresolvedQuarantine(@Param("ids")List<String> ids);
 record Runtime(String state,long generation) {}
 @Select("SELECT state,generation FROM material_sale_runtime WHERE id=1") Runtime saleRuntime();

 // ---- notifications, feedback notify log, risk events ----
 record Stamped(long id,long at) {}
 @Select("SELECT id,created_at_ms AS at FROM notifications WHERE id>#{after} ORDER BY id LIMIT #{n}")
 List<Stamped> notificationWindow(@Param("after")long after,@Param("n")int n);
 @Delete("<script>DELETE FROM notifications WHERE created_at_ms&lt;#{cutoff} AND id IN "+IDS+"</script>")
 int deleteNotifications(@Param("ids")List<Long> ids,@Param("cutoff")long cutoff);
 /** C-4: a feedback notify log row goes only once its notification is gone, so a repeated resolve cannot notify twice. */
 @Delete("DELETE FROM feedback_notify_log WHERE created_at_ms<#{cutoff} AND NOT EXISTS(SELECT 1 FROM notifications n WHERE n.recipient_user_id=feedback_notify_log.account_id"
  +" AND n.dedupe_key=CONCAT('feedback-ready:',feedback_notify_log.account_id,':',feedback_notify_log.feedback_comment_id,':',feedback_notify_log.new_version_id))"
  +" ORDER BY account_id,feedback_comment_id,new_version_id LIMIT #{n}")
 int deleteFeedbackNotifyLog(@Param("cutoff")long cutoff,@Param("n")int n);
 @Delete("DELETE FROM trend_risk_events WHERE occurred_at_ms<#{cutoff} ORDER BY occurred_at_ms LIMIT #{n}")
 int deleteRiskEvents(@Param("cutoff")long cutoff,@Param("n")int n);

 // ---- C-6 evidence: rows kept because their completion time is unknown ----
 @Select("SELECT COUNT(*) FROM outbox_events WHERE status='SENT' AND sent_at IS NULL") long sentOutboxWithoutTime();
 @Select("SELECT COUNT(*) FROM material_sale_dispatch WHERE state='SENT' AND sent_at_ms IS NULL") long sentDispatchWithoutTime();
 @Select("SELECT COUNT(*) FROM material_sale_consume_receipts c WHERE NOT EXISTS(SELECT 1 FROM material_sale_dispatch d WHERE d.reservation_id=c.reservation_id)") long receiptsWithoutDispatch();
}
