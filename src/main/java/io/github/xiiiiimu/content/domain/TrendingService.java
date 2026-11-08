package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Minute buckets + rebuildable ranking. Net events are not the same as active like counts. */
public final class TrendingService {
 public record Entry(String topic,long score) {}
 private final InteractionMapper db;private final TransactionTemplate tx;private final FeedRedis redis;private final VisibilityService visibility;
 public TrendingService(InteractionMapper db,TransactionTemplate tx,FeedRedis redis,VisibilityService visibility){this.db=db;this.tx=tx;this.redis=redis;this.visibility=visibility;}
 public void record(String eventId,String topic,long post,int delta,String kind){
  tx.executeWithoutResult(status->{
   if(db.ledger(eventId,topic,post,System.currentTimeMillis(),delta,kind)!=1)return;
   long bucket=System.currentTimeMillis()/60_000L*60_000L;
   db.bucket(topic,bucket,delta,delta==0?0:1);
  });
 }
 public List<Entry> window(String metric,long windowMillis,long viewer){
  if(!"net_events".equals(metric) && !"active_interactions".equals(metric))throw new FeedFailure(400,"INVALID_REQUEST");
  long since=Math.max(0,System.currentTimeMillis()-windowMillis);
  var scores=new HashMap<String,Long>();
  for(var b:db.bucketsSince(since))scores.merge(b.topic(), "net_events".equals(metric)?b.net():b.active(), Long::sum);
  return scores.entrySet().stream().sorted((a,b)->Long.compare(b.getValue(),a.getValue())).limit(50)
   .map(e->new Entry(e.getKey(),e.getValue())).toList();
 }
 public int rebuild(long windowMillis){
  long since=Math.max(0,System.currentTimeMillis()-windowMillis);
  return tx.execute(status->{
   db.clearBuckets();
   int n=0;for(var e:db.ledgerSince(since)){long bucket=e.at()/60_000L*60_000L;db.bucket(e.topic(),bucket,e.delta(),e.delta()==0?0:1);n++;}
   // Redis ranking is a rebuildable projection; MySQL buckets remain authoritative for this batch.
   try{var sync=redis.connection().sync();sync.del("trend:v1:net:"+windowMillis);for(var e:window("net_events",windowMillis,0))sync.zadd("trend:v1:net:"+windowMillis,(double)e.score(),e.topic());}catch(Exception ignored){}
   return n;
  });
 }
}
