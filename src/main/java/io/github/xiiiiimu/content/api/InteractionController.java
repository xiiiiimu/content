package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

@RestController @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class InteractionController {
 private final InteractionService interactions;private final TrendingService trending;
 public InteractionController(InteractionService interactions,TrendingService trending){this.interactions=interactions;this.trending=trending;}
 private long user(Principal p){if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");try{long id=Long.parseLong(p.getName());if(id<=0)throw new FeedFailure(401,"UNAUTHENTICATED");return id;}catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}}
 @PutMapping("/api/posts/{post}/likes") public InteractionService.LikeResult like(@PathVariable long post,@RequestParam(required=false)String topic,Principal p){return interactions.like(user(p),post,topic);}
 @DeleteMapping("/api/posts/{post}/likes") public InteractionService.LikeResult unlike(@PathVariable long post,@RequestParam(required=false)String topic,Principal p){return interactions.unlike(user(p),post,topic);}
 @PutMapping("/api/posts/{post}/dislikes") public InteractionService.DislikeResult dislike(@PathVariable long post,Principal p){return interactions.dislike(user(p),post);}
 @DeleteMapping("/api/posts/{post}/dislikes") public InteractionService.DislikeResult undislike(@PathVariable long post,Principal p){return interactions.undislike(user(p),post);}
 @PostMapping("/api/posts/{post}/reports") public InteractionService.ReportResult report(@PathVariable long post,@RequestBody java.util.Map<String,Object> body,Principal p){
  Object ctx=body.get("context");String context=ctx==null?null:String.valueOf(ctx);
  if(ctx instanceof java.util.Map<?,?>)try{context=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(ctx);}catch(Exception ignored){context=null;}
  Object reason=body.get("reason");Object detail=body.get("detail");
  return interactions.report(user(p),post,reason==null?null:String.valueOf(reason),detail==null?null:String.valueOf(detail),context);
 }
 @PostMapping("/api/posts/{post}/comments") public InteractionService.CommentResult comment(@PathVariable long post,@RequestHeader("Idempotency-Key")String key,@RequestBody java.util.Map<String,Object> body,Principal p){
  Long parentId=num(body.get("parentId"));
  Long versionId=num(body.get("versionId"));if(versionId==null)versionId=num(body.get("materialVersionId"));
  Long offsetMs=num(body.get("offsetMs"));
  Object rawBody=body.get("body");Object topic=body.get("topic");
  return interactions.comment(user(p),post,rawBody==null?null:String.valueOf(rawBody),key,topic==null?null:String.valueOf(topic),parentId,versionId,offsetMs);
 }
 @GetMapping("/api/posts/{post}/stats") public Object stats(@PathVariable long post,Principal p){user(p);return interactions.stats(post);}
 private static Long num(Object o){
  if(o==null)return null;if(o instanceof Number n)return n.longValue();
  if(o instanceof String s && !s.isBlank())return Long.parseLong(s);return null;
 }
 @GetMapping("/api/topics/trending") public List<TrendingService.Entry> trending(@RequestParam(defaultValue="1h")String window,@RequestParam(defaultValue="net_events")String metric,Principal p){
  long millis=switch(window){case "1h"->3_600_000L;case "24h"->86_400_000L;default->throw new FeedFailure(400,"INVALID_REQUEST");};
  return trending.window(metric,millis,user(p));
 }
 @GetMapping("/api/topics/{topic}/posts") public List<VisibilityService.View> topicPosts(@PathVariable String topic,@RequestParam(defaultValue="50")int limit,Principal p){
  return trending.topicPosts(topic,user(p),limit);
 }
}
