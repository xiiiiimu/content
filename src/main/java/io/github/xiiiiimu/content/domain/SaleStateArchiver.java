package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/**
 * Hardening C3: ended campaigns' sale state leaves Redis; MySQL is the only state afterwards.
 *
 * <p><b>Archive.</b> A campaign qualifies once it stopped (end time, or close time) more than the grace period (24 h) ago and
 * it has no PROCESSING request, no Stream entry that is not yet persisted (pending or undelivered; an entry is ACKed only
 * after MySQL has it or it is quarantined), and no unresolved quarantine (none at all with an unknown campaign). It is then
 * marked archived in MySQL ({@code cache_archived_at_ms}): from that commit on its cache jobs are settled in MySQL without
 * writing Redis. Its Redis fields are deleted in fenced batches, the first one setting the tombstone {@code x:<campaign>}
 * so a job that read the campaign as active just before cannot write back afterwards. Finally
 * {@code cache_released_at_ms}; a run interrupted in between resumes with the release.
 *
 * <p><b>Fields.</b> {@code c:<id>:*}, {@code v:CAMPAIGN:<id>}; per request {@code k:<user>:<id>:<sha>},
 * {@code v:REQUEST:<rid>}, {@code released:<rid>} and {@code r:<rid>} (owners' {@code r:} stay while the post has an active
 * campaign); V22 legacy {@code k:}; {@code refund:<order>} and {@code v:REFUND:<id>}. When the post has no active campaign
 * left, the post sweep removes {@code pc:<post>} and the owners' {@code o:}, {@code r:} and {@code v:OWNER:} in one script
 * that keeps them if a new campaign registered itself in {@code pc:<post>} meanwhile.
 *
 * <p><b>Migration.</b> {@link #migrate} rewrites the pre-C3 global {@code k:<user>:<sha>} fields to campaign-scoped ones in
 * batches without pausing the entry (RESERVE consults the old field until {@code fmt=3}), after every Stream entry older
 * than the migration start is persisted. A recovery completes the migration by itself (it writes only the new format).
 */
public final class SaleStateArchiver {
 public static final long DEFAULT_GRACE_MS=RetentionBounds.DAY_MS;
 static final int STREAM_LIMIT=1000;
 static final List<String> CAMPAIGN_SUFFIXES=List.of("known","entry","post","merchant","price","version","start","end","stock");
 public record Result(long archived,long released,long fields){}
 private final SaleIngressMapper db;private final SaleMapper sales;private final SaleEntryRedis redis;private final TransactionTemplate tx;
 private final long graceMs;private final int batch;private final int maxBatches;
 public SaleStateArchiver(SaleIngressMapper db,SaleMapper sales,SaleEntryRedis redis,TransactionTemplate tx,long graceMs,int batch,int maxBatches){
  if(graceMs<0||batch<1||maxBatches<1)throw new IllegalArgumentException("content.retention.sale-state.* must be positive");
  this.db=db;this.sales=sales;this.redis=redis;this.tx=tx;this.graceMs=graceMs;this.batch=batch;this.maxBatches=maxBatches;
 }

 public Result archive(){
  if(!"3".equals(redis.control("fmt")))return new Result(0,0,0);   // the clientKey migration comes first
  var runtime=db.runtime();if(runtime==null||!"RUNNING".equals(runtime.state()))return new Result(0,0,0);
  long generation=runtime.generation(),before=db.now()-graceMs;
  boolean unknownQuarantine=db.unresolvedUnknownQuarantine()>0;
  Optional<Set<Long>> unpersisted=null;
  long archived=0,released=0,fields=0,after=0;
  for(int b=0;b<maxBatches;b++){
   var candidates=db.archiveCandidates(before,after,batch);
   if(candidates.isEmpty())break;
   for(var c:candidates){
    after=c.id();
    if(!c.archived()){
     if(unknownQuarantine)continue;
     if(unpersisted==null)unpersisted=redis.unpersistedCampaigns(STREAM_LIMIT);
     if(unpersisted.isEmpty()||unpersisted.get().contains(c.id()))continue;
     if(db.reserved(c.id())>0||db.unresolvedQuarantine(c.id())>0)continue;
     Integer marked=tx.execute(s->db.markArchived(c.id()));
     if(marked==null||marked!=1)continue;
     archived++;
    }
    long n=release(generation,c.id(),c.postId());
    if(n<0)return new Result(archived,released,fields);   // a recovery started: it rebuilds without archived campaigns
    fields+=n;
    if(db.markReleased(c.id())==1)released++;
   }
   if(candidates.size()<batch)break;
  }
  return new Result(archived,released,fields);
 }

 /** Deletes the campaign's Redis fields; -1 when the generation moved. */
 private long release(long generation,long campaign,long post){
  long n=0;
  List<String> f=new ArrayList<>();
  for(String s:CAMPAIGN_SUFFIXES)f.add("c:"+campaign+":"+s);
  f.add("v:CAMPAIGN:"+campaign);
  long k=redis.archiveFields(generation,campaign,f);if(k<0)return -1;n+=k;f.clear();   // first call also sets x:<campaign>
  String cursor="";
  for(;;){
   var rows=db.requestsOfCampaign(campaign,cursor,batch);if(rows.isEmpty())break;
   for(var r:rows){
    f.add(SaleEntryRedis.client(r.userId(),campaign,r.clientKey()));f.add("v:REQUEST:"+r.reservationId());f.add("released:"+r.reservationId());
    if(r.orderId()==null)f.add("r:"+r.reservationId());
    cursor=r.reservationId();
   }
   k=redis.archiveFields(generation,campaign,f);if(k<0)return -1;n+=k;f.clear();
  }
  for(var l:db.legacyKeys(campaign))f.add(SaleEntryRedis.client(l.userId(),campaign,l.clientKey()));
  for(long order:db.refundedOrders(campaign))f.add("refund:"+order);
  for(long refund:db.refundRequests(campaign))f.add("v:REFUND:"+refund);
  if(!f.isEmpty()){k=redis.archiveFields(generation,campaign,f);if(k<0)return -1;n+=k;f.clear();}
  if(db.activeCampaignsOfPost(post).isEmpty()){
   List<String> owners=new ArrayList<>();
   for(long w:db.winners(post)){owners.add("o:"+w+":"+post);owners.add("v:OWNER:"+w+":"+post);}
   for(var o:db.ordersOfPost(post)){owners.add("o:"+o.userId()+":"+post);owners.add("r:"+o.reservationId());owners.add("v:OWNER:"+o.userId()+":"+post);}
   k=redis.sweepPost(generation,post,db.archivedCampaignsOfPost(post),owners);if(k<0)return -1;n+=Math.max(0,k-1);
  }
  return n;
 }

 /** Old global {@code k:<user>:<sha>} fields to campaign-scoped ones; true when the migration is complete. */
 public boolean migrate(){
  if("3".equals(redis.control("fmt")))return true;
  var runtime=db.runtime();if(runtime==null||!"RUNNING".equals(runtime.state()))return false;
  long generation=runtime.generation();
  String t0=redis.control("fmt_t0");
  if(t0==null){t0=Long.toString(db.now());redis.setControl("fmt_t0",t0);}
  // Requests made by the previous release only have the old field: wait until every Stream entry before T0 is in MySQL.
  var oldest=redis.oldestUnpersistedMs(STREAM_LIMIT);
  if(oldest.isEmpty()||oldest.get()<=Long.parseLong(t0))return false;
  String cursor=Optional.ofNullable(redis.control("fmt_cursor")).orElse("");
  Map<Long,Boolean> archivedCampaigns=new HashMap<>();
  for(int b=0;b<maxBatches;b++){
   var rows=db.requestPage(cursor,batch);
   if(rows.isEmpty()){return finish(generation);}
   List<String[]> triples=new ArrayList<>(rows.size());
   for(var r:rows){
    boolean archived=archivedCampaigns.computeIfAbsent(r.campaignId(),id->Boolean.TRUE.equals(db.archived(id)));
    triples.add(new String[]{SaleEntryRedis.client(r.userId(),r.clientKey()),archived?"":SaleEntryRedis.client(r.userId(),r.campaignId(),r.clientKey()),r.reservationId()});
    cursor=r.reservationId();
   }
   if(redis.migrateKeys(generation,triples)<0)return false;
   redis.setControl("fmt_cursor",cursor);
  }
  return false;   // continues on the next run
 }
 private boolean finish(long generation){
  Map<String,String> fields=new LinkedHashMap<>();
  long after=0;
  for(;;){
   var ids=db.activeCampaigns(after);if(ids.isEmpty())break;
   for(long id:ids){
    var c=sales.campaign(id);
    if(c!=null)fields.put("pc:"+c.postId(),String.join(",",db.activeCampaignsOfPost(c.postId()).stream().map(String::valueOf).toList()));
    for(var l:db.legacyKeys(id))fields.put(SaleEntryRedis.client(l.userId(),id,l.clientKey()),l.canonicalReservationId());
    after=id;
   }
  }
  if(redis.putFenced(generation,fields)<0)return false;
  return redis.markFormat(generation);
 }
}
