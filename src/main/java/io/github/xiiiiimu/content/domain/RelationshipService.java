package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.FeedMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

public final class RelationshipService {
 private final FeedMapper db;private final TransactionTemplate tx;private final ObjectMapper json;private final int historyLimit;private final long window;private final PostDetailService details;
 public RelationshipService(FeedMapper db,TransactionTemplate tx,ObjectMapper json,int historyLimit,long window,PostDetailService details){
  if(historyLimit<0 || historyLimit>100 || window<=0)throw new IllegalArgumentException("Invalid history bounds");
  this.db=db;this.tx=tx;this.json=json;this.historyLimit=historyLimit;this.window=window;this.details=details;
 }
 public record Result(boolean following,long epoch,long graphVersion) {}
 public Result change(long reader,long author,boolean following){
  if(reader<=0 || author<=0 || reader==author)throw new FeedFailure(400,"INVALID_RELATIONSHIP");
  return tx.execute(status->{
   requireWritable(db.gate());var a=db.author(author);if(a==null)throw new FeedFailure(404,"AUTHOR_NOT_FOUND");
   db.initGraph(reader);long graph=db.lockGraph(reader);var old=db.relation(reader,author);
   if(old!=null && old.status().equals(following?"FOLLOWING":"UNFOLLOWED"))return new Result(following,old.epoch(),graph);
   if(!following && old==null)return new Result(false,0,graph);
   long epoch=following?Math.addExact(old==null?0:old.epoch(),1):old.epoch();
   long now=db.now();long since=Math.max(0,now-window);
   if(following){db.follow(reader,author,epoch,a.sequence(),since,historyLimit);for(long post:db.history(author,a.sequence(),since,historyLimit))db.saveHistory(reader,author,epoch,post);}
   else db.unfollow(reader,author);
   db.bumpGraph(reader);
   emit("FollowChanged",author,Map.of("followerId",reader,"authorId",author,"followEpoch",epoch,"following",following,"cutoff",a.sequence(),"sinceMs",since,"historyLimit",historyLimit));
   return new Result(following,epoch,Math.addExact(graph,1));
  });
 }
 public void offline(long author,long post,long version){
  if(author<=0 || post<=0 || version<0)throw new FeedFailure(400,"INVALID_REQUEST");
  tx.executeWithoutResult(status->{requireWritable(db.gate());var existing=db.post(post);if(existing==null)throw new FeedFailure(404,"POST_NOT_FOUND");
   if(existing.authorId()!=author)throw new FeedFailure(403,"POST_FORBIDDEN");db.author(author);
   if(db.offline(author,post,version)!=1)throw new FeedFailure(409,"POST_STATE_CONFLICT");
   emit("PostOfflined",post,Map.of("postId",post,"authorId",author,"postVersion",Math.addExact(version,1)));
  });
  // Invalidation accelerates cache drop but never authorizes; authority already flipped in MySQL.
  details.invalidate(post);
 }
 public long emit(String type,long aggregate,Map<String,?> fields){
  db.event(type,aggregate);long id=db.insertedId();var payload=new LinkedHashMap<String,Object>(fields);payload.put("eventId",id);payload.put("eventType",type);
  try{db.eventPayload(id,json.writeValueAsString(payload));}catch(Exception e){throw new IllegalStateException(e);}return id;
 }
 public static void requireWritable(FeedMapper.Control c){if(c==null || c.generation()<=0 || !Set.of("PRIMARY","READY").contains(c.state()))throw new FeedFailure(503,"MAINTENANCE");}
}
