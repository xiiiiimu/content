package io.github.xiiiiimu.content.domain;
import io.github.xiiiiimu.content.infrastructure.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.*;

public final class DistributionWorker {
 public record Write(long reader,FeedCandidate candidate) {}
 private final DeliveryStore store;private final DeliveryMapper tasks;private final FeedMapper db;private final RedisFeedCandidateStore redis;private final ObjectMapper json;private final PipelineObserver observer;
 public DistributionWorker(DeliveryStore store,DeliveryMapper tasks,FeedMapper db,StatefulRedisConnection<String,String> connection,ObjectMapper json,PipelineObserver observer){this.store=store;this.tasks=tasks;this.db=db;redis=new RedisFeedCandidateStore(connection);this.json=json;this.observer=observer;}
 public void tick(){
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Worker I/O must not run in an ambient SQL transaction");
  for(int i=0;i<10;i++){var job=store.claim();if(job==null)return;run(job);}
 }
 public void run(DeliveryLease initial){
  var job=initial;
  try{
   observer.afterClaim(job);
   var body=json.readTree(job.payload());if(body.path("eventId").longValue()!=job.eventId() || body.path("authorId").longValue()!=job.authorId())throw new IllegalStateException("TASK_PAYLOAD_CONFLICT");
   while(store.valid(job)){
    var writes=new ArrayList<Write>();long follower=job.lastFollower(),post=job.lastPost();boolean done;
    if(job.kind().equals("PUBLISH")){
     var p=db.post(Objects.requireNonNull(job.postId()));
     if(p==null || p.time()==null || p.sequence()==null || p.epoch()==null || !"PUSH".equals(p.mode()) || p.epoch()!=job.deliveryEpoch() || p.authorId()!=job.authorId()
       || body.path("postId").longValue()!=p.id() || body.path("publishedAtMs").longValue()!=p.time() || body.path("publishSeq").longValue()!=p.sequence())throw new IllegalStateException("TASK_PUBLICATION_CONFLICT");
     var recipients=tasks.followers(job.authorId(),follower,Objects.requireNonNull(job.upper()),100);
     for(long reader:recipients){if(p.status().equals("PUBLISHED") && tasks.eligible(reader,job.authorId(),p.sequence()))writes.add(new Write(reader,new FeedCandidate(p.time(),p.id())));follower=reader;}
     done=recipients.size()<100;
    }else if(job.kind().equals("BACKFILL")){
     long reader=Objects.requireNonNull(job.followerId()),epoch=Objects.requireNonNull(job.followEpoch());
     if(body.path("followerId").longValue()!=reader || body.path("followEpoch").longValue()!=epoch)throw new IllegalStateException("TASK_HISTORY_CONFLICT");
     var ids=tasks.history(reader,job.authorId(),epoch,post,100);
     for(long id:ids){var p=db.post(id);if(p!=null && p.time()!=null && p.status().equals("PUBLISHED") && tasks.historyEligible(reader,job.authorId(),epoch,id))writes.add(new Write(reader,new FeedCandidate(p.time(),id)));post=id;}
     done=ids.size()<100;
    }else throw new IllegalStateException("Unknown task kind");
    observer.afterRelations(job,List.copyOf(writes));
    if(!store.valid(job))return;
    observer.beforeRedis(job);
    for(var write:writes)redis.addFenced(write.reader(),List.of(write.candidate()),Objects.requireNonNull(job.generation()));
    observer.afterRedis(job);observer.beforeProgress(job);
    if(!store.progress(job,follower,post,done))return;
    if(done)return;job=job.position(follower,post);
   }
  }catch(Exception failure){org.slf4j.LoggerFactory.getLogger(DistributionWorker.class).warn("Delivery batch failed taskId={} token={}",job.taskId(),job.token(),failure);store.fail(job,failure);}
 }
}
