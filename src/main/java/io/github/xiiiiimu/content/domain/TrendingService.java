package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Minute buckets + rebuildable ranking. Emits TrendGuard risk events per ADR-005. Redis never authorizes. */
public final class TrendingService {
 public record Entry(String topic,long score) {}
 private final InteractionMapper db;private final TransactionTemplate tx;private final FeedRedis redis;private final VisibilityService visibility;
 private final TrendGuardService risks;private final int spikeThreshold;
 public TrendingService(InteractionMapper db,TransactionTemplate tx,FeedRedis redis,VisibilityService visibility,TrendGuardService risks,int spikeThreshold){
  this.db=db;this.tx=tx;this.redis=redis;this.visibility=visibility;this.risks=risks;this.spikeThreshold=spikeThreshold;
 }
 public void record(String eventId,String topic,long post,int delta,String kind){
  tx.executeWithoutResult(status->{
   if(db.ledger(eventId,topic,post,System.currentTimeMillis(),delta,kind)!=1)return;
   long bucket=System.currentTimeMillis()/60_000L*60_000L;
   db.bucket(topic,bucket,delta,delta==0?0:1);
   emitSpikeIfNeeded(topic,post);
  });
 }
 public static String likeEventId(long post,long user){return "like:"+post+":"+user;}
 /**
  * A false→true like flip on a post with a topic: one ledger row like:{post}:{user} (at most one per post/user while liked)
  * and +1 in the current minute bucket. A stale row (legacy data) makes this a no-op instead of a second +1.
  */
 public void recordLike(long post,long user,String topic){record(likeEventId(post,user),topic,post,1,"LIKE");}
 /**
  * A true→false like flip (unlike, or a dislike that removed the like): deletes that like's ledger row and takes it back
  * from the bucket of the minute it was counted in, so like/unlike cycles net to zero, never below, and an unlike of an
  * old like does not subtract from the current window. No ledger row (post had no topic, or row past retention) → no-op.
  * Joins the caller's transaction; the caller's post_likes row lock serializes flips of the same (post,user).
  */
 public boolean revokeLike(long post,long user){
  String id=likeEventId(post,user);
  return Boolean.TRUE.equals(tx.execute(status->{
   var like=db.likeEvent(id);
   if(like==null || db.deleteLedger(id)!=1)return false;
   db.revokeBucket(like.topic(),like.at()/60_000L*60_000L);
   return true;
  }));
 }
 /**
  * Production TrendGuard output (ADR-005): same TX as ledger/bucket.
  * Lock order: post/like/stats (caller) → ledger insert → minute-bucket row → {@code trend_topic_serial} FOR UPDATE → risk insert.
  * Single-topic serial lock avoids deadlock; concurrent txs serialize so threshold emit does not require a later interaction.
  * <p>Once this process knows the (topic, hour) spike row exists, later interactions skip the serial lock, the scan and the
  * insert (round-1 R9). "Known" means: our insert committed (marked after commit, so a rollback never suppresses the real
  * emit) or the insert hit the unique business key (a committed row from another transaction or node).
  */
 void emitSpikeIfNeeded(String topic,long post){
  if(spikeThreshold<=0 || risks==null)return;
  long now=System.currentTimeMillis();
  long hour=now/3_600_000L*3_600_000L;
  if(reported.contains(topic,hour))return;
  risks.lockTopicForEmit(topic);
  long since=Math.max(0,now-3_600_000L);
  long net=db.topicNetSince(topic,since);
  if(net<spikeThreshold)return;
  String id=TrendGuardService.spikeEventId(topic,hour);
  boolean inserted=risks.recordInCurrentTx(id,topic,TrendGuardService.KIND_TOPIC_SPIKE,hour,post,"net_events_1h>="+spikeThreshold+";net="+net);
  if(!inserted)reported.add(topic,hour);
  else if(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
   org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
    @Override public void afterCommit(){reported.add(topic,hour);}
   });
 }
 private final ReportedSpikes reported=new ReportedSpikes();
 /** Per-process memory of reported (topic, clock hour) spike windows; keeps only the current and previous hour. */
 static final class ReportedSpikes {
  private final java.util.concurrent.ConcurrentHashMap<Long,Set<String>> byHour=new java.util.concurrent.ConcurrentHashMap<>();
  boolean contains(String topic,long hour){var s=byHour.get(hour);return s!=null && s.contains(topic);}
  void add(String topic,long hour){
   byHour.computeIfAbsent(hour,h->java.util.concurrent.ConcurrentHashMap.newKeySet()).add(topic);
   byHour.keySet().removeIf(h->h<hour-3_600_000L);
  }
 }
 /** Ranking scores come from MySQL buckets only. Content lists use {@link #topicPosts}. */
 public List<Entry> window(String metric,long windowMillis,long viewer){
  if(viewer<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  if(!"net_events".equals(metric) && !"active_interactions".equals(metric))throw new FeedFailure(400,"INVALID_REQUEST");
  long since=Math.max(0,System.currentTimeMillis()-windowMillis);
  var scores=new HashMap<String,Long>();
  for(var b:db.bucketsSince(since))scores.merge(b.topic(), "net_events".equals(metric)?b.net():b.active(), Long::sum);
  return scores.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(),a.getValue())).limit(50)
   .map(e->new Entry(e.getKey(),e.getValue())).toList();
 }
 /** Topic page / hotspot content list from posts.topic (the same attribute that routes topic statistics); every returned post re-checks VisibilityService (ADR-004 T10b carry-over). */
 public List<VisibilityService.View> topicPosts(String topic,long viewer,int limit){
  if(viewer<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  if(topic==null || topic.isBlank())throw new FeedFailure(400,"INVALID_REQUEST");
  int n=limit<=0?50:Math.min(limit,50);
  var out=new ArrayList<VisibilityService.View>();
  for(long post:db.postsWithTopic(topic,n*4)){
   visibility.read(viewer,post).ifPresent(out::add);
   if(out.size()>=n)break;
  }
  return List.copyOf(out);
 }
 public int rebuild(long windowMillis){
  long since=Math.max(0,System.currentTimeMillis()-windowMillis);
  return tx.execute(status->{
   db.clearBuckets();
   int n=0;for(var e:db.ledgerSince(since)){long bucket=e.at()/60_000L*60_000L;db.bucket(e.topic(),bucket,e.delta(),e.delta()==0?0:1);n++;}
   try{var sync=redis.connection().sync();sync.del("trend:v1:net:"+windowMillis);for(var e:window("net_events",windowMillis,1L))sync.zadd("trend:v1:net:"+windowMillis,(double)e.score(),e.topic());}catch(Exception ignored){}
   return n;
  });
 }
}
