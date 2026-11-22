package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class PostAuthoringController {
 private final PostAuthoringService posts;
 public PostAuthoringController(PostAuthoringService posts){this.posts=posts;}
 @PostMapping("/api/posts") public PostAuthoringService.Draft create(@RequestBody Map<String,Object> body,Principal p){
  return posts.create(user(p),text(body.get("body")),topic(body.get("topic")));
 }
 /** "topic" absent keeps the stored topic; a string sets it; null or blank clears it. */
 @PatchMapping("/api/posts/{id}") public PostAuthoringService.Draft edit(@PathVariable long id,@RequestBody Map<String,Object> body,Principal p){
  Object v=body.get("expectedVersion");
  if(!(v instanceof Number n) || n.longValue()<0)throw new FeedFailure(400,"INVALID_REQUEST");
  return posts.edit(user(p),id,n.longValue(),text(body.get("body")),body.containsKey("topic"),topic(body.get("topic")));
 }
 private static String text(Object o){return o==null?null:String.valueOf(o);}
 private static String topic(Object o){if(o==null)return null;if(!(o instanceof String s))throw new FeedFailure(400,"INVALID_TOPIC");return s;}
 private static long user(Principal p){
  if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");
  try{long id=Long.parseLong(p.getName());if(id<=0)throw new FeedFailure(401,"UNAUTHENTICATED");return id;}
  catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}
 }
}
