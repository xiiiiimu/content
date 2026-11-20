package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import io.lettuce.core.StreamMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.stream.Collectors;

/** One set-based transaction per normal batch. Isolation/release never trusts the conflicting body. */
public final class SaleBridgeBatch {
 private final SaleBridgeMapper db;private final SaleIngressMapper journal;private final SaleMapper sales;
 private final SaleEntryRedis cache;private final TransactionTemplate tx;private final SaleFaultHooks faults;private final int batchSize;
 public SaleBridgeBatch(SaleBridgeMapper db,SaleIngressMapper journal,SaleMapper sales,SaleEntryRedis cache,TransactionTemplate tx,SaleFaultHooks faults,int batchSize){
  if(batchSize<1||batchSize>200)throw new IllegalArgumentException("bridge-batch-size must be 1..200");
  this.db=db;this.journal=journal;this.sales=sales;this.cache=cache;this.tx=tx;this.faults=faults;this.batchSize=batchSize;
 }
 record Item(StreamMessage<String,String> message,SaleEntryRedis.Request request,long generation,SaleEntryRedis.Trusted trusted){}
 record Bad(StreamMessage<String,String> message,String reason,String canonical,SaleEntryRedis.Trusted trusted){}
 record Result(List<Item> good,List<Bad> bad,List<String> inserted){}
 private static boolean valid(SaleEntryRedis.Request r){return r!=null&&r.reservationId()!=null&&r.reservationId().matches("[a-f0-9]{32}")&&r.userId()>0&&r.campaignId()>0&&r.postId()>0&&r.merchantId()>0&&r.priceCents()>=0&&r.createdAtMs()>=0&&r.clientKey()!=null&&!r.clientKey().isBlank()&&r.clientKey().length()<=128&&"PROCESSING".equals(r.state())&&r.orderId()==null&&r.rejectReason()==null;}
 private static boolean same(SaleEntryRedis.Request a,SaleEntryRedis.Request b){return a.reservationId().equals(b.reservationId())&&a.campaignId()==b.campaignId()&&a.postId()==b.postId()&&a.merchantId()==b.merchantId()&&a.userId()==b.userId()&&a.priceCents()==b.priceCents()&&a.createdAtMs()==b.createdAtMs()&&a.clientKey().equals(b.clientKey());}
 private static String client(SaleEntryRedis.Request r){return SaleEntryRedis.client(r.userId(),r.clientKey());}
 public boolean blocked(String id){return "BLOCKED".equals(db.fence(id));}
 public boolean unknownHolds(){return db.unknownHoldCurrent()!=null;} // Locking/current read must not open an early ownership snapshot.
 public int held(long campaign){return db.held(campaign);}
 public void drain(String executor,int limit,long idle){
  try(var trace=SaleLatency.begin(null,"bridge-scan",0,0)){
  var runtime=journal.runtime();if(!"RUNNING".equals(runtime.state()))return;syncIsolations();
  process(cache.read(executor,Math.min(batchSize,Math.max(1,limit)),idle),null,null);
  }
 }
 public void recover(String executor,long generation,String owner){
  for(;;){
   if(journal.renew(generation,owner)!=1)throw new FeedFailure(503,"RECOVERY_FENCED");
   var messages=cache.read(executor,batchSize,0);if(messages.isEmpty()){if(cache.pending()>0)continue;return;}
   process(messages,generation,owner);
  }
 }
 private void process(List<StreamMessage<String,String>> messages,Long recoveryGeneration,String owner){
  if(messages.isEmpty())return;
  faults.failIfArmed("stream.afterRead");
  var trusted=cache.trusted(messages.stream().map(StreamMessage::getId).toList());
  List<Item> parsed=new ArrayList<>();List<Bad> malformed=new ArrayList<>();
  for(var m:messages){
   var binding=trusted.get(m.getId());
   try{
    var r=cache.decode(m.getBody().get("request"));long generation=Long.parseLong(m.getBody().get("generation"));
    if(!valid(r)||generation<0)throw new IllegalArgumentException();
    if(trusted.containsKey(m.getId())&&(binding==null||binding.generation()!=generation||!same(r,binding.request())))malformed.add(new Bad(m,"TRUSTED_BINDING_MISMATCH",null,binding));
    else parsed.add(new Item(m,r,generation,binding));
   }catch(RuntimeException e){malformed.add(new Bad(m,"INVALID_PAYLOAD",null,binding));}
  }
  // Claimed and new messages can interleave. Canonical order inside a batch is Stream order.
  parsed.sort((a,b)->compareIds(a.message().getId(),b.message().getId()));
  try(var trace=SaleLatency.begin(null,"persist-batch",0,0)){
   faults.failIfArmed("stream.beforePersist");Result result=null;
   for(int attempt=0;attempt<2;attempt++){
    try(var phase=SaleLatency.phase("bridge.transaction")){
     result=parsed.isEmpty()?new Result(List.of(),new ArrayList<>(),List.of()):tx.execute(status->persist(parsed,recoveryGeneration,owner));break;
    }catch(DuplicateKeyException e){if(attempt==1)throw e;} // Fresh transaction/read view; never reinterpret a conflict as success.
   }
   SaleLatency.bridgeBatch(messages.size(),result.good().stream().map(i->i.request().reservationId()).toList(),result.inserted());
   faults.failIfArmed("stream.afterCommit");
   try(var phase=SaleLatency.phase("stream.ack")){cache.acknowledge(result.good().stream().map(i->i.message().getId()).toList());}
   malformed.addAll(result.bad());
   for(var bad:malformed)isolate(bad,recoveryGeneration,owner);
  }
 }
 private Result persist(List<Item> parsed,Long recoveryGeneration,String owner){
  var control=journal.guard();
  if(owner==null){if(control==null||!"RUNNING".equals(control.state()))throw new FeedFailure(503,"SALE_RECOVERING");}
  else if(control==null||!"RECOVERING".equals(control.state())||control.generation()!=recoveryGeneration||!owner.equals(control.owner())||control.leaseUntilMs()<=journal.now())throw new FeedFailure(503,"RECOVERY_FENCED");
  var rows=parsed.stream().map(Item::request).toList();
  var campaigns=db.campaigns(rows.stream().map(SaleEntryRedis.Request::campaignId).distinct().toList()).stream().collect(Collectors.toMap(SaleMapper.Campaign::id,c->c));
  var existing=db.requests(rows);Map<String,SaleEntryRedis.Request> byId=new HashMap<>(),byClient=new HashMap<>();
  for(var old:existing){var r=SaleIngress.request(old);byId.put(r.reservationId(),r);byClient.put(client(r),r);}
  // A generation fence is a retry/recovery condition, not a permanent poison
  // decision. No writes or acknowledgements by the stale normal executor.
  var candidates=parsed.stream().filter(i->owner!=null||i.generation()==control.generation()||byId.containsKey(i.request().reservationId())).toList();
  if(candidates.isEmpty())throw new FeedFailure(503,"STALE_INGRESS_GENERATION");
  var ids=candidates.stream().map(i->i.request().reservationId()).distinct().sorted().toList();
  db.ensureFences(ids);var fences=db.fences(ids).stream().collect(Collectors.toMap(SaleBridgeMapper.Fence::reservationId,SaleBridgeMapper.Fence::state));
  List<Item> good=new ArrayList<>();List<Bad> bad=new ArrayList<>();Map<String,SaleEntryRedis.Request> insert=new LinkedHashMap<>();Set<String> enqueue=new LinkedHashSet<>();
  for(var item:candidates){
   var r=item.request();var prior=byId.get(r.reservationId());var canonical=byClient.get(client(r));var c=campaigns.get(r.campaignId());String reason=null;
   if("BLOCKED".equals(fences.get(r.reservationId()))&&!(prior!=null&&same(prior,r)&&"SUCCEEDED".equals(prior.state())))reason="RESERVATION_BLOCKED";
   else if(c==null||c.postId()!=r.postId()||c.merchantId()!=r.merchantId()||c.priceCents()!=r.priceCents())reason="CAMPAIGN_SNAPSHOT_MISMATCH";
   else if(prior!=null&&!same(prior,r))reason="RESERVATION_IDENTITY_CONFLICT";
   else if(canonical!=null&&!canonical.reservationId().equals(r.reservationId()))reason="CLIENT_KEY_CONFLICT";
   if(reason!=null){bad.add(new Bad(item.message(),reason,prior!=null?prior.reservationId():canonical==null?null:canonical.reservationId(),item.trusted()));continue;}
   good.add(item);
   if(prior==null){insert.put(r.reservationId(),r);byId.put(r.reservationId(),r);byClient.put(client(r),r);}
   if(owner!=null||control.generation()==item.generation())enqueue.add(r.reservationId());
  }
  if(!insert.isEmpty())db.insertRequests(List.copyOf(insert.values()));
  if(!enqueue.isEmpty())db.enqueue(List.copyOf(enqueue));
  faults.failIfArmed("stream.beforeCommit");
  return new Result(good,bad,List.copyOf(insert.keySet()));
 }
 private void isolate(Bad bad,Long recoveryGeneration,String owner){
  String body=cache.body(bad.message().getBody());
  try(var trace=SaleLatency.begin(null,"quarantine",0,0)){
   var q=tx.execute(status->{
    var binding=bad.trusted();
    // An unscoped isolation waits for all existing guarded business transactions.
    boolean known=binding!=null&&valid(binding.request());
    var runtime=known?journal.guard():journal.lockRuntime();
    if(owner==null){if(runtime==null||!"RUNNING".equals(runtime.state()))throw new FeedFailure(503,"SALE_RECOVERING");}
    else if(runtime==null||!"RECOVERING".equals(runtime.state())||runtime.generation()!=recoveryGeneration||!owner.equals(runtime.owner())||runtime.leaseUntilMs()<=journal.now())throw new FeedFailure(503,"RECOVERY_FENCED");
    var prior=db.quarantine(bad.message().getId());
    if(prior!=null){if(!prior.payload().equals(body))throw new IllegalStateException("Quarantine message identity changed");return prior;}
    String disposition="HOLD";String rid=null;Long campaign=null,gen=null;String raw=null;
    if(known){
     var r=binding.request();rid=r.reservationId();campaign=r.campaignId();gen=binding.generation();raw=binding.raw();
     db.ensureFences(List.of(rid));db.lockFence(rid);var own=sales.lockRequest(rid);int orders=db.orders(rid);
     db.block(rid);
     if(orders>0)disposition="NO_RELEASE";
     else if(own==null&&(owner!=null||binding.generation()==runtime.generation())){
      var canonical=bad.canonical()==null?null:journal.request(bad.canonical());
      // Two durable PROCESSING identities sharing one campaign budget are not
      // sufficient evidence for automatic stock restoration.
      if(canonical==null||canonical.campaignId()!=r.campaignId()||!"PROCESSING".equals(canonical.state()))disposition="RELEASE_PENDING";
     }
    }
    String reference=bad.canonical();
    if(reference==null){try{var hint=cache.decode(bad.message().getBody().get("request"));if(hint.reservationId()!=null&&hint.reservationId().matches("[a-f0-9]{32}")){var found=journal.request(hint.reservationId());if(found!=null)reference=found.reservationId();}}catch(RuntimeException ignored){/* Only an audit hint; never an authorization or release identity. */}}
    var row=new SaleBridgeMapper.Quarantine(bad.message().getId(),body,bad.reason(),reference,raw,gen,rid,campaign,disposition);
    db.bump();db.audit(row);faults.failIfArmed("quarantine.beforeCommit");return db.quarantine(row.streamId());
   });
   faults.failIfArmed("quarantine.afterCommit");
   if(owner==null){refreshGates();completeRelease(q);}
   refreshGates();faults.failIfArmed("quarantine.beforeAck");cache.acknowledge(bad.message().getId());faults.failIfArmed("quarantine.afterAck");
  }
 }
 private void completeRelease(SaleBridgeMapper.Quarantine q){
  if(!"RELEASE_PENDING".equals(q.disposition()))return;
  var runtime=journal.runtime();if(!"RUNNING".equals(runtime.state())||runtime.generation()!=q.trustedGeneration())return;
  var canonical=q.canonicalReservationId()==null?null:journal.request(q.canonicalReservationId());
  boolean released=cache.releaseQuarantine(q,canonical==null?null:SaleIngress.request(canonical));
  if(released)faults.failIfArmed("quarantine.afterRelease");
  tx.executeWithoutResult(status->{var current=journal.guard();if(!"RUNNING".equals(current.state())||current.generation()!=q.trustedGeneration())throw new FeedFailure(503,"STALE_INGRESS_GENERATION");db.bump();db.disposition(q.streamId(),released?"RELEASED":"HOLD");});
 }
 record Gates(SaleBridgeMapper.Gate gate,boolean unknown,List<Long> campaigns){}
 public void refreshGates(){var gates=tx.execute(status->{var gate=db.gate();return new Gates(gate,db.unknownHolds()>0,db.pausedCampaigns());});cache.quarantineGates(gates.gate().generation(),gates.gate().revision(),gates.unknown(),gates.campaigns());}
 public void syncIsolations(){var pending=db.unresolved();if(pending.isEmpty())return;refreshGates();for(var q:pending)completeRelease(q);refreshGates();}
 public void restore(String stage,long generation,String owner){
  String after="";
  for(;;){if(journal.renew(generation,owner)!=1)throw new FeedFailure(503,"RECOVERY_FENCED");var page=db.page(after);if(page.isEmpty())break;for(var q:page){
   if(q.trustedJson()!=null){var binding=cache.trusted(q.trustedJson());if(binding!=null){var r=binding.request();boolean released="RELEASED".equals(q.disposition())||"RELEASE_PENDING".equals(q.disposition());
    if(journal.request(r.reservationId())==null){var shadow=new SaleEntryRedis.Request(r.reservationId(),r.campaignId(),r.postId(),r.merchantId(),r.userId(),r.clientKey(),r.priceCents(),released?"REJECTED":"PROCESSING",released?"INGRESS_QUARANTINED":null,null,r.createdAtMs());
     cache.put(stage,Map.of("r:"+r.reservationId(),cache.encode(shadow)));if(released)cache.put(stage,Map.of("released:"+r.reservationId(),"1"));
    }
   }}after=q.streamId();
  }}
 }
 public void reconciled(long generation,String owner){tx.executeWithoutResult(status->{var runtime=journal.guard();if(runtime.generation()!=generation||!owner.equals(runtime.owner())||!"RECOVERING".equals(runtime.state())||runtime.leaseUntilMs()<=journal.now())throw new FeedFailure(503,"RECOVERY_FENCED");db.bump();db.reconcileReleases();});refreshGates();}
 private static int compareIds(String a,String b){String[] x=a.split("-"),y=b.split("-");int n=Long.compareUnsigned(Long.parseUnsignedLong(x[0]),Long.parseUnsignedLong(y[0]));return n==0?Long.compareUnsigned(Long.parseUnsignedLong(x[1]),Long.parseUnsignedLong(y[1])):n;}
}
