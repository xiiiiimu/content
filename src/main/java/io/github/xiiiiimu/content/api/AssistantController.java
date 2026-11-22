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
 @GetMapping("/assistant/tasks/{id}") public AssistantService.TaskView task(@PathVariable long id,Principal p){return assistant.status(user(p),id);}
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
  long viewer=user(p);
  // Hardening A12: a missing or non-integer versionId/offsetMs is a 400, not a NullPointerException/ClassCastException 500.
  Long version=integer(body.get("versionId"));if(version==null)throw new FeedFailure(400,"INVALID_REQUEST");
  Object rawOffset=body.get("offsetMs");Long offset=integer(rawOffset);
  if(rawOffset!=null && offset==null)throw new FeedFailure(400,"INVALID_REQUEST");
  feedback.linkResolution(viewer,post,comment,version,offset);
  return Map.of("ok",true);
 }
 private static Long integer(Object v){
  if(v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte)return ((Number)v).longValue();
  if(v instanceof java.math.BigInteger b && b.bitLength()<64)return b.longValue();
  return null;
 }
 @PostMapping("/posts/{post}/favorite") public GiveawayService.FavoriteResult favorite(@PathVariable long post,Principal p){return giveaways.favorite(user(p),post);}
 @DeleteMapping("/posts/{post}/favorite") public GiveawayService.FavoriteResult unfavorite(@PathVariable long post,Principal p){return giveaways.unfavorite(user(p),post);}
 @GetMapping("/posts/{post}/giveaways") public java.util.List<GiveawayService.GiveawayView> giveaways(@PathVariable long post,@RequestParam(defaultValue="10")int limit,Principal p){
  return giveaways.listForPost(user(p),post,limit);
 }
 @GetMapping("/giveaways/{id}") public GiveawayService.GiveawayView giveaway(@PathVariable long id,Principal p){return giveaways.view(user(p),id);}
 @GetMapping("/posts/{post}/entitlement") public GiveawayService.EntitlementView entitlement(@PathVariable long post,@RequestParam(required=false)Long versionId,Principal p){
  return giveaways.myEntitlement(user(p),post,versionId);
 }
 private static long user(Principal p){if(p==null)throw new FeedFailure(401,"UNAUTHENTICATED");return Long.parseLong(p.getName());}
}
