package io.github.xiiiiimu.content.domain;
import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

public final class DeliveryStore {
 private final FeedMapper feed;private final DeliveryMapper tasks;private final TransactionTemplate tx;private final int max;private final long lease;
 public DeliveryStore(FeedMapper feed,DeliveryMapper tasks,TransactionTemplate template,int max,long lease){
  if(max<1 || max>100 || lease<1000 || lease>300000)throw new IllegalArgumentException("Invalid task lease/retry configuration");
  this.feed=feed;this.tasks=tasks;this.max=max;this.lease=lease;tx=new TransactionTemplate(template.getTransactionManager());tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);
 }
 public DeliveryLease claim(){return tx.execute(status->{var control=feed.gate();if(control==null || !java.util.Set.of("PRIMARY","READY").contains(control.state()))return null;
  // Split NEW/RUNNING scans so OR cannot force a PRIMARY walk across the DONE heap.
  for(var expired:tasks.exhaustedNew(max))if(tasks.dead(expired.taskId(),expired.token(),max)==1)org.slf4j.LoggerFactory.getLogger(DeliveryStore.class).error("Delivery DEAD taskId={} eventId={} attempt={}",expired.taskId(),expired.eventId(),expired.attempt());
  for(var expired:tasks.exhaustedRunning(max))if(tasks.dead(expired.taskId(),expired.token(),max)==1)org.slf4j.LoggerFactory.getLogger(DeliveryStore.class).error("Delivery DEAD taskId={} eventId={} attempt={}",expired.taskId(),expired.eventId(),expired.attempt());
  var row=tasks.candidateNew(max);if(row==null)row=tasks.candidateRunning(max);if(row==null)return null;
  if(tasks.claim(row.taskId(),row.token(),row.authorId(),control.generation(),lease)!=1)throw new IllegalStateException("Locked task changed");return tasks.lock(row.taskId());
 });}
 public boolean valid(DeliveryLease job){return tasks.valid(job.taskId(),job.token());}
 public boolean progress(DeliveryLease job,long follower,long post,boolean done){return Boolean.TRUE.equals(tx.execute(status->{if(!gate(job))return false;tasks.lock(job.taskId());return tasks.progress(job.taskId(),job.token(),follower,post,done,lease,job.generation())==1;}));}
 public boolean fail(DeliveryLease job,Exception cause){return Boolean.TRUE.equals(tx.execute(status->{if(!gate(job))return false;var row=tasks.lock(job.taskId());if(row==null || row.token()!=job.token())return false;
  String message=cause.getClass().getSimpleName()+": "+cause.getMessage();message=message.substring(0,Math.min(1024,message.length()));
  boolean dead=row.attempt()>=max;int changed=tasks.fail(job.taskId(),job.token(),dead?"DEAD":"NEW",message,Math.min(60000,1000L<<(Math.min(20,row.attempt()-1))),job.generation());
  if(changed==1 && dead)org.slf4j.LoggerFactory.getLogger(DeliveryStore.class).error("Delivery DEAD taskId={} eventId={} error={}",job.taskId(),job.eventId(),message);return changed==1;
 }));}
 private boolean gate(DeliveryLease job){var c=feed.gate();return c!=null && java.util.Set.of("PRIMARY","READY").contains(c.state()) && java.util.Objects.equals(job.generation(),c.generation());}
 public boolean replay(long id,String operator,String reason){
  if(id<=0 || operator==null || operator.isBlank() || operator.length()>255 || reason==null || reason.isBlank() || reason.length()>512)throw new IllegalArgumentException("OS operator and bounded replay reason required");
  return Boolean.TRUE.equals(tx.execute(status->{RelationshipService.requireWritable(feed.gate());var row=tasks.lock(id);if(row==null || tasks.replay(id,row.token())!=1)return false;
   tasks.audit(id,row.eventId(),row.token(),Math.addExact(row.token(),1),row.attempt(),operator,reason);return true;
  }));
 }
}
