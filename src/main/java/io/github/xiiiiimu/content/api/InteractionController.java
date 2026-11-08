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
 @PostMapping("/api/posts/{post}/comments") public InteractionService.CommentResult comment(@PathVariable long post,@RequestHeader("Idempotency-Key")String key,@RequestBody java.util.Map<String,String> body,Principal p){
  return interactions.comment(user(p),post,body.get("body"),key,body.get("topic"));
 }
 @GetMapping("/api/posts/{post}/stats") public Object stats(@PathVariable long post){return interactions.stats(post);}
 @GetMapping("/api/topics/trending") public List<TrendingService.Entry> trending(@RequestParam(defaultValue="1h")String window,@RequestParam(defaultValue="net_events")String metric,Principal p){
  long millis=switch(window){case "1h"->3_600_000L;case "24h"->86_400_000L;default->throw new FeedFailure(400,"INVALID_REQUEST");};
  return trending.window(metric,millis,user(p));
 }
}
