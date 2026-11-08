package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

public final class FollowingFeed {
 public record Cursor(int version,long reader,int limit,long snapshot,long since,FeedCandidate globalBoundary,FeedCandidate consumedBoundary,long graph,long generation,long expiresAt) {}
 public record Item(long postId,long publishedAtMs,String body) {}
 public record Response(List<Item> items,String nextCursor,FeedMerger.PageState pageState,int scanned) {}
 private final FeedMapper db;private final FeedMerger merger;private final FeedTokens tokens;private final long window;private final FeedRedis redis;private final FeedMerger.Budget budget;private final PipelineObserver observer;private final VisibilityService visibility;
 public FollowingFeed(FeedMapper db,ExecutorService executor,FeedTokens tokens,long window,FeedRedis redis,FeedMerger.Budget budget,PipelineObserver observer,VisibilityService visibility){this.db=db;merger=new FeedMerger(executor);this.tokens=tokens;this.window=window;this.redis=redis;this.budget=budget;this.observer=observer;this.visibility=visibility;}
 public Response read(long reader,int limit,String token){
  if(reader<=0 || limit<1 || limit>100)throw new FeedFailure(400,"INVALID_REQUEST");
  long now=db.now();db.initGraph(reader);long graph=Objects.requireNonNull(db.graph(reader));var control=db.control();
  if(control==null || control.generation()<=0 || !Set.of("PRIMARY","READY","MAINTENANCE","REBUILDING").contains(control.state()))throw new FeedFailure(503,"INVALID_FEED_CONTROL");
  Cursor c=token==null?new Cursor(1,reader,limit,now,Math.max(0,now-window),null,null,graph,control.generation(),now+900_000):tokens.verify(token,Cursor.class);
  if(c.version()!=1 || c.reader()!=reader || c.limit()!=limit || c.expiresAt()<=now || c.snapshot()>now || c.since()<0 || c.since()>c.snapshot())throw new FeedFailure(400,"INVALID_CURSOR");
  if(c.graph()!=graph || c.generation()!=control.generation())throw new FeedFailure(409,"CURSOR_STALE");
  var sources=new LinkedHashMap<String,FeedMerger.Source>();sources.put("pull",source("PULL",c));sources.put("push-authority",source("PUSH",c));
  // The complete primary sources remain present even when a ready Redis inbox is used.
  if(control.state().equals("READY")){
   try{var connection=redis.connection();if(Long.toString(control.generation()).equals(connection.sync().get("feed:ready-generation")) && connection.sync().exists("feed:v2:{"+reader+"}:inbox:untrusted")==0)sources.put("inbox",new RedisCandidateSource(new RedisFeedCandidateStore(connection),reader,c.since()));}
   catch(io.lettuce.core.RedisException ignored){/* Complete primary range still covers this request. */}
  }
  var payloads=new HashMap<Long,Item>();
  FeedMerger.Authority authority=candidate->{observer.beforeFeedAuthority(reader,candidate);var row=db.authority(reader,candidate.postId());
   if(row==null)return new FeedMerger.Decision(null,false);
   if(row.time()==null)throw new FeedFailure(503,"INVALID_PUBLICATION");
   // Feed membership still uses the follow predicate; offline/status denial is unified here.
   var canonical=new FeedCandidate(row.time(),row.id());boolean visible=row.allowed() && row.time()>=c.since() && visibility.visibleInFeed(reader,candidate.postId());
   if(visible)payloads.put(row.id(),new Item(row.id(),row.time(),row.body()));return new FeedMerger.Decision(canonical,visible);
  };
  var input=new FeedMerger.Continuation(c.snapshot(),c.globalBoundary(),c.consumedBoundary());
  // A mid-scan fault fails the request; do not reset work counters through an implicit whole-page retry.
  // On a subsequent request an unavailable readiness marker selects complete primary sources directly.
  var page=merger.read(sources,authority,input,limit,budget);
  observer.beforeFeedReturn(reader);
  if(!Objects.equals(db.graph(reader),graph) || db.control().generation()!=control.generation())throw new FeedFailure(409,"CURSOR_STALE");
  String next=page.next()==null?null:tokens.sign(new Cursor(1,reader,limit,c.snapshot(),c.since(),page.next().globalBoundary(),page.next().consumedBoundary(),graph,c.generation(),c.expiresAt()));
  return new Response(page.items().stream().map(item->payloads.get(item.postId())).toList(),next,page.pageState(),page.scanned());
 }
 private FeedMerger.Source source(String mode,Cursor cursor){return(snapshot,boundary,n)->{
  var rows=db.candidates(cursor.reader(),mode,cursor.since(),snapshot,boundary==null?null:boundary.publishedAtMs(),boundary==null?null:boundary.postId(),n);
  return new FeedMerger.Batch(rows,rows.size()<n);
 };}
}
