package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * Load-test / fault-hooks only: create a giveaway via the same service path as the assistant IT,
 * then seal+draw so GIVEAWAY_AUTO refunds commit. Not a product public API.
 */
@RestController
@RequestMapping("/api/sale-debug")
@ConditionalOnProperty(name="content.sale.fault-hooks",havingValue="true")
@ConditionalOnProperty(name="content.media.enabled",havingValue="true")
public class SaleGiveawayDebugController {
 private final GiveawayService giveaways;
 private final InteractionService interactions;
 private final io.github.xiiiiimu.content.infrastructure.GiveawayMapper giveawayDb;

 public SaleGiveawayDebugController(GiveawayService giveaways,InteractionService interactions,
                                    io.github.xiiiiimu.content.infrastructure.GiveawayMapper giveawayDb){
  this.giveaways=giveaways;this.interactions=interactions;this.giveawayDb=giveawayDb;
 }

 private static long user(Principal p){
  if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");
  return Long.parseLong(p.getName());
 }

 @PostMapping("/giveaway-auto-refund")
 public Map<String,Object> createSealDraw(@RequestBody Map<String,Object> body,Principal p)throws Exception{
  long author=user(p);
  long postId=num(body.get("postId"),0);
  if(postId<=0)throw new FeedFailure(400,"INVALID_REQUEST");
  int winners=(int)num(body.get("winnerCount"),1);
  long deadlineMs=num(body.get("deadlineMs"),System.currentTimeMillis()+3_000);
  if(deadlineMs<=System.currentTimeMillis())deadlineMs=System.currentTimeMillis()+2_000;
  String elig=str(body.get("eligibility"));
  if(!"LIKE".equals(elig))elig="PURCHASE";
  String rules="{\"op\":\"ALL\",\"children\":[{\"type\":\""+elig+"\"}]}";
  String key="gwa-debug-"+UUID.randomUUID();
  var comment=interactions.comment(author,postId,"@助手 load-test giveaway",key,"general");
  var cmd=new AssistantParser.GiveawayCommand(rules,"Asia/Shanghai",null,deadlineMs,winners,"DEFAULT","DOWNLOAD","load-test-auto-refund");
  giveaways.createFromAssistant(author,postId,comment.commentId(),cmd);
  var g=giveawayDb.bySource(comment.commentId());
  if(g==null)throw new FeedFailure(500,"GIVEAWAY_CREATE_FAILED");
  long wait=Math.max(0,deadlineMs-System.currentTimeMillis())+200;
  Thread.sleep(Math.min(wait,30_000));
  giveaways.seal(g.id());
  giveaways.draw(g.id());
  giveaways.draw(g.id());
  return Map.of(
   "ok",true,
   "giveawayId",g.id(),
   "sourceCommentId",comment.commentId(),
   "deadlineMs",deadlineMs,
   "winnerCount",winners,
   "eligibility",elig);
 }

 private static String str(Object o){return o==null?null:String.valueOf(o);}
 private static long num(Object o,long d){
  if(o==null)return d;if(o instanceof Number n)return n.longValue();
  try{return Long.parseLong(String.valueOf(o));}catch(Exception e){return d;}
 }
}
