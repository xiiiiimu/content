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
 public record OrderView(long id,long postId,long merchantId,long priceCents,String state,long paidAtMs,Long refundedAtMs,String refundReason,boolean giveawayRefundEligible) {}

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
 public SaleMessageSender sender(){return sender;}
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
  if(viewer<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  var c=db.openByPost(postId);return c==null?null:view(c);
 }
 public CampaignView byToken(long viewer,String token){
  var c=db.byToken(token);if(c==null)throw new FeedFailure(404,"CAMPAIGN_NOT_FOUND");
  if(viewer<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
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
   if("REJECT".equals(result.status())&&"CAMPAIGN_NOT_FOUND".equals(result.detail())){var archived=archivedPurchase(userId,campaignId,clientKey);if(archived!=null)return archived;}
   if("REJECT".equals(result.status()))throw new FeedFailure("CAMPAIGN_NOT_FOUND".equals(result.detail())?404:409,result.detail());
   SaleLatency.correlate(clientKey,result.request().reservationId());
   return cacheView(result.request());
  }catch(io.lettuce.core.RedisException e){throw new FeedFailure(503,"SALE_CACHE_UNAVAILABLE");}
  }
 }
 /**
  * Hardening C3: Redis no longer knows an archived campaign. Only on this path (the Lua script found no campaign) MySQL is
  * read: a replay of this account's key in that campaign returns the original request; anything else keeps the 409 the
  * campaign had before it was archived. Active campaigns never reach this code, so their entry stays SQL-free.
  */
 private PurchaseView archivedPurchase(long userId,long campaignId,String clientKey){
  if(ingress==null||!ingress.campaignArchived(campaignId))return null;
  var replay=ingress.archivedRequest(userId,campaignId,clientKey);
  if(replay!=null)return toPurchase(replay);
  var c=db.campaign(campaignId);
  throw new FeedFailure(409,c!=null&&"CLOSED".equals(c.state())?"CAMPAIGN_CLOSED":"ENDED");
 }
 private PurchaseView cacheView(SaleEntryRedis.Request r){return new PurchaseView(r.reservationId(),r.state(),r.rejectReason(),r.orderId(),r.campaignId(),r.postId());}

 public void flushPending(int limit){
  ingress.prepare();ingress.drain(limit);ingress.syncJobs(limit);
  dispatchPending(limit);
 }
 /** Serial dispatch; returns the number of rows claimed (B4: 0 means the pass found no work). */
 public int dispatchPending(int limit){
  if(sender==null)return 0;
  int claimed=0;
  for(int i=0;i<Math.min(200,Math.max(1,limit));i++){
   try(var trace=SaleLatency.begin(null,"dispatch",0,0)){
    io.github.xiiiiimu.content.infrastructure.SaleIngressMapper.Dispatch claim;
    try(var phase=SaleLatency.phase("dispatch.claim")){claim=ingress.claim();}
    if(claim==null)return claimed;
    claimed++;
    SaleLatency.correlate(null,claim.reservationId());
    try{
     try(var phase=SaleLatency.phase("mq.send")){sender.send(claim.reservationId());}
     try(var phase=SaleLatency.phase("mq.ack.success")){};
     faults.failIfArmed("dispatch.afterAck");
     try(var phase=SaleLatency.phase("dispatch.confirm")){ingress.sent(claim);}
    }catch(Exception e){try(var phase=SaleLatency.phase("dispatch.retry")){ingress.failed(claim);}}
   }
  }
  return claimed;
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
  if(userId<=0)throw new FeedFailure(401,"UNAUTHENTICATED");
  var o=db.orderByUserPost(userId,postId);if(o==null)return null;
  return toOrder(o);
 }
 public OrderView getOrder(long viewer,long orderId){
  var o=db.order(orderId);if(o==null)throw new FeedFailure(404,"ORDER_NOT_FOUND");
  if(o.userId()!=viewer && o.merchantId()!=viewer)throw new FeedFailure(403,"ORDER_FORBIDDEN");
  return toOrder(o);
 }
 private OrderView toOrder(SaleMapper.Order o){
  return new OrderView(o.id(),o.postId(),o.merchantId(),o.priceCents(),o.state(),o.paidAtMs(),o.refundedAtMs(),o.refundReason(),"PAID".equals(o.state())&&ingress.winner(o.userId(),o.postId()));
 }

 private SaleCacheSyncBatcher cacheSync;
 /** Hardening B5: batched cache sync used by {@link #settleQueued}. */
 public void setCacheSync(SaleCacheSyncBatcher cacheSync){this.cacheSync=cacheSync;}
 /** MQ consumer path (hardening B5): settle, then hand the cache jobs to the batcher. */
 public void settleQueued(String reservationId){settle(reservationId,cacheSync!=null);}
 /** Durable settle; idempotent on reservationId. The cache is synced before it returns (one transaction). */
 public void settle(String reservationId){settle(reservationId,false);}
 private void settle(String reservationId,boolean queued){
  faults.awaitHoldIfArmed(120);
  faults.enterInFlight();
  try(var trace=SaleLatency.begin("settle-"+reservationId,"settle",0,0)){SaleLatency.correlate(null,reservationId);settleBody(reservationId,queued);SaleLatency.status(200);}
  finally{faults.leaveInFlight();}
 }

 /** Thrown inside the settle transaction when the final stock UPDATE matched no row; rolls the order writes back. */
 private static final class StockClaimMissed extends RuntimeException{
  final long at;StockClaimMissed(long at){super(null,null,false,false);this.at=at;}
 }

 private void settleBody(String reservationId,boolean queued){
  for(int attempt=1;;attempt++){
   try{settleOnce(reservationId);break;}
   catch(StockClaimMissed miss){
    // Rolled back: no order, entitlement or receipt survived. Re-judge on committed state in a new transaction.
    if(rejectAfterStockMiss(reservationId,miss.at))break;
    if(attempt>=2)throw new FeedFailure(503,"SETTLE_STOCK_CONTENDED"); // stock freed again meanwhile: MQ redelivery retries
   }
  }
  var req=db.request(reservationId);
  if(req==null)return;
  // Hardening B5: the jobs this settle dirtied, applied in one transaction (direct call) or handed to the batcher (MQ).
  var keys=new ArrayList<SaleCacheSyncBatcher.Key>();keys.add(new SaleCacheSyncBatcher.Key("REQUEST",reservationId));
  if("REJECTED".equals(req.state())&&"ALREADY_OWNED".equals(req.rejectReason()))keys.add(new SaleCacheSyncBatcher.Key("OWNER",req.userId()+":"+req.postId()));
  if(queued&&cacheSync!=null)cacheSync.submit(keys);else ingress.bestEffortBatch(keys);
 }

 /**
  * Campaign row is read without a lock and written once, by the conditional stock UPDATE that is the last statement
  * (docs/commerce-campaign-lock-analysis.md, option (a)). The X lock on the hot row is therefore held only from that
  * UPDATE to commit, and close/takedown/refund/bridge inserts are not serialized behind the order writes.
  * The early campaign checks read committed state; they can only reject, and every request they let through is
  * re-checked by the final UPDATE.
  */
 private void settleOnce(String reservationId){
  tx.executeWithoutResult(status->{
   ingress.guard();
   boolean ingressBlocked=ingress.blocked(reservationId);
   var req=db.lockRequest(reservationId);if(req==null)throw new FeedFailure(503,"REQUEST_NOT_PERSISTED");
   SaleLatency.correlate(req.clientKey(),reservationId);
   if(!"PROCESSING".equals(req.state())){db.insertReceipt(reservationId,req.state(),System.currentTimeMillis());return;}
   if(ingressBlocked)return; // Durable isolation, never a late successful order or an unproven release.
   if(db.receipt(reservationId)!=null)return;
   // Upsert then FOR UPDATE the account/material decision row; refunds and giveaway awards take the same lock,
   // so ownership is stable here.
   db.touchLock(req.userId(),req.postId(),System.currentTimeMillis());
   db.lockDecision(req.userId(),req.postId());
   boolean alreadyOwns=db.alreadyOwnsMaterial(req.userId(),req.postId());
   var campaign=db.campaign(req.campaignId());
   long at=System.currentTimeMillis();
   // Dynamic blacklist remains authoritative in MySQL even if Redis deny cache lags.
   if(db.activeBlacklist(req.merchantId(),req.userId())>0){reject(req,"BLACKLISTED",at);return;}
   if(alreadyOwns){reject(req,"ALREADY_OWNED",at);return;}
   String closed=campaignRejection(campaign,at); // same order as before V26: closed, post offline, window, stock
   if("CAMPAIGN_CLOSED".equals(closed)){reject(req,closed,at);return;}
   if(!"PUBLISHED".equals(Optional.ofNullable(posts.post(req.postId())).map(FeedMapper.Post::status).orElse(null))){reject(req,"POST_OFFLINE",at);return;}
   if(closed!=null){reject(req,closed,at);return;}
   Long version=campaign.frozenVersionId();
   if(version==null){
    var profile=materials.profile(req.postId());
    version=profile==null?null:profile.defaultVersionId();
   }
   if(version==null)throw new IllegalStateException("Campaign settlement requires a material version");
   var order=new SaleMapper.OrderInsert();
   order.reservationId=req.reservationId();order.campaignId=req.campaignId();order.postId=req.postId();
   order.merchantId=req.merchantId();order.userId=req.userId();order.priceCents=req.priceCents();order.paidAt=at;
   try{db.insertOrder(order);}
   catch(DuplicateKeyException e){reject(req,"ALREADY_OWNED",at);return;} // no stock taken yet
   var ent=new SaleMapper.EntitlementInsert();
   ent.account=req.userId();ent.post=req.postId();ent.version=version;ent.license="DOWNLOAD";
   ent.kind="PURCHASE";ent.source=order.id;ent.at=at;
   if(db.insertEntitlement(ent)!=1 || db.finishRequest(reservationId,"SUCCEEDED",null,order.id,at)!=1
      || db.insertReceipt(reservationId,"SUCCEEDED",at)!=1)throw new IllegalStateException("Incomplete purchase settlement");
   // B5: no OWNER job. The REQUEST job's APPLY writes o:<user>:<post>=reservationId on SUCCEEDED (keeping GIVEAWAY), the value
   // the OWNER job would compute: one order per account/material, so the owner only moves ""->order<->GIVEAWAY.
   ingress.dirty("REQUEST",reservationId);
   faults.pauseIfArmed("settle.beforeStockUpdate",60);
   if(db.tryIncrementSold(campaign.id(),at)!=1)throw new StockClaimMissed(at); // rolls back every write above
   faults.failIfArmed("settle.beforeCommit");
  });
 }

 /** First failing sellability condition at {@code at}, or null when the campaign can still sell. */
 private static String campaignRejection(SaleMapper.Campaign c,long at){
  if(c==null || !"OPEN".equals(c.state()))return "CAMPAIGN_CLOSED";
  if(at<c.startMs())return "NOT_STARTED";
  if(at>c.endMs())return "ENDED";
  if(c.soldCount()>=c.stockCap())return "SOLD_OUT";
  return null;
 }

 /** After a missed stock claim: reject with the reason the committed campaign row now gives; false if none (stock freed). */
 private boolean rejectAfterStockMiss(String reservationId,long at){
  return Boolean.TRUE.equals(tx.execute(status->{
   ingress.guard();
   var req=db.lockRequest(reservationId);
   if(req==null || !"PROCESSING".equals(req.state()) || db.receipt(reservationId)!=null)return true; // decided elsewhere
   String reason=campaignRejection(db.campaign(req.campaignId()),at);
   if(reason==null)return false;
   reject(req,reason,at);return true;
  }));
 }

 private void reject(SaleMapper.PurchaseRequest req,String reason,long at){
  db.finishRequest(req.reservationId(),"REJECTED",reason,null,at);
  db.insertReceipt(req.reservationId(),"REJECTED:"+reason,at);
  ingress.dirty("REQUEST",req.reservationId());
  // B5: a rejection does not change ownership. ALREADY_OWNED reaching settle means Redis lacked the owner (admission checks
  // o:), so that OWNER job is kept as a repair.
  if("ALREADY_OWNED".equals(reason))ingress.dirty("OWNER",req.userId()+":"+req.postId());
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
 public long requestGiveawayRefund(long buyerId,long orderId){
  return tx.execute(status->{
   ingress.guard();var snapshot=db.order(orderId);if(snapshot==null)throw new FeedFailure(404,"ORDER_NOT_FOUND");
   if(snapshot.userId()!=buyerId)throw new FeedFailure(403,"ORDER_FORBIDDEN");
   db.touchLock(buyerId,snapshot.postId(),System.currentTimeMillis());db.lockDecision(buyerId,snapshot.postId());
   var order=db.lockOrder(orderId);var success=db.succeededRefund(orderId);
   if(success!=null){if("GIVEAWAY_REFUND".equals(success.reasonKind()))return success.id();throw new FeedFailure(409,"ALREADY_REFUNDED");}
   if(!"PAID".equals(order.state()))throw new FeedFailure(409,"ORDER_NOT_PAID");
   if(ingress.winner(buyerId,order.postId())==false)throw new FeedFailure(409,"GIVEAWAY_ENTITLEMENT_REQUIRED");
   var pending=db.pendingRefund(orderId);
   if(pending!=null){if("GIVEAWAY_REFUND".equals(pending.reasonKind()))return pending.id();throw new FeedFailure(409,"REFUND_PENDING");}
   var row=new SaleMapper.RefundInsert();row.order=orderId;row.kind="GIVEAWAY_REFUND";row.requester=buyerId;row.at=System.currentTimeMillis();db.insertRefund(row);return row.id;
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
   if(forced)kind="BUYER_FORCED";
   if("GIVEAWAY_REFUND".equals(kind)&&!ingress.winner(o.userId(),o.postId()))throw new FeedFailure(409,"GIVEAWAY_ENTITLEMENT_REQUIRED");
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
  * Locks (account, post) for a giveaway award. The sale runtime is required only when the material is linked to a sale:
  * it has had a campaign, or the winner holds an order for it (same rule as offlineInTransaction). With no link there is
  * no sale cache state to keep consistent, so the award proceeds even while the runtime is not RUNNING.
  * Skipping the guard is safe when a campaign is created later, even concurrently: createCampaign seeds OWNER from the
  * committed winners, and any purchase that still gets through is settled under this same decision lock, where the
  * ownership check rejects it as ALREADY_OWNED. Orders only come from campaigns and campaigns are never deleted, so an
  * order for the winner always implies a committed campaign this read sees. The check is a plain read made before
  * guard() so the lock order (runtime row, then decision row) stays the same as in settle.
  * @return whether the award is sale-linked; pass it to {@link #giveawayAwarded}.
  */
 public boolean lockForGiveawayAward(long account,long post){
  boolean saleLinked=db.saleLinked(account,post);
  if(saleLinked)ingress.guard();
  db.touchLock(account,post,System.currentTimeMillis());db.lockDecision(account,post);
  return saleLinked;
 }
 public void giveawayAwarded(long account,long post,long giveaway,boolean saleLinked){
  if(!saleLinked)return; // No campaign and no order: no OWNER cache entry and no refund notice to produce.
  ingress.dirty("OWNER",account+":"+post);
  ingress.afterCommit("OWNER",account+":"+post);
  var order=db.orderByUserPost(account,post);
  if(order!=null&&"PAID".equals(order.state())){
   String dedupe="giveaway-refund-available:"+giveaway+":"+account;
   long synthetic=Math.floorMod(Objects.hash(giveaway,account,"refund-available"),1000000000)+5000000000L;
   notifications.insertWithMeta(account,synthetic,"GIVEAWAY_REFUND_AVAILABLE",order.merchantId(),post,0L,null,null,dedupe,System.currentTimeMillis());
  }
 }

 public List<Long> purchaseEligibleAccounts(long merchantId,Long postId,Long windowStartMs,Long deadlineMs){
  try{return db.purchaseEligible(merchantId,postId,windowStartMs,deadlineMs);}
  catch(RuntimeException e){throw new FeedFailure(409,"PURCHASE_SOURCE_UNAVAILABLE");}
 }
}
