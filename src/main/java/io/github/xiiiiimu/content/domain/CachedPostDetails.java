package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.FeedRedis;
import io.lettuce.core.api.sync.RedisCommands;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Content cache only. Every return path re-checks VisibilityService. */
public final class CachedPostDetails implements PostDetailService {
 private record Cache(long postId,long authorId,long publishedAtMs,String body,long version,String status) {}
 private final VisibilityService visibility;private final FeedRedis redis;private final ObjectMapper json;private final PipelineObserver observer;
 public CachedPostDetails(VisibilityService visibility,FeedRedis redis,ObjectMapper json,PipelineObserver observer){this.visibility=visibility;this.redis=redis;this.json=json;this.observer=observer;}
 private static String key(long id){return "post:detail:v1:"+id;}
 @Override public VisibilityService.View get(long viewer,long postId){
  var view=visibility.read(viewer,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  try{
   RedisCommands<String,String> sync=redis.connection().sync();
   sync.setex(key(postId),300,json.writeValueAsString(new Cache(view.postId(),view.authorId(),view.publishedAtMs(),view.body(),view.version(),view.status())));
  }catch(Exception ignored){/* Cache write failure must not block authoritative reads. */}
  return view;
 }
 @Override public Long cachedVersion(long postId){
  try{
   String raw=redis.connection().sync().get(key(postId));if(raw==null)return null;
   return json.readValue(raw,Cache.class).version();
  }catch(Exception e){return null;}
 }
 @Override public void invalidate(long postId){
  observer.beforeCacheInvalidate(postId);
  try{redis.connection().sync().del(key(postId));}catch(Exception ignored){/* Invalidation is acceleration only. */}
 }
}
