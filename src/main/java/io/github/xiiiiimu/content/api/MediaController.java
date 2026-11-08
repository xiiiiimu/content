package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Base64;
import java.util.Map;

@RestController @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
public class MediaController {
 private final MediaService media;
 public MediaController(MediaService media){this.media=media;}
 private long user(Principal p){if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");try{long id=Long.parseLong(p.getName());if(id<=0)throw new FeedFailure(401,"UNAUTHENTICATED");return id;}catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}}
 @PostMapping("/api/posts/{post}/media")
 public MediaService.Upload upload(@PathVariable long post,@RequestBody Map<String,String> body,Principal p){
  long version=Long.parseLong(body.getOrDefault("expectedVersion","-1"));
  byte[] bytes=Base64.getDecoder().decode(body.getOrDefault("contentBase64",""));
  return media.attach(user(p),post,version,bytes,body.get("contentType"));
 }
 @PostMapping("/api/posts/{post}/media/sign")
 public MediaService.Signed sign(@PathVariable long post,@RequestParam(defaultValue="60000")long ttlMillis,Principal p){return media.sign(user(p),post,ttlMillis);}
}
