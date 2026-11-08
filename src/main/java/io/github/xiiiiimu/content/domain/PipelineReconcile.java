package io.github.xiiiiimu.content.domain;

import org.springframework.jdbc.core.JdbcTemplate;

/** Operator-facing Outbox/Inbox/Task reconciliation. Read-only sampling against MySQL authority. */
public final class PipelineReconcile {
 public record Report(long sentWithoutInbox,long pushWithoutTask,long orphanTasks,long offlineStillInStats) {}
 private final JdbcTemplate db;
 public PipelineReconcile(JdbcTemplate db){this.db=db;}
 public Report scan(){
  long missingInbox=nz(db.queryForObject("""
   SELECT COUNT(*) FROM outbox_events o
   WHERE o.status='SENT' AND o.event_type IN('PostPublished','FollowChanged','FeedModeChanged','PostOfflined')
     AND NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=o.id)
   """,Long.class));
  long missingTasks=nz(db.queryForObject("""
   SELECT COUNT(*) FROM consumer_inbox i
   WHERE i.decision='PUSH_TASK' AND NOT EXISTS(SELECT 1 FROM feed_delivery_tasks t WHERE t.event_id=i.event_id)
   """,Long.class));
  long orphanTasks=nz(db.queryForObject("""
   SELECT COUNT(*) FROM feed_delivery_tasks t
   WHERE NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=t.event_id)
   """,Long.class));
  long offlineStats=nz(db.queryForObject("""
   SELECT COUNT(*) FROM post_stats s JOIN posts p ON p.id=s.post_id WHERE p.status='OFFLINE'
   """,Long.class));
  return new Report(missingInbox,missingTasks,orphanTasks,offlineStats);
 }
 private static long nz(Long v){return v==null?0:v;}
}
