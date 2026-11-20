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
 private final SaleFaultHooks faults;
 private volatile SaleMessageSender sender;
 private SaleIngress ingress;
 public void setIngress(SaleIngress ingress){this.ingress=ingress;}

 public SaleService(SaleMapper db,SaleRedis redis,MaterialMapper materials,FeedMapper posts,NotificationMapper notifications,
                    VisibilityService visibility,TransactionTemplate tx,boolean syncSettle){
  this(db,redis,materials,posts,notifications,visibility,tx,syncSettle,new SaleFaultHooks());
 }
 public SaleService(SaleMapper db,SaleRedis redis,MaterialMapper materials,FeedMapper posts,NotificationMapper notifications,
                    VisibilityService visibility,TransactionTemplate tx,boolean syncSettle,SaleFaultHooks faults){
  this.db=db;this.redis=redis;this.materials=materials;this.posts=posts;this.notifications=notifications;
  this.visibility=visibility;this.tx=tx;this.syncSettle=syncSettle;this.faults=faults==null?new SaleFaultHooks():faults;
 }
 public void setSender(SaleMessageSender sender){this.sender=sender;}
 public SaleFaultHooks faults(){return faults;}

 public CampaignView createCampaign(long merchantId,long postId,long priceCents,int stockCap,long startMs,long endMs){
  ingress.prepare();
  var post=posts.post(postId);if(post==null)throw new FeedFailure(404,"POST_NOT_FOUND");
  if(post.authorId()!=merchantId)throw new FeedFailure(403,"POST_FORBIDDEN");
  var profile=materials.profile(postId);
  if(profile==null)throw new FeedFailure(404,"NOT_MATERIAL");
  if(priceCents<0 || stockCap<=0 || startMs<=0 || endMs<=startMs)throw new FeedFailure(400,"INVALID_CAMPAIGN");
  if(db.openByPost(postId)!=null)throw new FeedFailure(409,"CAMPAIGN_ALREADY_OPEN");
  if(profile.defaultVersionId()==null)throw new FeedFailure(409,"MATERIAL_VERSION_UNAVAILABLE");
  var row=new SaleMapper.CampaignInsert();
  row.post=postId;row.merchant=merchantId;row.price=priceCents;row.cap=stockCap;row.start=startMs;row.end=endMs;
  row.version=profile.defaultVersionId();
  row.token=UUID.randomUUID().toString().replace("-","");row.at=System.currentTimeMillis();
  try{
   tx.executeWithoutResult(s->{
    // Offline uses this same author row before changing the post. Serialize
    // campaign creation with it, then read the current post inside this transaction.
    posts.author(merchantId);
    ingress.guard();
    if(!"PUBLISHED".equals(posts.post(postId).status()))throw new FeedFailure(409,"POST_OFFLINE");
    if(db.insertCampaign(row)!=1)throw new IllegalStateException("campaign");
    ingress.dirty("CAMPAIGN",Long.toString(row.id));
    for(long user:db.giveawayWinnerAccounts(postId))ingress.dirty("OWNER",user+":"+postId);
   });
  }catch(DuplicateKeyException e){throw new FeedFailure(409,"CAMPAIGN_ALREADY_OPEN");}
  for(long user:db.giveawayWinnerAccounts(postId))confirmControl("OWNER",user+":"+postId);
  confirmControl("CAMPAIGN",Long.toString(row.id));
  return view(db.campaign(row.id));
 }
 public boolean hasOpenCampaign(long postId){return db.openByPost(postId)!=null;}

 public CampaignView closeCampaign(long merchantId,long campaignId){
  var c=db.campaign(campaignId);if(c==null)throw new FeedFailure(404,"CAMPAIGN_NOT_FOUND");
  if(c.merchantId()!=merchantId)throw new FeedFailure(403,"CAMPAIGN_FORBIDDEN");
  tx.executeWithoutResult(s->{ingress.guard();db.closeCampaign(campaignId,System.currentTimeMillis());ingress.dirty("CAMPAIGN",Long.toString(campaignId));});
  if(!ingress.sync("CAMPAIGN",Long.toString(campaignId)))throw new FeedFailure(503,"CONTROL_SYNC_PENDING");
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
  try(var timing=SaleLatency.phase("purchase.total")){
  SaleLatency.correlate(clientKey,null);
  if(userId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  if(clientKey==null || clientKey.isBlank() || clientKey.length()>128)throw new FeedFailure(400,"INVALID_CLIENT_KEY");
  try{
   var result=redis.entry().reserve(campaignId,userId,UUID.randomUUID().toString().replace("-",""),clientKey);
   if("UNAVAILABLE".equals(result.status()))throw new FeedFailure(503,result.detail());
   if("REJECT".equals(result.status()))throw new FeedFailure("CAMPAIGN_NOT_FOUND".equals(result.detail())?404:409,result.detail());
   SaleLatency.correlate(clientKey,result.request().reservationId());
   return cacheView(result.request());
  }catch(io.lettuce.core.RedisException e){throw new FeedFailure(503,"SALE_CACHE_UNAVAILABLE");}
  }
 }
 private PurchaseView cacheView(SaleEntryRedis.Request r){return new PurchaseView(r.reservationId(),r.state(),r.rejectReason(),r.orderId(),r.campaignId(),r.postId());}

 public void flushPending(int limit){
  ingress.prepare();ingress.drain(limit);ingress.syncJobs(limit);
  dispatchPending(limit);
 }
 public void dispatchPending(int limit){
  if(sender==null)return;
  for(int i=0;i<Math.min(200,Math.max(1,limit));i++){
   try(var trace=SaleLatency.begin(null,"dispatch",0,0)){
    io.github.xiiiiimu.content.infrastructure.SaleIngressMapper.Dispatch claim;
    try(var phase=SaleLatency.phase("dispatch.claim")){claim=ingress.claim();}
    if(claim==null)return;
    SaleLatency.correlate(null,claim.reservationId());
    try{
     try(var phase=SaleLatency.phase("mq.send")){sender.send(claim.reservationId());}
     try(var phase=SaleLatency.phase("mq.ack.success")){};
     faults.failIfArmed("dispatch.afterAck");
     try(var phase=SaleLatency.phase("dispatch.confirm")){ingress.sent(claim);}
    }catch(Exception e){try(var phase=SaleLatency.phase("dispatch.retry")){ingress.failed(claim);}}
   }
  }
 }

 public PurchaseView getRequest(long userId,String reservationId){
  try{var cached=redis.entry().request(reservationId);if(cached!=null){if(cached.userId()!=userId)throw new FeedFailure(403,"REQUEST_FORBIDDEN");
   if("REJECTED".equals(cached.state())&&"INGRESS_QUARANTINED".equals(cached.rejectReason())&&!redis.entry().quarantineReleased(reservationId))cached=new SaleEntryRedis.Request(cached.reservationId(),cached.campaignId(),cached.postId(),cached.merchantId(),cached.userId(),cached.clientKey(),cached.priceCents(),"PROCESSING",null,null,cached.createdAtMs());
   return cacheView(cached);}}
  catch(io.lettuce.core.RedisException ignored){/* Committed MySQL remains queryable during Redis outage. */}
  var req=db.request(reservationId);
  if(req==null){var canonical=ingress.legacyCanonical(reservationId);if(canonical!=null)req=db.request(canonical);}
  if(req==null)throw new FeedFailure(404,"REQUEST_NOT_FOUND");
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
  faults.awaitHoldIfArmed(120);
  faults.enterInFlight();
  try(var trace=SaleLatency.begin("settle-"+reservationId,"settle",0,0)){SaleLatency.correlate(null,reservationId);settleBody(reservationId);SaleLatency.status(200);}
  finally{faults.leaveInFlight();}
 }

 private void settleBody(String reservationId){
  Boolean release=tx.execute(status->{
   ingress.guard();
   boolean ingressBlocked=ingress.blocked(reservationId);
   var req=db.lockRequest(reservationId);if(req==null)throw new FeedFailure(503,"REQUEST_NOT_PERSISTED");
   SaleLatency.correlate(req.clientKey(),reservationId);
   if(!"PROCESSING".equals(req.state())){db.insertReceipt(reservationId,req.state(),System.currentTimeMillis());return false;}
   if(ingressBlocked)return false; // Durable isolation, never a late successful order or an unproven release.
   if(db.receipt(reservationId)!=null)return false;
   // Upsert then FOR UPDATE the account/material decision row before campaign lock.
   // Refunds and giveaway awards take the same lock, so ownership is stable here.
   db.touchLock(req.userId(),req.postId(),System.currentTimeMillis());
   db.lockDecision(req.userId(),req.postId());
   boolean alreadyOwns=db.alreadyOwnsMaterial(req.userId(),req.postId());
   var campaign=db.lockCampaign(req.campaignId());
   long at=System.currentTimeMillis();
   // Dynamic blacklist remains authoritative in MySQL even if Redis deny cache lags.
   if(db.activeBlacklist(req.merchantId(),req.userId())>0){
    reject(req,"BLACKLISTED",at);return true;
   }
   if(alreadyOwns){reject(req,"ALREADY_OWNED",at);return true;}
   if(campaign==null || !"OPEN".equals(campaign.state())){reject(req,"CAMPAIGN_CLOSED",at);return true;}
   if(!"PUBLISHED".equals(Optional.ofNullable(posts.post(req.postId())).map(FeedMapper.Post::status).orElse(null))){reject(req,"POST_OFFLINE",at);return true;}
   if(at<campaign.startMs()){reject(req,"NOT_STARTED",at);return true;}
   if(at>campaign.endMs()){reject(req,"ENDED",at);return true;}
   Long version=campaign.frozenVersionId();
   if(version==null){
    var profile=materials.profile(req.postId());
    version=profile==null?null:profile.defaultVersionId();
   }
   if(version==null)throw new IllegalStateException("Campaign settlement requires a material version");
   if(db.tryIncrementSold(campaign.id(),at)!=1){reject(req,"SOLD_OUT",at);return true;}
   var order=new SaleMapper.OrderInsert();
   order.reservationId=req.reservationId();order.campaignId=req.campaignId();order.postId=req.postId();
   order.merchantId=req.merchantId();order.userId=req.userId();order.priceCents=req.priceCents();order.paidAt=at;
   try{db.insertOrder(order);}
   catch(DuplicateKeyException e){reject(req,"ALREADY_OWNED",at);db.tryDecrementSold(campaign.id(),at);return true;}
   var ent=new SaleMapper.EntitlementInsert();
   ent.account=req.userId();ent.post=req.postId();ent.version=version;ent.license="DOWNLOAD";
   ent.kind="PURCHASE";ent.source=order.id;ent.at=at;
   if(db.insertEntitlement(ent)!=1 || db.finishRequest(reservationId,"SUCCEEDED",null,order.id,at)!=1
      || db.insertReceipt(reservationId,"SUCCEEDED",at)!=1)throw new IllegalStateException("Incomplete purchase settlement");
   ingress.dirty("OWNER",req.userId()+":"+req.postId());ingress.dirty("REQUEST",reservationId);faults.failIfArmed("settle.beforeCommit");
   return false;
  });
  var req=db.request(reservationId);
  if(req==null)return;
  ingress.bestEffort("REQUEST",reservationId);ingress.bestEffort("OWNER",req.userId()+":"+req.postId());
 }

 private void reject(SaleMapper.PurchaseRequest req,String reason,long at){
  db.finishRequest(req.reservationId(),"REJECTED",reason,null,at);
  db.insertReceipt(req.reservationId(),"REJECTED:"+reason,at);
  ingress.dirty("REQUEST",req.reservationId());ingress.dirty("OWNER",req.userId()+":"+req.postId());
 }

 public void block(long merchantId,long targetUserId,String reason){
  if(targetUserId<=0 || targetUserId==merchantId)throw new FeedFailure(400,"INVALID_TARGET");
  long at=System.currentTimeMillis();
  tx.executeWithoutResult(s->{ingress.guard();db.upsertBlacklist(merchantId,targetUserId,reason==null?"":reason,merchantId,at);ingress.dirty("BLACKLIST",merchantId+":"+targetUserId);});
  confirmControl("BLACKLIST",merchantId+":"+targetUserId);
 }
 public void unblock(long merchantId,long targetUserId){
  tx.executeWithoutResult(s->{ingress.guard();db.revokeBlacklist(merchantId,targetUserId,System.currentTimeMillis());ingress.dirty("BLACKLIST",merchantId+":"+targetUserId);});
  confirmControl("BLACKLIST",merchantId+":"+targetUserId);
 }
 private void confirmControl(String kind,String key){try{if(!ingress.sync(kind,key))throw new FeedFailure(503,"CONTROL_SYNC_PENDING");}catch(io.lettuce.core.RedisException e){throw new FeedFailure(503,"CONTROL_SYNC_PENDING");}}
 /** Joins the authoritative offline transaction; no Redis operation can roll it back after commit. */
 public void offlineInTransaction(long post){
  var campaigns=db.openCampaignIdsForPost(post);
  if(campaigns.isEmpty())return; // Unrelated material operations need no sale runtime.
  ingress.guard();
  for(long id:campaigns){db.closeCampaign(id,System.currentTimeMillis());ingress.dirty("CAMPAIGN",Long.toString(id));ingress.afterCommit("CAMPAIGN",Long.toString(id));}
 }
 public boolean isBlocked(long merchantId,long targetUserId){return db.activeBlacklist(merchantId,targetUserId)>0;}

 public long requestRefund(long buyerId,long orderId){
  return tx.execute(status->{
   ingress.guard();
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
   ingress.guard();
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
   ingress.guard();
   var snapshot=db.refund(refundId);if(snapshot==null)throw new FeedFailure(404,"REFUND_NOT_FOUND");
   var before=db.order(snapshot.orderId());if(before==null)throw new FeedFailure(404,"ORDER_NOT_FOUND");
   if(before.merchantId()!=actorId&&!forced)throw new FeedFailure(403,"ORDER_FORBIDDEN");
   db.touchLock(before.userId(),before.postId(),System.currentTimeMillis());db.lockDecision(before.userId(),before.postId());
   var rr=db.lockRefund(refundId);var o=db.lockOrder(rr.orderId());
   if("SUCCEEDED".equals(rr.state()))return new long[]{o.id(),o.campaignId(),o.userId(),0};
   if(!"PENDING".equals(rr.state()))throw new FeedFailure(409,"REFUND_NOT_PENDING");
   long at=System.currentTimeMillis();
   String kind=rr.reasonKind();
   if(forced && !"GIVEAWAY_AUTO".equals(kind))kind="BUYER_FORCED";
   if(db.markRefunded(o.id(),kind,at)!=1)throw new FeedFailure(409,"ALREADY_REFUNDED");
   db.revokePurchaseEntitlement(o.id(),o.userId(),at);
   var c=db.lockCampaign(o.campaignId());
   boolean restoreRedis=c!=null && "OPEN".equals(c.state()) && at<=c.endMs();
   if(restoreRedis)db.tryDecrementSold(c.id(),at);
   db.finishRefund(refundId,"SUCCEEDED",actorId,1,at);
   ingress.recordRefundRestore(refundId,restoreRedis);ingress.dirty("OWNER",o.userId()+":"+o.postId());
   String dedupe="order-refund:"+o.id()+":"+kind;
   long synthetic=Math.floorMod(Objects.hash(o.id(),kind),1_000_000_000)+4_000_000_000L;
   notifications.insertWithMeta(o.userId(),synthetic,"ORDER_REFUNDED",o.merchantId(),o.postId(),0L,null,null,dedupe,at);
   return new long[]{o.id(),o.campaignId(),o.userId(),restoreRedis?1:0};
  });
  ingress.bestEffort("REFUND",Long.toString(refundId));
  var latest=db.order(meta[0]);ingress.bestEffort("OWNER",latest.userId()+":"+latest.postId());
  return toOrder(db.order(meta[0]));
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
