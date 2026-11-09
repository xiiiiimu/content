package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.infrastructure.InboxMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Outbox/Inbox/Task reconciliation.
 *
 * <p>Frozen detection scope (per repair() call):
 * <ul>
 *   <li>SENT outbox rows (PostPublished|FollowChanged|FeedModeChanged|PostOfflined) without consumer_inbox</li>
 *   <li>Inbox decisions PUSH_TASK|HISTORY_TASK without feed_delivery_tasks</li>
 *   <li>post_stats rows for OFFLINE posts</li>
 *   <li>orphanTasks (non-RUNNING task without inbox) are <b>detected only</b> — never deleted/rewritten</li>
 * </ul>
 *
 * <p>Repair rules (reuse production idempotency + lease protocol):
 * <ul>
 *   <li>Inbox gaps → DistributionInbox.accept (INSERT IGNORE receipt + createTask); duplicates are no-ops</li>
 *   <li>Task gaps → createTaskIgnore (unique event_id); never UPDATE/DELETE RUNNING leases or cursors</li>
 *   <li>Offline stats → DELETE matching post_stats only</li>
 *   <li>Gap id lists are frozen at repair start; concurrent new SENT/writes after the freeze are left for the next scan</li>
 * </ul>
 */
public final class PipelineReconcile {
 public record Report(long sentWithoutInbox,long pushWithoutTask,long historyWithoutTask,long orphanTasks,long offlineStillInStats) {}
 public record RepairResult(Report before,Report after,int inboxRepaired,int tasksRepaired,int statsCleared,List<Long> frozenInboxGaps,List<Long> frozenTaskGaps) {}
 private final JdbcTemplate db;private final DistributionInbox inbox;private final InboxMapper tasks;private final TransactionTemplate tx;private final ObjectMapper json;private final PipelineObserver observer;
 public PipelineReconcile(JdbcTemplate db,DistributionInbox inbox,InboxMapper tasks,TransactionTemplate tx,ObjectMapper json,PipelineObserver observer){
  this.db=db;this.inbox=inbox;this.tasks=tasks;this.tx=tx;this.json=json;this.observer=observer==null?new PipelineObserver(){}:observer;
 }
 public Report scan(){
  long missingInbox=nz(db.queryForObject("""
   SELECT COUNT(*) FROM outbox_events o
   WHERE o.status='SENT' AND o.event_type IN('PostPublished','FollowChanged','FeedModeChanged','PostOfflined')
     AND NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=o.id)
   """,Long.class));
  long missingPush=nz(db.queryForObject("""
   SELECT COUNT(*) FROM consumer_inbox i
   WHERE i.decision='PUSH_TASK' AND NOT EXISTS(SELECT 1 FROM feed_delivery_tasks t WHERE t.event_id=i.event_id)
   """,Long.class));
  long missingHistory=nz(db.queryForObject("""
   SELECT COUNT(*) FROM consumer_inbox i
   WHERE i.decision='HISTORY_TASK' AND NOT EXISTS(SELECT 1 FROM feed_delivery_tasks t WHERE t.event_id=i.event_id)
   """,Long.class));
  long orphanTasks=nz(db.queryForObject("""
   SELECT COUNT(*) FROM feed_delivery_tasks t
   WHERE t.state<>'RUNNING' AND NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=t.event_id)
   """,Long.class));
  long offlineStats=nz(db.queryForObject("""
   SELECT COUNT(*) FROM post_stats s JOIN posts p ON p.id=s.post_id WHERE p.status='OFFLINE'
   """,Long.class));
  return new Report(missingInbox,missingPush,missingHistory,orphanTasks,offlineStats);
 }
 public RepairResult repair(){
  // Freeze gap ids once so concurrent worker/inbox writes during repair are not silently dropped from this batch.
  List<Long> frozenInbox=db.queryForList("""
   SELECT o.id FROM outbox_events o
   WHERE o.status='SENT' AND o.event_type IN('PostPublished','FollowChanged','FeedModeChanged','PostOfflined')
     AND NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=o.id)
   ORDER BY o.id LIMIT 100
   """,Long.class);
  List<Map<String,Object>> frozenTasks=db.queryForList("""
   SELECT i.event_id,i.decision,o.payload FROM consumer_inbox i
   JOIN outbox_events o ON o.id=i.event_id
   WHERE i.decision IN('PUSH_TASK','HISTORY_TASK')
     AND NOT EXISTS(SELECT 1 FROM feed_delivery_tasks t WHERE t.event_id=i.event_id)
   ORDER BY i.event_id LIMIT 100
   """);
  List<Long> frozenTaskIds=new ArrayList<>();
  for(var row:frozenTasks)frozenTaskIds.add(((Number)row.get("event_id")).longValue());
  Report before=scan();
  int inboxRepaired=0,tasksRepaired=0;
  for(long id:frozenInbox){
   String type=db.queryForObject("SELECT event_type FROM outbox_events WHERE id=?",String.class,id);
   String payload=db.queryForObject("SELECT payload FROM outbox_events WHERE id=?",String.class,id);
   observer.beforeReconcileInbox(id);
   try{inbox.accept(payload,Long.toString(id),type);inboxRepaired++;}
   catch(Exception e){org.slf4j.LoggerFactory.getLogger(PipelineReconcile.class).warn("reconcile inbox skip eventId={} reason={}",id,e.toString());}
  }
  for(var row:frozenTasks){
   long id=((Number)row.get("event_id")).longValue();String decision=(String)row.get("decision");String payload=(String)row.get("payload");
   observer.beforeReconcileTask(id);
   Integer added=tx.execute(status->{
    try{
     JsonNode body=json.readTree(payload);
     if("PUSH_TASK".equals(decision)){
      return tasks.createTaskIgnore(id,body.path("authorId").asLong(),body.path("deliveryEpoch").asLong(),"PUBLISH",body.path("postId").asLong(),null,null,payload);
     }
     if("HISTORY_TASK".equals(decision)){
      return tasks.createTaskIgnore(id,body.path("authorId").asLong(),0,"BACKFILL",null,body.path("followerId").asLong(),body.path("followEpoch").asLong(),payload);
     }
    }catch(Exception e){org.slf4j.LoggerFactory.getLogger(PipelineReconcile.class).warn("reconcile task skip eventId={} reason={}",id,e.toString());}
    return 0;
   });
   if(added!=null && added>0)tasksRepaired+=added;
  }
  int statsCleared=db.update("DELETE s FROM post_stats s JOIN posts p ON p.id=s.post_id WHERE p.status='OFFLINE'");
  return new RepairResult(before,scan(),inboxRepaired,tasksRepaired,statsCleared,List.copyOf(frozenInbox),List.copyOf(frozenTaskIds));
 }
 private static long nz(Long v){return v==null?0:v;}
}
