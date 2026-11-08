package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class PostController {
 private final PostDetailService details;
 public PostController(PostDetailService details){this.details=details;}
 @GetMapping("/api/posts/{id}")
 public VisibilityService.View get(@PathVariable long id,Principal p){
  if(p==null || p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");
  long viewer;try{viewer=Long.parseLong(p.getName());}catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}
  if(viewer<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  return details.get(viewer,id);
 }
}
