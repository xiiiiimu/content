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
 /** Production TrendGuard output path (ADR-005 B1): same TX as ledger/bucket. */
 void emitSpikeIfNeeded(String topic,long post){
  if(spikeThreshold<=0 || risks==null)return;
  long since=Math.max(0,System.currentTimeMillis()-3_600_000L);
  long net=0;for(var b:db.bucketsSince(since))if(topic.equals(b.topic()))net+=b.net();
  if(net<spikeThreshold)return;
  long hour=System.currentTimeMillis()/3_600_000L*3_600_000L;
  String id="tg:1:TOPIC_SPIKE:"+topic+":"+hour;
  // RED: truncation retained so long shared-prefix topics collide — removed in GREEN.
  if(id.length()>64)id=id.substring(0,64);
  risks.recordInCurrentTx(id,topic,"TOPIC_SPIKE",hour,post,"net_events_1h>="+spikeThreshold+";net="+net);
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
 /** Topic page / hotspot content list: every returned post re-checks VisibilityService (ADR-004 T10b carry-over). */
 public List<VisibilityService.View> topicPosts(String topic,long viewer,int limit){
  if(viewer<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  if(topic==null || topic.isBlank())throw new FeedFailure(400,"INVALID_REQUEST");
  int n=limit<=0?50:Math.min(limit,50);
  var out=new ArrayList<VisibilityService.View>();
  for(long post:db.postsForTopic(topic,n*4)){
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
