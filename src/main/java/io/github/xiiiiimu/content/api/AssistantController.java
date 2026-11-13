package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController @RequestMapping("/api") @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
public class AssistantController {
 private final AssistantService assistant;private final FeedbackWatchService feedback;private final GiveawayService giveaways;
 public AssistantController(AssistantService assistant,FeedbackWatchService feedback,GiveawayService giveaways){
  this.assistant=assistant;this.feedback=feedback;this.giveaways=giveaways;
 }
 @GetMapping("/assistant/tasks/{id}") public AssistantService.TaskView task(@PathVariable long id){return assistant.status(id);}
 @PostMapping("/materials/{post}/feedback/{comment}/subscribe")
 public Map<String,Object> subscribe(@PathVariable long post,@PathVariable long comment,Principal p){
  String msg=feedback.subscribe(user(p),comment);return Map.of("ok",true,"message",msg);
 }
 @DeleteMapping("/materials/{post}/feedback/{comment}/subscribe")
 public Map<String,Object> cancel(@PathVariable long post,@PathVariable long comment,Principal p){
  feedback.cancel(user(p),comment);return Map.of("ok",true);
 }
 @PostMapping("/materials/{post}/feedback/{comment}/resolve")
 public Map<String,Object> resolve(@PathVariable long post,@PathVariable long comment,@RequestBody Map<String,Object> body,Principal p){
  long version=((Number)body.get("versionId")).longValue();
  Long offset=body.get("offsetMs")==null?null:((Number)body.get("offsetMs")).longValue();
  feedback.linkResolution(user(p),post,comment,version,offset);
  return Map.of("ok",true);
 }
 @PostMapping("/posts/{post}/favorite") public GiveawayService.FavoriteResult favorite(@PathVariable long post,Principal p){return giveaways.favorite(user(p),post);}
 @DeleteMapping("/posts/{post}/favorite") public GiveawayService.FavoriteResult unfavorite(@PathVariable long post,Principal p){return giveaways.unfavorite(user(p),post);}
 private static long user(Principal p){if(p==null)throw new FeedFailure(401,"UNAUTHENTICATED");return Long.parseLong(p.getName());}
}
