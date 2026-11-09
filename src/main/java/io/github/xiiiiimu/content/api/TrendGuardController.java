package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

@RestController @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class TrendGuardController {
 private final TrendGuardService trendGuard;
 public TrendGuardController(TrendGuardService trendGuard){this.trendGuard=trendGuard;}
 private long user(Principal p){if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");try{long id=Long.parseLong(p.getName());if(id<=0)throw new FeedFailure(401,"UNAUTHENTICATED");return id;}catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}}
 @GetMapping("/api/trendguard/risks") public List<TrendGuardService.Risk> risks(
   @RequestParam(defaultValue="1h")String window,
   @RequestParam(required=false)String topic,
   @RequestParam(defaultValue="50")int limit,
   @RequestParam(required=false)Long beforeMs,
   @RequestParam(required=false)String beforeEventId,
   Principal p){
  user(p);
  long millis=switch(window){case "1h"->3_600_000L;case "24h"->86_400_000L;default->throw new FeedFailure(400,"INVALID_REQUEST");};
  return trendGuard.list(millis,topic,limit,beforeMs,beforeEventId);
 }
}
