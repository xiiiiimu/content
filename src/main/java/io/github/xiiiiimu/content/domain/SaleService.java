package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Limited-sale campaigns: Redis Lua reserve → MQ/async settle → MySQL order + PURCHASE entitlement. */
public final class SaleService {
 public record CampaignView(long id,long postId,long merchantId,long priceCents,int stockCap,int soldCount,
                            long startMs,long endMs,String state,String linkToken,Long remainingStock) {}
 public record PurchaseView(String reservationId,String state,String rejectReason,Long orderId,long campaignId,long postId) {}
 public record OrderView(long id,long postId,long merchantId,long priceCents,String state,long paidAtMs,Long refundedAtMs,String refundReason) {}

 private final SaleMapper db;private final SaleRedis redis;private final MaterialMapper materials;private final FeedMapper posts;
 private final NotificationMapper notifications;private final VisibilityService visibility;private final TransactionTemplate tx;
 private final boolean syncSettle;
 private volatile SaleMessageSender sender;

 public SaleService(SaleMapper db,SaleRedis redis,MaterialMapper materials,FeedMapper posts,NotificationMapper notifications,
                    VisibilityService visibility,TransactionTemplate tx,boolean syncSettle){
  this.db=db;this.redis=redis;this.materials=materials;this.posts=posts;this.notifications=notifications;
  this.visibility=visibility;this.tx=tx;this.syncSettle=syncSettle;
 }
 public void setSender(SaleMessageSender sender){this.sender=sender;}

 public CampaignView createCampaign(long merchantId,long postId,long priceCents,int stockCap,long startMs,long endMs){
  var post=posts.post(postId);if(post==null)throw new FeedFailure(404,"POST_NOT_FOUND");
  if(post.authorId()!=merchantId)throw new FeedFailure(403,"POST_FORBIDDEN");
  if(materials.profile(postId)==null)throw new FeedFailure(404,"NOT_MATERIAL");
  if(priceCents<0 || stockCap<=0 || startMs<=0 || endMs<=startMs)throw new FeedFailure(400,"INVALID_CAMPAIGN");
  if(db.openByPost(postId)!=null)throw new FeedFailure(409,"CAMPAIGN_ALREADY_OPEN");
  var row=new SaleMapper.CampaignInsert();
  row.post=postId;row.merchant=merchantId;row.price=priceCents;row.cap=stockCap;row.start=startMs;row.end=endMs;
  row.token=UUID.randomUUID().toString().replace("-","");row.at=System.currentTimeMillis();
  try{tx.executeWithoutResult(s->{if(db.insertCampaign(row)!=1)throw new IllegalStateException("campaign");});}
  catch(DuplicateKeyException e){throw new FeedFailure(409,"CAMPAIGN_ALREADY_OPEN");}
  redis.openCampaign(row.id,postId,merchantId,priceCents,stockCap,startMs,endMs);
  return view(db.campaign(row.id));
 }

 public CampaignView closeCampaign(long merchantId,long campaignId){
  var c=db.campaign(campaignId);if(c==null)throw new FeedFailure(404,"CAMPAIGN_NOT_FOUND");
  if(c.merchantId()!=merchantId)throw new FeedFailure(403,"CAMPAIGN_FORBIDDEN");
  tx.executeWithoutResult(s->db.closeCampaign(campaignId,System.currentTimeMillis()));
  redis.closeCampaign(campaignId);
  return view(db.campaign(campaignId));
 }

 public CampaignView currentForPost(long viewer,long postId){
  visibility.read(viewer,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  var c=db.openByPost(postId);return c==null?null:view(c);
 }
 public CampaignView byToken(long viewer,String token){
  var c=db.byToken(token);if(c==null)throw new FeedFailure(404,"CAMPAIGN_NOT_FOUND");
  visibility.read(viewer,c.postId()).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  return view(c);
 }
 private CampaignView view(SaleMapper.Campaign c){
  Long rem=null;try{rem=redis.remainingStock(c.id());}catch(Exception ignored){}
  if(rem==null)rem=(long)Math.max(0,c.stockCap()-c.soldCount());
  return new CampaignView(c.id(),c.postId(),c.merchantId(),c.priceCents(),c.stockCap(),c.soldCount(),c.startMs(),c.endMs(),c.state(),c.linkToken(),rem);
 }

 public PurchaseView purchase(long userId,long campaignId,String clientKey){
  if(clientKey==null || clientKey.isBlank() || clientKey.length()>128)throw new FeedFailure(400,"INVALID_CLIENT_KEY");
  if(redis.gatewayPaused())throw new FeedFailure(503,"GATEWAY_PAUSED");
  var existing=db.byClient(userId,clientKey);
  if(existing!=null)return toPurchase(existing);
  var campaign=db.campaign(campaignId);if(campaign==null)throw new FeedFailure(404,"CAMPAIGN_NOT_FOUND");
  if(!"OPEN".equals(campaign.state()))throw new FeedFailure(409,"CAMPAIGN_CLOSED");
  visibility.read(userId,campaign.postId()).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  String candidateId=UUID.randomUUID().toString().replace("-","");
  var lua=redis.reserve(campaignId,userId,candidateId,clientKey,System.currentTimeMillis());
  if("REJECT".equals(lua.status()))throw new FeedFailure(409,lua.detail()==null?"RESERVE_REJECTED":lua.detail());
  if("EXISTING".equals(lua.status())){
   var req=db.request(lua.reservationId());
   if(req!=null)return toPurchase(req);
  }
  final String reservationId=lua.reservationId()!=null?lua.reservationId():candidateId;
  try{
   long at=System.currentTimeMillis();
   tx.executeWithoutResult(s->db.insertRequest(reservationId,campaignId,campaign.postId(),campaign.merchantId(),userId,clientKey,campaign.priceCents(),at));
  }catch(DuplicateKeyException e){
   var again=db.byClient(userId,clientKey);if(again!=null)return toPurchase(again);
   throw e;
  }
  if(syncSettle)settle(reservationId);
  else enqueue(reservationId);
  return toPurchase(db.request(reservationId));
 }

 private void enqueue(String reservationId){
  if(sender!=null){
   try{sender.send(reservationId);db.markMqSent(reservationId,System.currentTimeMillis());}
   catch(Exception e){/* pending sender / tick will retry */}
  }
 }

 public void flushPending(int limit){
  for(var req:db.pendingMq(Math.max(1,limit))){
   try{
    if(sender!=null)sender.send(req.reservationId());
    db.markMqSent(req.reservationId(),System.currentTimeMillis());
    if(syncSettle)settle(req.reservationId());
   }catch(Exception ignored){}
  }
 }

 public PurchaseView getRequest(long userId,String reservationId){
  var req=db.request(reservationId);if(req==null)throw new FeedFailure(404,"REQUEST_NOT_FOUND");
  if(req.userId()!=userId)throw new FeedFailure(403,"REQUEST_FORBIDDEN");
  return toPurchase(req);
 }
 private PurchaseView toPurchase(SaleMapper.PurchaseRequest r){
  return new PurchaseView(r.reservationId(),r.state(),r.rejectReason(),r.orderId(),r.campaignId(),r.postId());
 }

 public OrderView myOrder(long userId,long postId){
  visibility.read(userId,postId).orElseThrow(()->new FeedFailure(404,"POST_NOT_VISIBLE"));
  var o=db.orderByUserPost(userId,postId);if(o==null)return null;
  return toOrder(o);
 }
 public OrderView getOrder(long viewer,long orderId){
  var o=db.order(orderId);if(o==null)throw new FeedFailure(404,"ORDER_NOT_FOUND");
  if(o.userId()!=viewer && o.merchantId()!=viewer)throw new FeedFailure(403,"ORDER_FORBIDDEN");
  return toOrder(o);
 }
 private OrderView toOrder(SaleMapper.Order o){
  return new OrderView(o.id(),o.postId(),o.merchantId(),o.priceCents(),o.state(),o.paidAtMs(),o.refundedAtMs(),o.refundReason());
 }

 /** Durable settle; idempotent on reservationId. */
 public void settle(String reservationId){
  Boolean release=tx.execute(status->{
   var req=db.lockRequest(reservationId);if(req==null)return false;
   if(!"PROCESSING".equals(req.state())){db.insertReceipt(reservationId,req.state(),System.currentTimeMillis());return false;}
   if(db.receipt(reservationId)!=null)return false;
   db.touchLock(req.userId(),req.postId(),System.currentTimeMillis());
   db.lockDecision(req.userId(),req.postId());
   var campaign=db.lockCampaign(req.campaignId());
   long at=System.currentTimeMillis();
   if(db.activeBlacklist(req.merchantId(),req.userId())>0){
    reject(req,"BLACKLISTED",at);return true;
   }
   var existingOrder=db.orderByUserPost(req.userId(),req.postId());
   if(existingOrder!=null){reject(req,"ALREADY_OWNED",at);return true;}
   var ents=db.activeEntitlements(req.userId(),req.postId());
   if(ents.stream().anyMatch(e->"GIVEAWAY".equals(e.sourceKind()))){reject(req,"ALREADY_OWNED",at);return true;}
   if(campaign==null || !"OPEN".equals(campaign.state())){reject(req,"CAMPAIGN_CLOSED",at);return true;}
   if(at<campaign.startMs()){reject(req,"NOT_STARTED",at);return true;}
   if(at>campaign.endMs()){reject(req,"ENDED",at);return true;}
   if(db.tryIncrementSold(campaign.id(),at)!=1){reject(req,"SOLD_OUT",at);return true;}
   var order=new SaleMapper.OrderInsert();
   order.reservationId=req.reservationId();order.campaignId=req.campaignId();order.postId=req.postId();
   order.merchantId=req.merchantId();order.userId=req.userId();order.priceCents=req.priceCents();order.paidAt=at;
   try{db.insertOrder(order);}
   catch(DuplicateKeyException e){reject(req,"ALREADY_OWNED",at);db.tryDecrementSold(campaign.id(),at);return true;}
   var profile=materials.profile(req.postId());
   Long version=profile==null?null:profile.defaultVersionId();
   if(version!=null){
    var ent=new SaleMapper.EntitlementInsert();
    ent.account=req.userId();ent.post=req.postId();ent.version=version;ent.license="DOWNLOAD";
    ent.kind="PURCHASE";ent.source=order.id;ent.at=at;
    try{db.insertEntitlement(ent);}catch(DuplicateKeyException ignored){}
   }
   db.finishRequest(reservationId,"SUCCEEDED",null,order.id,at);
   db.insertReceipt(reservationId,"SUCCEEDED",at);
   return false;
  });
  var req=db.request(reservationId);
  if(req==null)return;
  if(Boolean.TRUE.equals(release))redis.release(req.campaignId(),reservationId,req.userId(),req.rejectReason());
  else if("SUCCEEDED".equals(req.state()))redis.markSucceeded(req.campaignId(),reservationId);
 }

 private void reject(SaleMapper.PurchaseRequest req,String reason,long at){
  db.finishRequest(req.reservationId(),"REJECTED",reason,null,at);
  db.insertReceipt(req.reservationId(),"REJECTED:"+reason,at);
 }

 public void block(long merchantId,long targetUserId,String reason){
  if(targetUserId<=0 || targetUserId==merchantId)throw new FeedFailure(400,"INVALID_TARGET");
  db.upsertBlacklist(merchantId,targetUserId,reason==null?"":reason,merchantId,System.currentTimeMillis());
 }
 public void unblock(long merchantId,long targetUserId){
  db.revokeBlacklist(merchantId,targetUserId,System.currentTimeMillis());
 }
 public boolean isBlocked(long merchantId,long targetUserId){return db.activeBlacklist(merchantId,targetUserId)>0;}

 public long requestRefund(long buyerId,long orderId){
  return tx.execute(status->{
   var o=db.lockOrder(orderId);if(o==null)throw new FeedFailure(404,"ORDER_NOT_FOUND");
   if(o.userId()!=buyerId)throw new FeedFailure(403,"ORDER_FORBIDDEN");
   if(!"PAID".equals(o.state()))throw new FeedFailure(409,"ORDER_NOT_PAID");
   if(db.succeededRefund(orderId)!=null)throw new FeedFailure(409,"ALREADY_REFUNDED");
   if(db.pendingRefund(orderId)!=null)throw new FeedFailure(409,"REFUND_PENDING");
   var row=new SaleMapper.RefundInsert();row.order=orderId;row.kind="BUYER_REQUESTED";row.requester=buyerId;row.at=System.currentTimeMillis();
   db.insertRefund(row);return row.id;
  });
 }

 public OrderView approveRefund(long merchantId,long refundId){
  return completeRefund(merchantId,refundId,false);
 }
 public OrderView forceRefund(long actorId,long orderId){
  Long refundId=tx.execute(status->{
   var o=db.lockOrder(orderId);if(o==null)throw new FeedFailure(404,"ORDER_NOT_FOUND");
   if(o.merchantId()!=actorId && o.userId()!=actorId)throw new FeedFailure(403,"ORDER_FORBIDDEN");
   if(!"PAID".equals(o.state()))throw new FeedFailure(409,"ORDER_NOT_PAID");
   if(db.succeededRefund(orderId)!=null)throw new FeedFailure(409,"ALREADY_REFUNDED");
   var pending=db.pendingRefund(orderId);
   if(pending!=null)return pending.id();
   var row=new SaleMapper.RefundInsert();row.order=orderId;row.kind="BUYER_FORCED";row.requester=actorId;row.at=System.currentTimeMillis();
   db.insertRefund(row);return row.id;
  });
  return completeRefund(actorId,refundId,true);
 }

 private OrderView completeRefund(long actorId,long refundId,boolean forced){
  long[] meta=tx.execute(status->{
   var rr=db.lockRefund(refundId);if(rr==null)throw new FeedFailure(404,"REFUND_NOT_FOUND");
   if(!"PENDING".equals(rr.state()))throw new FeedFailure(409,"REFUND_NOT_PENDING");
   var o=db.lockOrder(rr.orderId());if(o==null)throw new FeedFailure(404,"ORDER_NOT_FOUND");
   if(o.merchantId()!=actorId && !forced)throw new FeedFailure(403,"ORDER_FORBIDDEN");
   db.touchLock(o.userId(),o.postId(),System.currentTimeMillis());
   db.lockDecision(o.userId(),o.postId());
   long at=System.currentTimeMillis();
   String kind=rr.reasonKind();
   if(forced && !"GIVEAWAY_AUTO".equals(kind))kind="BUYER_FORCED";
   if(db.markRefunded(o.id(),kind,at)!=1)throw new FeedFailure(409,"ALREADY_REFUNDED");
   db.revokePurchaseEntitlement(o.id(),o.userId(),at);
   var c=db.lockCampaign(o.campaignId());
   boolean restoreRedis=c!=null && "OPEN".equals(c.state());
   if(restoreRedis)db.tryDecrementSold(c.id(),at);
   db.finishRefund(refundId,"SUCCEEDED",actorId,1,at);
   String dedupe="order-refund:"+o.id()+":"+kind;
   long synthetic=Math.floorMod(Objects.hash(o.id(),kind),1_000_000_000)+4_000_000_000L;
   notifications.insertWithMeta(o.userId(),synthetic,"ORDER_REFUNDED",o.merchantId(),o.postId(),0L,null,null,dedupe,at);
   return new long[]{o.id(),o.campaignId(),o.userId(),restoreRedis?1:0};
  });
  if(meta[3]==1){
   try{restoreRedisStock(meta[1],meta[0],meta[2]);}catch(Exception ignored){}
  }
  return toOrder(db.order(meta[0]));
 }

 /** Idempotent stock restore after refund while campaign still OPEN. */
 private void restoreRedisStock(long campaignId,long orderId,long userId){
  String rid="refund-stock-"+orderId;
  redis.connectionSeedForRelease(campaignId,rid,userId);
  redis.release(campaignId,rid,userId,"REFUND_STOCK");
 }

 /**
  * Called under giveaway award lock path: if winner has PAID order on post, auto-refund once.
  * Must be invoked while account_material_decision_locks is held by caller OR we lock here.
  */
 /** Prefer calling from ensureAwards; joins ambient tx when present (avoid nested timeout). */
 public void autoRefundOnGiveawayWin(long accountId,long postId,long giveawayId){
  Runnable body=()->{
   db.touchLock(accountId,postId,System.currentTimeMillis());
   db.lockDecision(accountId,postId);
   var o=db.orderByUserPost(accountId,postId);
   if(o==null || !"PAID".equals(o.state()))return;
   if(db.succeededRefund(o.id())!=null)return;
   var pending=db.pendingRefund(o.id());
   long refundId;
   if(pending!=null){
    refundId=pending.id();
   }else{
    var row=new SaleMapper.RefundInsert();row.order=o.id();row.kind="GIVEAWAY_AUTO";row.requester=0;row.at=System.currentTimeMillis();
    db.insertRefund(row);refundId=row.id;
   }
   long at=System.currentTimeMillis();
   if(db.markRefunded(o.id(),"GIVEAWAY_AUTO",at)!=1)return;
   db.revokePurchaseEntitlement(o.id(),accountId,at);
   var c=db.lockCampaign(o.campaignId());
   if(c!=null && "OPEN".equals(c.state()))db.tryDecrementSold(c.id(),at);
   db.finishRefund(refundId,"SUCCEEDED",0L,1,at);
   String dedupe="order-refund:"+o.id()+":GIVEAWAY_AUTO";
   long synthetic=Math.floorMod(Objects.hash(o.id(),"GIVEAWAY_AUTO",giveawayId),1_000_000_000)+4_000_000_000L;
   notifications.insertWithMeta(accountId,synthetic,"ORDER_REFUNDED",o.merchantId(),postId,0L,null,null,dedupe,at);
  };
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())body.run();
  else tx.executeWithoutResult(status->body.run());
 }

 public List<Long> purchaseEligibleAccounts(long merchantId,Long postId,Long windowStartMs,Long deadlineMs){
  try{return db.purchaseEligible(merchantId,postId,windowStartMs,deadlineMs);}
  catch(RuntimeException e){throw new FeedFailure(409,"PURCHASE_SOURCE_UNAVAILABLE");}
 }
}
