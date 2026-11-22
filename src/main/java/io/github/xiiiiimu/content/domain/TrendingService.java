package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Topic ledger writes + bucket-based ranking. Buckets and TrendGuard risk events come from {@link TopicAggregator} (B1). Redis never authorizes. */
public final class TrendingService {
 public record Entry(String topic,long score) {}
 private final InteractionMapper db;private final TransactionTemplate tx;private final FeedRedis redis;private final VisibilityService visibility;
 private final TopicAggregator aggregator;
 public TrendingService(InteractionMapper db,TransactionTemplate tx,FeedRedis redis,VisibilityService visibility,TopicAggregator aggregator){
  this.db=db;this.tx=tx;this.redis=redis;this.visibility=visibility;this.aggregator=aggregator;
 }
 /**
  * Hardening B1: one pending ledger row and nothing else. No topic-wide row (minute bucket, serial lock) is touched and
  * no spike check runs here; {@link TopicAggregator} folds the row into its minute's bucket within the lag budget and
  * runs the spike check. A duplicate event id is a no-op.
  */
 public void record(String eventId,String topic,long post,int delta,String kind){
  tx.executeWithoutResult(status->db.ledger(eventId,topic,post,System.currentTimeMillis(),delta,kind));
 }
 public static String likeEventId(long post,long user){return "like:"+post+":"+user;}
 /**
  * A false→true like flip on a post with a topic: one pending ledger row like:{post}:{user} (at most one per post/user
  * while liked). A stale row (legacy data) makes this a no-op instead of a second +1.
  */
 public void recordLike(long post,long user,String topic){record(likeEventId(post,user),topic,post,1,"LIKE");}
 /**
  * A true→false like flip (unlike, or a dislike that removed the like), round-1 R8 kept under B1:
  * <ul>
  *  <li>like not folded yet → its pending row is deleted: it never reaches a bucket;</li>
  *  <li>like already folded → the row is renamed off like:{post}:{user} (so the user can like again) and queued as a
  *      revocation; the aggregator takes it back from the bucket of the minute it was counted in, never below zero, and
  *      deletes the row in the same transaction, so it is subtracted exactly once.</li>
  * </ul>
  * Like/unlike cycles net to zero, and an unlike of an old like does not subtract from the current window. No ledger row
  * (post had no topic, or row past retention) → no-op. Joins the caller's transaction; the caller's post_likes row lock
  * serializes flips of the same (post,user), and the FOR UPDATE read waits at most one aggregator batch.
  */
 public boolean revokeLike(long post,long user){
  String id=likeEventId(post,user);
  return Boolean.TRUE.equals(tx.execute(status->{
   var like=db.likeEventForUpdate(id);
   if(like==null)return false;
   if(like.pending())return db.deleteLedger(id)==1;
   return db.revokeCounted(id,"rv:"+java.util.UUID.randomUUID(),System.currentTimeMillis())==1;
  }));
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
 /** Rebuilds the buckets from the ledger (see {@link TopicAggregator#rebuild}), then refreshes the Redis ranking cache. */
 public int rebuild(long windowMillis){
  long since=Math.max(0,System.currentTimeMillis()-windowMillis);
  int n=aggregator.rebuild(since);
  try{var sync=redis.connection().sync();sync.del("trend:v1:net:"+windowMillis);for(var e:window("net_events",windowMillis,1L))sync.zadd("trend:v1:net:"+windowMillis,(double)e.score(),e.topic());}catch(Exception ignored){}
  return n;
 }
}
