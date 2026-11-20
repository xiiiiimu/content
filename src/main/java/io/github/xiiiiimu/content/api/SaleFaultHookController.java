package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.SaleService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * Load-test / correctness fault hooks. Registered only with an explicit {@code content.sale.fault-hooks=true}
 * (and commerce enabled). The condition sits on the class: Spring evaluates {@code @Conditional} on configuration
 * classes and {@code @Bean} methods only, never on handler methods. Not a product API; no authentication.
 */
@RestController @RequestMapping("/api/sale-debug")
@ConditionalOnProperty(name="content.sale.fault-hooks",havingValue="true")
@ConditionalOnProperty(name="content.media.enabled",havingValue="true")
public class SaleFaultHookController {
 private final SaleService sales;
 public SaleFaultHookController(SaleService sales){this.sales=sales;}

 @PostMapping("/hold-settle")
 public Map<String,Object> holdSettle(@RequestBody Map<String,Object> body){
  boolean arm=body.get("arm")==null || Boolean.parseBoolean(String.valueOf(body.get("arm")));
  if(arm)sales.faults().armHold(); else sales.faults().releaseHold();
  return Map.of("holdArmed",sales.faults().holdArmed());
 }

 @PostMapping("/fail-after-commit-once")
 public Map<String,Object> failAfterCommitOnce(){
  sales.faults().armFailAfterCommitOnce();
  return Map.of("armed",true);
 }

 @GetMapping("/inflight")
 public Map<String,Object> inflight(){
  var f=sales.faults();
  return Map.of(
   "settleInFlight",f.inFlight(),"peakSettleInFlight",f.peakInFlight(),
   "httpInFlight",f.httpInFlight(),"peakHttpInFlight",f.peakHttpInFlight(),
   // legacy aliases
   "inFlight",f.inFlight(),"peakInFlight",f.peakInFlight());
 }

 @PostMapping("/reset-inflight-peaks")
 public Map<String,Object> resetInflightPeaks(){
  sales.faults().resetPeakInFlight();
  sales.faults().resetPeakHttpInFlight();
  return Map.of("ok",true);
 }
}
