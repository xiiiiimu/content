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
 /** Ops/TrendGuard service identity only — not a general reader feed. */
 private ContentPrincipal requireOps(Principal p){
  if(!(p instanceof ContentPrincipal cp) || cp.reader()<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  if(!cp.trendGuardOps())throw new FeedFailure(403,"FORBIDDEN");
  return cp;
 }
 @GetMapping("/api/trendguard/risks") public List<TrendGuardService.Risk> risks(
   @RequestParam(defaultValue="1h")String window,
   @RequestParam(required=false)String topic,
   @RequestParam(defaultValue="50")int limit,
   @RequestParam(required=false)Long beforeMs,
   @RequestParam(required=false)String beforeEventId,
   Principal p){
  requireOps(p);
  long millis=switch(window){case "1h"->3_600_000L;case "24h"->86_400_000L;default->throw new FeedFailure(400,"INVALID_REQUEST");};
  return trendGuard.list(millis,topic,limit,beforeMs,beforeEventId);
 }
}
