package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.infrastructure.InboxMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;

/**
 * Outbox/Inbox/Task reconciliation.
 * scan() is read-only. repair() is idempotent: only inserts missing NEW rows / cleans offline stats,
 * never touches RUNNING leases or worker cursors.
 */
public final class PipelineReconcile {
 public record Report(long sentWithoutInbox,long pushWithoutTask,long historyWithoutTask,long orphanTasks,long offlineStillInStats) {}
 public record RepairResult(Report before,Report after,int inboxRepaired,int tasksRepaired,int statsCleared) {}
 private final JdbcTemplate db;private final DistributionInbox inbox;private final InboxMapper tasks;private final TransactionTemplate tx;private final ObjectMapper json;
 public PipelineReconcile(JdbcTemplate db,DistributionInbox inbox,InboxMapper tasks,TransactionTemplate tx,ObjectMapper json){
  this.db=db;this.inbox=inbox;this.tasks=tasks;this.tx=tx;this.json=json;
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
  Report before=scan();
  int inboxRepaired=0,tasksRepaired=0,statsCleared=0;
  List<Map<String,Object>> missing=db.queryForList("""
   SELECT o.id,o.event_type,o.payload FROM outbox_events o
   WHERE o.status='SENT' AND o.event_type IN('PostPublished','FollowChanged','FeedModeChanged','PostOfflined')
     AND NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=o.id)
   ORDER BY o.id LIMIT 100
   """);
  for(var row:missing){
   long id=((Number)row.get("id")).longValue();String type=(String)row.get("event_type");String payload=(String)row.get("payload");
   try{inbox.accept(payload,Long.toString(id),type);inboxRepaired++;}
   catch(Exception e){org.slf4j.LoggerFactory.getLogger(PipelineReconcile.class).warn("reconcile inbox skip eventId={} reason={}",id,e.toString());}
  }
  List<Map<String,Object>> gaps=db.queryForList("""
   SELECT i.event_id,i.decision,o.payload FROM consumer_inbox i
   JOIN outbox_events o ON o.id=i.event_id
   WHERE i.decision IN('PUSH_TASK','HISTORY_TASK')
     AND NOT EXISTS(SELECT 1 FROM feed_delivery_tasks t WHERE t.event_id=i.event_id)
   ORDER BY i.event_id LIMIT 100
   """);
  for(var row:gaps){
   long id=((Number)row.get("event_id")).longValue();String decision=(String)row.get("decision");String payload=(String)row.get("payload");
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
  statsCleared=db.update("DELETE s FROM post_stats s JOIN posts p ON p.id=s.post_id WHERE p.status='OFFLINE'");
  return new RepairResult(before,scan(),inboxRepaired,tasksRepaired,statsCleared);
 }
 private static long nz(Long v){return v==null?0:v;}
}
