package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

/**
 * Demo hook: settle a PROCESSING reservation without waiting for MQ. Registered only with an explicit
 * {@code content.sale.manual-settle=true} (and commerce enabled); the condition is class-level because Spring ignores
 * {@code @Conditional} on handler methods. The caller must own the reservation.
 */
@RestController @RequestMapping("/api")
@ConditionalOnProperty(name="content.sale.manual-settle",havingValue="true")
@ConditionalOnProperty(name="content.media.enabled",havingValue="true")
public class SaleManualSettleController {
 private final SaleService sales;
 public SaleManualSettleController(SaleService sales){this.sales=sales;}
 private static long user(Principal p){if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");return Long.parseLong(p.getName());}

 @PostMapping("/purchase-requests/{reservationId}/settle")
 public SaleService.PurchaseView manualSettle(@PathVariable String reservationId,Principal p){
  sales.getRequest(user(p),reservationId);
  sales.settle(reservationId);
  return sales.getRequest(user(p),reservationId);
 }
}
