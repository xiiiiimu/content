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
 @PostMapping("/api/posts") public PostAuthoringService.Draft create(@RequestBody Map<String,String> body,Principal p){
  return posts.create(user(p),body.get("body"));
 }
 @PatchMapping("/api/posts/{id}") public PostAuthoringService.Draft edit(@PathVariable long id,@RequestBody Map<String,Object> body,Principal p){
  Object v=body.get("expectedVersion");
  if(!(v instanceof Number n) || n.longValue()<0)throw new FeedFailure(400,"INVALID_REQUEST");
  Object b=body.get("body");
  return posts.edit(user(p),id,n.longValue(),b==null?null:String.valueOf(b));
 }
 private static long user(Principal p){
  if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");
  try{long id=Long.parseLong(p.getName());if(id<=0)throw new FeedFailure(401,"UNAUTHENTICATED");return id;}
  catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}
 }
}
