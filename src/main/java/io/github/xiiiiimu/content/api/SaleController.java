package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController @RequestMapping("/api") @ConditionalOnProperty(name="content.media.enabled",havingValue="true")
public class SaleController {
 private final SaleService sales;
 public SaleController(SaleService sales){this.sales=sales;}
 private static long user(Principal p){if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");return Long.parseLong(p.getName());}

 @PostMapping("/materials/{post}/sale-campaigns")
 public SaleService.CampaignView create(@PathVariable long post,@RequestBody Map<String,Object> body,Principal p){
  return sales.createCampaign(user(p),post,num(body.get("priceCents"),0), (int)num(body.get("stockCap"),0),
   num(body.get("startMs"),0), num(body.get("endMs"),0));
 }
 @GetMapping("/materials/{post}/sale-campaigns/current")
 public SaleService.CampaignView current(@PathVariable long post,Principal p){return sales.currentForPost(user(p),post);}
 @PostMapping("/sale-campaigns/{id}/close")
 public SaleService.CampaignView close(@PathVariable long id,Principal p){return sales.closeCampaign(user(p),id);}
 @GetMapping("/sale-links/{token}")
 public SaleService.CampaignView byToken(@PathVariable String token,Principal p){return sales.byToken(user(p),token);}

 @PostMapping("/sale-campaigns/{id}/purchase")
 public SaleService.PurchaseView purchase(@PathVariable long id,@RequestBody Map<String,Object> body,Principal p){
  return sales.purchase(user(p),id,str(body.get("clientKey")));
 }
 @GetMapping("/purchase-requests/{reservationId}")
 public SaleService.PurchaseView request(@PathVariable String reservationId,Principal p){return sales.getRequest(user(p),reservationId);}
 @GetMapping("/materials/{post}/orders/me")
 public SaleService.OrderView myOrder(@PathVariable long post,Principal p){return sales.myOrder(user(p),post);}
 @GetMapping("/orders/{id}")
 public SaleService.OrderView order(@PathVariable long id,Principal p){return sales.getOrder(user(p),id);}

 @PostMapping("/orders/{id}/refund-requests")
 public Map<String,Object> requestRefund(@PathVariable long id,Principal p){
  return Map.of("refundRequestId",sales.requestRefund(user(p),id));
 }
 @PostMapping("/refund-requests/{id}/approve")
 public SaleService.OrderView approve(@PathVariable long id,Principal p){return sales.approveRefund(user(p),id);}
 @PostMapping("/orders/{id}/force-refund")
 public SaleService.OrderView force(@PathVariable long id,Principal p){return sales.forceRefund(user(p),id);}

 @PutMapping("/merchants/me/blacklist/{targetUserId}")
 public Map<String,Object> block(@PathVariable long targetUserId,@RequestBody(required=false) Map<String,Object> body,Principal p){
  sales.block(user(p),targetUserId,body==null?null:str(body.get("reason")));
  return Map.of("ok",true);
 }
 @DeleteMapping("/merchants/me/blacklist/{targetUserId}")
 public Map<String,Object> unblock(@PathVariable long targetUserId,Principal p){
  sales.unblock(user(p),targetUserId);return Map.of("ok",true);
 }

 /** Demo/fault hook: settle a PROCESSING reservation without waiting for MQ (enabled only when configured). */
 @PostMapping("/purchase-requests/{reservationId}/settle")
 @ConditionalOnProperty(name="content.sale.manual-settle",havingValue="true")
 public SaleService.PurchaseView manualSettle(@PathVariable String reservationId,Principal p){
  var view=sales.getRequest(user(p),reservationId);
  sales.settle(reservationId);
  return sales.getRequest(user(p),reservationId);
 }

 private static String str(Object o){return o==null?null:String.valueOf(o);}
 private static long num(Object o,long d){if(o==null)return d;if(o instanceof Number n)return n.longValue();try{return Long.parseLong(String.valueOf(o));}catch(Exception e){return d;}}
}
