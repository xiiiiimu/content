package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import io.lettuce.core.RedisException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Real Redis Stream -> request/outbox bridge, fenced recovery and durable cache synchronization. */
public final class SaleIngress {
 private final SaleIngressMapper db;private final SaleMapper sales;private final SaleEntryRedis cache;private final TransactionTemplate tx;private final SaleFaultHooks faults;
 private final String consumer=UUID.randomUUID().toString();
 private final SaleBridgeBatch bridge;
 public SaleIngress(SaleIngressMapper db,SaleMapper sales,SaleEntryRedis cache,TransactionTemplate tx,SaleFaultHooks faults,SaleBridgeMapper batches,int batchSize){this.db=db;this.sales=sales;this.cache=cache;this.tx=tx;this.faults=faults;this.bridge=new SaleBridgeBatch(batches,db,sales,cache,tx,faults,batchSize);}
 public SaleEntryRedis cache(){return cache;}
 public String legacyCanonical(String id){return db.legacyCanonical(id);}
 public boolean winner(long user,long post){return db.winner(user,post)>0;}
 public void guard(){var r=db.guard();if(r==null||!"RUNNING".equals(r.state())||bridge.unknownHolds())throw new FeedFailure(503,"SALE_RECOVERING");}
 public boolean blocked(String id){return bridge.blocked(id);}
 public void dirty(String kind,String key){db.dirty(kind,key);}
 public boolean healthy(){
  var r=db.runtime();return r!=null&&"RUNNING".equals(r.state())&&cache.ready(r.generation())&&Objects.equals(r.redisRunId(),cache.runId());
 }
 public void prepare(){try(var trace=SaleLatency.begin(null,"bridge-prepare",0,0)){if(healthy())return;recover();if(!healthy())throw new FeedFailure(503,"SALE_RECOVERING");}}
 public static SaleEntryRedis.Request request(SaleIngressMapper.RestoreRequest r){return new SaleEntryRedis.Request(r.reservationId(),r.campaignId(),r.postId(),r.merchantId(),r.userId(),r.clientKey(),r.priceCents(),r.state(),r.rejectReason(),r.orderId(),r.createdAtMs());}

 /** Returns the number of Stream entries processed, see {@link SaleBridgeBatch#drain}. */
 public int drain(int limit){return drain(consumer,limit,30000);}
 public int drain(String executor,int limit,long minIdle){
  return bridge.drain(executor,limit,minIdle);
 }
 private void persist(SaleEntryRedis.Request r,long generation,String recoveryOwner){
  if(r==null||r.userId()<=0||r.clientKey()==null||r.clientKey().isBlank()||r.clientKey().length()>128||!r.reservationId().matches("[a-f0-9]{32}"))throw new IllegalStateException("Invalid ingress identity");
  faults.failIfArmed("stream.beforePersist");
  tx.executeWithoutResult(status->{
   var control=db.guard();
   if(recoveryOwner==null){
    if(!"RUNNING".equals(control.state())||control.generation()!=generation){if(sales.request(r.reservationId())!=null)return;throw new FeedFailure(503,"STALE_INGRESS_GENERATION");}
   }else if(!"RECOVERING".equals(control.state())||control.generation()!=generation||!recoveryOwner.equals(control.owner())||control.leaseUntilMs()<=db.now())throw new FeedFailure(503,"RECOVERY_FENCED");
   var campaign=sales.campaign(r.campaignId());
   if(campaign==null||campaign.postId()!=r.postId()||campaign.merchantId()!=r.merchantId()||campaign.priceCents()!=r.priceCents())throw new IllegalStateException("Ingress campaign snapshot mismatch");
   var existing=sales.request(r.reservationId());
   if(existing==null){
    try{sales.insertRequest(r.reservationId(),r.campaignId(),r.postId(),r.merchantId(),r.userId(),r.clientKey(),r.priceCents(),r.createdAtMs());}
    catch(DuplicateKeyException e){var replay=sales.byClientInCampaign(r.userId(),r.campaignId(),r.clientKey());if(replay==null||!replay.reservationId().equals(r.reservationId()))throw e;}
   }
   db.enqueue(r.reservationId());faults.failIfArmed("stream.beforeCommit");
  });
 }
 public SaleIngressMapper.Dispatch claim(){return tx.execute(status->{guard();var d=db.next();if(d==null)return null;db.claim(d.reservationId());return db.dispatch(d.reservationId());});}
 /**
  * One claim transaction for up to {@code limit} rows. Lease-expired SENDING rows get the first ceil(limit/2) slots so a
  * steady PENDING stream (available_at_ms=0) cannot starve takeover; due PENDING fills the rest, then more expired rows.
  * Each row gets its own token+1/attempts+1 and the database-time 30s lease. Nothing is sent before this commits.
  */
 public List<SaleIngressMapper.Dispatch> claimBatch(int limit){
  int n=Math.max(0,Math.min(200,limit));if(n==0)return List.of();
  return tx.execute(status->{
   guard();
   int quota=(n+1)/2;
   var expired=db.expired(quota,List.of());
   var picked=new ArrayList<SaleIngressMapper.Dispatch>(expired);
   if(picked.size()<n)picked.addAll(db.due(n-picked.size()));
   if(expired.size()==quota&&picked.size()<n)picked.addAll(db.expired(n-picked.size(),ids(expired)));
   if(picked.isEmpty())return List.<SaleIngressMapper.Dispatch>of();
   var ids=ids(picked);
   if(db.claimAll(ids)!=ids.size())throw new IllegalStateException("Locked dispatch rows changed");
   faults.failIfArmed("dispatch.beforeClaimCommit");
   // Rows are row-locked by this transaction, so the read values plus one are the committed claim values.
   return picked.stream().map(d->new SaleIngressMapper.Dispatch(d.reservationId(),"SENDING",d.attempts()+1,d.token()+1,0)).toList();
  });
 }
 /** Per-item result of one outcome transaction; ids absent from {@code applied} lost their state/token/lease and stay with the protocol. */
 public record Outcome(List<String> applied,List<String> missed){static final Outcome NONE=new Outcome(List.of(),List.of());}
 /** One transaction confirms all broker-ACKed claims; each row is checked by id, SENDING, its own token and a live lease. */
 public Outcome sentBatch(List<SaleIngressMapper.Dispatch> claims){return outcome(claims,"SENT","dispatch.beforeConfirmCommit",true);}
 /** One transaction returns failed sends to PENDING with the original per-row backoff; same per-row checks. */
 public Outcome failedBatch(List<SaleIngressMapper.Dispatch> claims){return outcome(claims,"PENDING","dispatch.beforeRetryCommit",false);}
 private Outcome outcome(List<SaleIngressMapper.Dispatch> claims,String state,String fault,boolean sent){
  if(claims.isEmpty())return Outcome.NONE;
  return tx.execute(status->{
   int changed=sent?db.sentAll(claims):db.retryAll(claims);
   var applied=db.holding(claims,state);
   if(applied.size()<changed)throw new IllegalStateException("Dispatch outcome verification lost rows");
   faults.failIfArmed(fault);
   var held=new HashSet<>(applied);
   return new Outcome(List.copyOf(applied),claims.stream().map(SaleIngressMapper.Dispatch::reservationId).filter(id->!held.contains(id)).toList());
  });
 }
 private static List<String> ids(List<SaleIngressMapper.Dispatch> rows){return rows.stream().map(SaleIngressMapper.Dispatch::reservationId).toList();}
 public void sent(SaleIngressMapper.Dispatch d){tx.executeWithoutResult(s->db.sent(d.reservationId(),d.token()));}
 public void failed(SaleIngressMapper.Dispatch d){tx.executeWithoutResult(s->db.retry(d.reservationId(),d.token()));}

 /** Scanner: all dirty jobs of this pass in one transaction (hardening B5; was one transaction per job). */
 public void syncJobs(int limit){try(var trace=SaleLatency.begin(null,"bridge-cache-sync",0,0)){
  var jobs=db.jobs(Math.max(1,Math.min(limit,200)));if(jobs.isEmpty())return;
  try{syncBatch(jobs.stream().map(j->new SaleCacheSyncBatcher.Key(j.kind(),j.entityKey())).toList());}
  catch(RedisException e){/* Whole batch rolled back; durable jobs retry next pass. */}
 }}
 public boolean sync(String kind,String key){return Boolean.TRUE.equals(tx.execute(status->{
  var runtime=db.guard();if(!"RUNNING".equals(runtime.state()))return false;
  var job=db.job(kind,key);if(job==null||job.appliedRevision()==job.revision())return true;
  return applyJob(runtime,job);
 }));}
 /**
  * Hardening B5: applies the given cache jobs in one transaction. Each job is row-locked (primary-key order), skipped when
  * already applied, rebuilt from MySQL and applied with the same revision checks as {@link #sync}. A generation change stops
  * the batch (jobs applied so far commit; the rest wait for recovery). A Redis failure rolls the whole batch back; Redis
  * writes already made are idempotent re-applies of the same revisions. Returns the number of jobs applied.
  */
 public int syncBatch(List<SaleCacheSyncBatcher.Key> keys){
  if(keys.isEmpty())return 0;
  var ordered=keys.stream().distinct().sorted(Comparator.comparing(SaleCacheSyncBatcher.Key::kind).thenComparing(SaleCacheSyncBatcher.Key::entityKey))
   .map(k->new SaleIngressMapper.CacheKey(k.kind(),k.entityKey())).toList();
  Integer applied=tx.execute(status->{
   var runtime=db.guard();if(!"RUNNING".equals(runtime.state()))return 0;
   int n=0;
   for(var job:db.jobsForUpdate(ordered)){
    if(job.appliedRevision()==job.revision())continue;
    if(!applyJob(runtime,job))break;
    n++;
   }
   return n;
  });
  return applied==null?0:applied;
 }
 /** B5: one-transaction sync after a direct settle; a Redis failure leaves the durable jobs to the scanner. */
 public void bestEffortBatch(List<SaleCacheSyncBatcher.Key> keys){try{syncBatch(keys);}catch(RedisException ignored){/* Durable jobs retry after reconnect. */}}
 private boolean applyJob(SaleIngressMapper.Runtime runtime,SaleIngressMapper.CacheJob job){
  String kind=job.kind(),key=job.entityKey();
  Map<String,String> fields=new LinkedHashMap<>();SaleEntryRedis.Request request=null;Long refund=null;boolean restore=false;long campaignId=0;
  Long scope=null,ownerPost=null;
  switch(kind){
   case "CAMPAIGN"->{var c=sales.campaign(Long.parseLong(key));
    if(c!=null){
     if(archived(c.id()))return skipArchived(job);
     scope=c.id();
     fields.putAll(SaleEntryRedis.campaignFields(c,db.reserved(c.id()),db.published(c.postId())==1));
     // C3: an active campaign brings its post's owners (and pc:<post>) into Redis; RESERVE relies on o: for one order per material.
     fields.putAll(ownerFields(c.postId()));
     fields.put("pc:"+c.postId(),joined(db.activeCampaignsOfPost(c.postId())));
    }}
   case "BLACKLIST"->{var pair=key.split(":");long merchant=Long.parseLong(pair[0]),user=Long.parseLong(pair[1]);fields.put("b:"+key,sales.activeBlacklist(merchant,user)>0?"1":"0");}
   case "OWNER"->{var pair=key.split(":");long user=Long.parseLong(pair[0]),post=Long.parseLong(pair[1]);var order=sales.orderByUserPost(user,post);fields.put("o:"+key,db.winner(user,post)>0?"GIVEAWAY":order==null?"":order.reservationId());ownerPost=post;}
   case "REQUEST"->{var r=db.request(key);if(r!=null){if(archived(r.campaignId()))return skipArchived(job);request=request(r);scope=r.campaignId();}}
   case "REFUND"->{var r=db.refund(Long.parseLong(key));if(r!=null){if(archived(r.campaignId()))return skipArchived(job);refund=r.orderId();restore=r.restoreStock();campaignId=r.campaignId();scope=r.campaignId();}}
   default->throw new IllegalStateException("Unknown cache job");
  }
  faults.failIfArmed("controls.beforeApply");
  if(!cache.apply(runtime.generation(),job,fields,request,refund,restore,campaignId,scope,ownerPost))return false;
  db.applied(kind,key,job.revision());return true;
 }
 private boolean archived(long campaign){return Boolean.TRUE.equals(db.archived(campaign));}
 /** C3: MySQL is the only state of an archived campaign; its jobs are settled there without writing Redis. */
 private boolean skipArchived(SaleIngressMapper.CacheJob job){db.applied(job.kind(),job.entityKey(),job.revision());return true;}
 static String joined(List<Long> ids){return String.join(",",ids.stream().map(String::valueOf).toList());}
 /** o:<user>:<post> for every sale owner (and giveaway winner) of the post, plus r: of each owner's request. */
 Map<String,String> ownerFields(long post){
  Map<String,String> m=new LinkedHashMap<>();
  for(long winner:db.winners(post))m.put("o:"+winner+":"+post,"GIVEAWAY");
  for(var o:db.ordersOfPost(post)){
   var r=db.request(o.reservationId());if(r!=null)m.put("r:"+o.reservationId(),cache.encode(request(r)));
   m.putIfAbsent("o:"+o.userId()+":"+post,o.reservationId());
  }
  return m;
 }
 /** C3: the archived-campaign path of the entry (Lua found no campaign): request by (user, campaign, key), V22 legacy rows included. */
 public SaleMapper.PurchaseRequest archivedRequest(long user,long campaign,String key){
  var r=sales.byClientInCampaign(user,campaign,key);if(r!=null)return r;
  for(var l:db.legacyKeys(campaign))if(l.userId()==user&&l.clientKey().equals(key))return sales.request(l.canonicalReservationId());
  return null;
 }
 public boolean campaignArchived(long campaign){return archived(campaign);}
 public void bestEffort(String kind,String key){try{sync(kind,key);}catch(RedisException ignored){/* Durable job retries after reconnect. */}}
 public void afterCommit(String kind,String key){
  if(!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()){bestEffort(kind,key);return;}
  org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
   @Override public void afterCommit(){
    var fresh=new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
    fresh.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    fresh.executeWithoutResult(status->bestEffort(kind,key));
   }
  });
 }

 /** Called outside a transaction; every page is bounded and the recovery lease is renewed. */
 public synchronized void recover(){
  String owner=UUID.randomUUID().toString();
  Long generation=tx.execute(status->{var r=db.lockRuntime();
   if("RECOVERING".equals(r.state())&&r.leaseUntilMs()>db.now())return null;
   if("RUNNING".equals(r.state())&&cache.ready(r.generation())&&Objects.equals(cache.runId(),r.redisRunId()))return null;
   db.begin(owner);return db.runtime().generation();});
  if(generation==null)return;
  cache.fence(generation);String runId=cache.runId();
  // Persist all available old-generation pending/new messages before deriving inventory.
  bridge.recover("recover-"+owner,generation,owner);
  importLegacy(generation,owner);
  String stage=cache.staging(generation);
  // Hardening C3: only the active set (campaigns not archived) is rebuilt; archived campaigns live in MySQL only.
  Set<Long> posts=new TreeSet<>();
  long after=0;for(;;){renew(generation,owner);var ids=db.activeCampaigns(after);if(ids.isEmpty())break;
   for(long id:ids){var c=sales.campaign(id);cache.put(stage,SaleEntryRedis.campaignFields(c,db.reserved(id)+bridge.held(id),db.published(c.postId())==1));posts.add(c.postId());after=id;}}
  after=0;for(;;){renew(generation,owner);var ids=db.activeCampaigns(after);if(ids.isEmpty())break;
   for(long id:ids){
    String cursor="";for(;;){renew(generation,owner);var rows=db.requestsOfCampaign(id,cursor,500);if(rows.isEmpty())break;
     for(var r:rows){cache.restoreRequest(stage,request(r));cursor=r.reservationId();}}
    // V22 legacy replays: a retry inside the legacy campaign keeps getting the canonical request.
    Map<String,String> legacy=new LinkedHashMap<>();
    for(var l:db.legacyKeys(id))legacy.put(SaleEntryRedis.client(l.userId(),id,l.clientKey()),l.canonicalReservationId());
    cache.put(stage,legacy);
    Map<String,String> refunds=new LinkedHashMap<>();for(long order:db.refundedOrders(id))refunds.put("refund:"+order,"1");cache.put(stage,refunds);
    after=id;
   }}
  // Owners of every post with an active campaign, from any campaign (giveaway ownership wins), and pc:<post>.
  for(long post:posts){renew(generation,owner);var fields=ownerFields(post);fields.put("pc:"+post,joined(db.activeCampaignsOfPost(post)));cache.put(stage,fields);}
  long merchant=0,user=0;for(;;){renew(generation,owner);var rows=db.blocks(merchant,user);if(rows.isEmpty())break;
   Map<String,String> fields=new LinkedHashMap<>();for(var b:rows){fields.put("b:"+b.merchantId()+":"+b.userId(),"1");merchant=b.merchantId();user=b.userId();}cache.put(stage,fields);}
  bridge.restore(stage,generation,owner);
  renew(generation,owner);faults.failIfArmed("recovery.beforeInstall");cache.install(stage,generation);
  bridge.reconciled(generation,owner);
  if(db.finish(generation,owner,runId)!=1)throw new FeedFailure(503,"RECOVERY_FENCED");
  cache.publish(generation,runId);syncJobs(200);
 }
 private void renew(long generation,String owner){if(db.renew(generation,owner)!=1)throw new FeedFailure(503,"RECOVERY_FENCED");}
 private void importLegacy(long generation,String owner){
  long after=0;for(;;){renew(generation,owner);var ids=db.campaigns(after);if(ids.isEmpty())return;
   for(long id:ids){var c=sales.campaign(id);String cursor=Optional.ofNullable(db.legacyCursor(id)).orElse("0-0");
    for(;;){renew(generation,owner);var messages=cache.legacy(id,cursor);if(messages.isEmpty())break;
     for(var m:messages){String rid=m.getBody().get("reservationId");
      if(rid!=null&&sales.request(rid)==null&&"PROCESSING".equals(cache.commands().hget("sale:{"+id+"}:r:"+rid,"state"))){
       persistLegacy(new SaleEntryRedis.Request(rid,id,c.postId(),c.merchantId(),Long.parseLong(m.getBody().get("userId")),m.getBody().get("clientKey"),c.priceCents(),"PROCESSING",null,null,db.now()),m.getId(),generation,owner);
      }
      cursor=m.getId();db.saveLegacyCursor(id,cursor);
     }
    }after=id;
   }
  }
 }
 private void persistLegacy(SaleEntryRedis.Request r,String stream,long generation,String owner){
  if(r.userId()<=0||r.clientKey()==null||r.clientKey().isBlank()||r.clientKey().length()>128||!r.reservationId().matches("[a-f0-9]{32}"))throw new IllegalStateException("Invalid legacy ingress identity");
  tx.executeWithoutResult(status->{
   var control=db.guard();
   if(!"RECOVERING".equals(control.state())||control.generation()!=generation||!owner.equals(control.owner())||control.leaseUntilMs()<=db.now())throw new FeedFailure(503,"RECOVERY_FENCED");
   var canonical=sales.byClient(r.userId(),r.clientKey());
   if(canonical!=null&&!canonical.reservationId().equals(r.reservationId())){
    db.legacyReplay(r.reservationId(),r.campaignId(),stream,canonical.reservationId(),r.userId(),r.clientKey(),cache.encode(r),cache.encode(request(db.request(canonical.reservationId()))));
    if(!canonical.reservationId().equals(db.legacyCanonical(r.reservationId())))throw new IllegalStateException("Legacy replay audit identity changed");
   }else persist(r,generation,owner);
   // Audit/request durability and migration progress commit together.
   db.saveLegacyCursor(r.campaignId(),stream);
  });
 }
 public void recordRefundRestore(long refund,boolean restore){db.refundRestore(refund,restore);dirty("REFUND",Long.toString(refund));}
}
