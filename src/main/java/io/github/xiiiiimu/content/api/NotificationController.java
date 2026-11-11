package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

@RestController @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class NotificationController {
 private final NotificationService notifications;
 public NotificationController(NotificationService notifications){this.notifications=notifications;}
 @GetMapping("/api/notifications") public List<NotificationService.Item> list(@RequestParam(defaultValue="50")int limit,Principal p){
  return notifications.list(user(p),limit);
 }
 @PostMapping("/api/notifications/{id}/read") public NotificationService.Item read(@PathVariable long id,Principal p){
  return notifications.markRead(user(p),id);
 }
 @GetMapping("/api/notifications/{id}/target") public NotificationService.Target target(@PathVariable long id,Principal p){
  return notifications.target(user(p),id);
 }
 private static long user(Principal p){
  if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");
  try{long id=Long.parseLong(p.getName());if(id<=0)throw new FeedFailure(401,"UNAUTHENTICATED");return id;}
  catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}
 }
}
