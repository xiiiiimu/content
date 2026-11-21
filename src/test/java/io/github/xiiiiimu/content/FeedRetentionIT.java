package io.github.xiiiiimu.content;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.DeliveryMapper;
import io.github.xiiiiimu.content.infrastructure.InteractionMapper;
import io.github.xiiiiimu.content.infrastructure.RetentionMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

/**
 * Hardening C2: R-FEED (outbox -> inbox -> DONE task per event), R-FOLLOW (stale follow epochs), the DONE completion time
 * (C-5) and the B1 "processed watermark" bound on the topic ledger purge (C-13).
 */
@SpringBootTest @Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class FeedRetentionIT {
 @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4").withExposedPorts(6379).withCommand("redis-server","--maxmemory-policy","noeviction");
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){
  r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
  r.add("content.feed.enabled",()->"true");r.add("content.feed.redis-uri",()->"redis://"+REDIS.getHost()+":"+REDIS.getMappedPort(6379));
  r.add("content.feed.cursor-key",()->"cursor-test-key-32-bytes-minimum-00001");r.add("content.feed.identity-key",()->"identity-test-key-32-bytes-minimum-0001");
 }
 @Autowired JdbcTemplate db;@Autowired RetentionMapper retention;@Autowired TransactionTemplate tx;@Autowired DeliveryMapper deliveries;@Autowired InteractionMapper interactions;
 static final AtomicLong AGG=new AtomicLong(900_000);
 static final long DAY=86_400_000L,HOUR=3_600_000L;

 @BeforeEach void reset(){
  for(String t:List.of("outbox_replay_audit","feed_event_conflicts","feed_delivery_tasks","consumer_inbox","outbox_events","follow_history","follows","topic_event_ledger","topic_minute_buckets"))db.update("DELETE FROM "+t);
 }

 /** Inserts an outbox event; sentDaysAgo null means sent_at NULL. Times come from the database clock. */
 long event(String status,Integer sentDaysAgo,int retryDaysAgo){
  var keys=new GeneratedKeyHolder();
  db.update(c->{var s=c.prepareStatement("INSERT INTO outbox_events(event_type,aggregate_id,payload,status,sent_at,next_retry_at) VALUES('FollowChanged',?,'{}',?,"
    +(sentDaysAgo==null?"NULL":"TIMESTAMPADD(DAY,-"+sentDaysAgo+",CURRENT_TIMESTAMP(6))")+",TIMESTAMPADD(DAY,-"+retryDaysAgo+",CURRENT_TIMESTAMP(6)))",Statement.RETURN_GENERATED_KEYS);
   s.setLong(1,AGG.incrementAndGet());s.setString(2,status);return s;},keys);
  return keys.getKey().longValue();
 }
 void inbox(long event,int daysAgo,String decision){
  db.update("INSERT INTO consumer_inbox(consumer_name,event_id,processed_at,event_type,payload_hash,decision) VALUES('feed-v1',?,TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP(6)),'FollowChanged',REPEAT('a',64),?)",event,daysAgo,decision);
 }
 /** completedDaysAgo null means completed_at NULL (a task finished before V31); lastAttemptDaysAgo ages such rows. */
 void task(long event,String state,Integer completedDaysAgo,int lastAttemptDaysAgo){
  db.update("INSERT INTO feed_delivery_tasks(event_id,author_id,delivery_epoch,state,kind,completed_at,last_attempt_at) VALUES(?,1,0,?,'PUBLISH',"
   +(completedDaysAgo==null?"NULL":"TIMESTAMPADD(DAY,-"+completedDaysAgo+",CURRENT_TIMESTAMP(6))")+",TIMESTAMPADD(DAY,-?,CURRENT_TIMESTAMP(6)))",event,state,lastAttemptDaysAgo);
 }
 FeedRetention feed(int batch){return new FeedRetention(retention,tx,new RetentionBounds(FeedRetention.DEFAULT_RETENTION_MS,batch,1000));}
 long count(String sql,Object... args){return db.queryForObject(sql,Long.class,args);}
 long orphanTasks(){return count("SELECT COUNT(*) FROM feed_delivery_tasks t WHERE t.state<>'RUNNING' AND NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=t.event_id)");}
 long taskGaps(){return count("SELECT COUNT(*) FROM consumer_inbox i WHERE i.decision IN('PUSH_TASK','HISTORY_TASK') AND NOT EXISTS(SELECT 1 FROM feed_delivery_tasks t WHERE t.event_id=i.event_id)");}
 long inboxGaps(){return count("SELECT COUNT(*) FROM outbox_events o WHERE o.status='SENT' AND NOT EXISTS(SELECT 1 FROM consumer_inbox i WHERE i.event_id=o.id)");}

 @Test void feedRetentionDeletesWholeFinishedEventsAndKeepsEverythingStillNeeded(){
  // deletable: SENT 8 days ago, receipt 8 days ago, and no task / DONE task completed 8 days ago (also a pre-V31 DONE task aged by last attempt)
  long pull=event("SENT",8,8);inbox(pull,8,"PULL_NO_FANOUT");
  long push=event("SENT",8,8);inbox(push,8,"PUSH_TASK");task(push,"DONE",8,8);
  long legacyDone=event("SENT",9,9);inbox(legacyDone,9,"HISTORY_TASK");task(legacyDone,"DONE",null,9);
  // kept
  long young=event("SENT",6,6);inbox(young,6,"PULL_NO_FANOUT");                         // inside the 7 day window
  long youngReceipt=event("SENT",8,8);inbox(youngReceipt,6,"PULL_NO_FANOUT");           // receipt still inside the idempotency window
  long noInbox=event("SENT",8,8);                                                       // reconcile gap: SENT without receipt
  long missingTask=event("SENT",8,8);inbox(missingTask,8,"PUSH_TASK");                  // reconcile gap: task never created
  long dead=event("SENT",8,8);inbox(dead,8,"PUSH_TASK");task(dead,"DEAD",null,8);        // DEAD task kept for manual replay
  long youngTask=event("SENT",8,8);inbox(youngTask,8,"PUSH_TASK");task(youngTask,"DONE",2,2); // DONE only 2 days ago
  long running=event("SENT",8,8);inbox(running,8,"PUSH_TASK");task(running,"RUNNING",null,1);
  long deadOutbox=event("DEAD",null,8);
  long newOutbox=event("NEW",null,8);
  long noSentTime=event("SENT",null,8);inbox(noSentTime,8,"PULL_NO_FANOUT");            // C-6: completion time unknown, kept
  long replayed=event("SENT",8,8);inbox(replayed,8,"PULL_NO_FANOUT");
  db.update("INSERT INTO outbox_replay_audit(event_id,operator,reason,old_token,new_token,previous_attempt) VALUES(?,'ops','test',1,2,5)",replayed);
  long conflicted=event("SENT",8,8);inbox(conflicted,8,"PULL_NO_FANOUT");
  db.update("INSERT INTO feed_event_conflicts(consumer_name,event_id,received_hash,reason,payload) VALUES('feed-v1',?,REPEAT('b',64),'TEST','{}')",conflicted);
  long orphanBefore=orphanTasks(),taskGapsBefore=taskGaps(),inboxGapsBefore=inboxGaps();

  var result=feed(2).purge();

  assertThat(result).as("batch size 2 still reaches every deletable event").isEqualTo(new FeedRetention.Result(3,3,2));
  var deleted=List.of(pull,push,legacyDone);
  var kept=List.of(young,youngReceipt,noInbox,missingTask,dead,youngTask,running,deadOutbox,newOutbox,noSentTime,replayed,conflicted);
  assertThat(db.queryForList("SELECT id FROM outbox_events ORDER BY id",Long.class)).containsExactlyElementsOf(kept);
  assertThat(db.queryForList("SELECT event_id FROM consumer_inbox",Long.class)).doesNotContainAnyElementsOf(deleted)
   .containsExactlyInAnyOrder(young,youngReceipt,missingTask,dead,youngTask,running,noSentTime,replayed,conflicted);
  assertThat(db.queryForList("SELECT event_id FROM feed_delivery_tasks",Long.class)).containsExactlyInAnyOrder(dead,youngTask,running);
  assertThat(orphanTasks()).as("no DONE/DEAD task left without its receipt").isEqualTo(orphanBefore);
  assertThat(taskGaps()).as("no receipt left whose task was deleted (reconcile would re-fan-out)").isEqualTo(taskGapsBefore);
  assertThat(inboxGaps()).as("no SENT outbox left whose receipt was deleted (reconcile would re-accept)").isEqualTo(inboxGapsBefore);
  assertThat(feed(2).purge()).as("idempotent").isEqualTo(new FeedRetention.Result(0,0,0));
 }

 @Test void feedRetentionStopsAfterMaxBatchesPerRun(){
  for(int i=0;i<5;i++){long e=event("SENT",8,8);inbox(e,8,"PULL_NO_FANOUT");}
  var bounded=new FeedRetention(retention,tx,new RetentionBounds(FeedRetention.DEFAULT_RETENTION_MS,2,1));
  assertThat(bounded.purge().events()).as("one batch of two per run").isEqualTo(2);
  assertThat(bounded.purge().events()).isEqualTo(2);
  assertThat(bounded.purge().events()).isEqualTo(1);
 }

 @Test void deliveryTaskRecordsCompletionTimeWhenItBecomesDone(){
  long gen=db.queryForObject("SELECT generation FROM feed_control WHERE id=1",Long.class);
  long done=event("SENT",0,0),partial=event("SENT",0,0);
  for(long e:List.of(done,partial))db.update("INSERT INTO feed_delivery_tasks(event_id,author_id,delivery_epoch,state,kind,follower_id,follow_epoch,lease_token,lease_until,lease_generation)"
   +" VALUES(?,1,0,'RUNNING','BACKFILL',2,1,7,TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(6)),?)",e,gen);
  long doneId=count("SELECT id FROM feed_delivery_tasks WHERE event_id=?",done),partialId=count("SELECT id FROM feed_delivery_tasks WHERE event_id=?",partial);
  assertThat(deliveries.progress(doneId,7,0,5,true,30_000,gen)).isEqualTo(1);
  assertThat(deliveries.progress(partialId,7,0,5,false,30_000,gen)).isEqualTo(1);
  assertThat(count("SELECT COUNT(*) FROM feed_delivery_tasks WHERE id=? AND state='DONE' AND completed_at BETWEEN TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP(6)) AND CURRENT_TIMESTAMP(6)",doneId))
   .as("DONE records completed_at").isEqualTo(1);
  assertThat(count("SELECT COUNT(*) FROM feed_delivery_tasks WHERE id=? AND state='RUNNING' AND completed_at IS NULL",partialId)).as("progress without done leaves it NULL").isEqualTo(1);
 }

 void follow(long reader,long author,String status,long epoch){
  db.update("INSERT INTO follows(follower_id,followee_id,status,follow_epoch,follow_cutoff_seq,history_since_ms,history_limit) VALUES(?,?,?,?,0,0,10)",reader,author,status,epoch);
 }
 void history(long reader,long author,long epoch,long... posts){for(long p:posts)db.update("INSERT INTO follow_history(follower_id,followee_id,follow_epoch,post_id) VALUES(?,?,?,?)",reader,author,epoch,p);}

 @Test void followHistoryRetentionDeletesOnlyDeadEpochs(){
  follow(1,9,"FOLLOWING",2);history(1,9,1,11,12,13);history(1,9,2,21,22);   // re-followed: epoch 1 dead, epoch 2 authorises reads
  follow(3,9,"UNFOLLOWED",1);history(3,9,1,31,32);                         // unfollowed: epoch 1 dead (re-follow would be epoch 2)
  follow(4,9,"FOLLOWING",1);history(4,9,1,41);                             // current
  var purge=new FollowHistoryRetention(retention,tx,2,1000);
  assertThat(purge.purge()).isEqualTo(5);
  assertThat(db.queryForList("SELECT CONCAT(follower_id,':',follow_epoch,':',post_id) FROM follow_history ORDER BY follower_id,follow_epoch,post_id",String.class))
   .containsExactly("1:2:21","1:2:22","4:1:41");
  assertThat(purge.purge()).isZero();
 }

 @Test void topicLedgerPurgeNeverPassesTheProcessedWatermark(){
  long now=System.currentTimeMillis(),m=now/60_000L*60_000L;
  for(long age:new long[]{72*HOUR,60*HOUR,50*HOUR}){
   db.update("INSERT INTO topic_event_ledger(event_id,topic,post_id,occurred_at_ms,delta,kind) VALUES(?,?,1,?,1,'LIKE')","wm:"+age,"wm",now-age);
   db.update("INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions) VALUES('wm',?,1,1)",m-age);
  }
  long watermark=now-66*HOUR; // events at/after this time are not processed yet (B1 option 2)
  var result=new TopicRetention(interactions,tx,TopicRetention.DEFAULT_RETENTION_MS,1,()->watermark).purge(now);
  assertThat(db.queryForList("SELECT event_id FROM topic_event_ledger ORDER BY occurred_at_ms",String.class))
   .as("only ledger rows before the processed watermark are deleted").containsExactly("wm:"+60*HOUR,"wm:"+50*HOUR);
  assertThat(result).isEqualTo(new TopicRetention.Result(3,1));
  assertThat(new TopicRetention(interactions,tx,TopicRetention.DEFAULT_RETENTION_MS,1).purge(now))
   .as("without a watermark the time cutoff alone applies (current behaviour)").isEqualTo(new TopicRetention.Result(0,2));
 }
}
