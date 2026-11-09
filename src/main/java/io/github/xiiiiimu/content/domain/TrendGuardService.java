package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.TrendGuardMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** V2.1 §14 + ADR-005: durable risk events + readonly listing. MySQL authoritative. */
public final class TrendGuardService {
 public static final int SCHEMA_VERSION=1;
 public static final String KIND_TOPIC_SPIKE="TOPIC_SPIKE";
 public record Risk(String eventId,int schemaVersion,String topic,String kind,long occurredAtMs,Long postId,String detail) {}
 private final TrendGuardMapper db;private final TransactionTemplate tx;
 public TrendGuardService(TrendGuardMapper db,TransactionTemplate tx){this.db=db;this.tx=tx;}
 public static String spikeEventId(String topic,long windowStartMs){return "tg:1:"+KIND_TOPIC_SPIKE+":"+topic+":"+windowStartMs;}
 /** Idempotent append by business key (kind,topic,window). Duplicate → false; other SQL errors propagate. */
 public boolean record(String eventId,String topic,String kind,long windowStartMs,Long postId,String detail){
  validate(eventId,topic,kind,detail,windowStartMs);
  long now=System.currentTimeMillis();
  String d=detail==null?"":detail;
  return Boolean.TRUE.equals(tx.execute(status->insertNew(eventId,topic,kind,windowStartMs,now,postId,d)));
 }
 /** Same-transaction emit used by TrendingService after ledger/bucket writes. */
 public boolean recordInCurrentTx(String eventId,String topic,String kind,long windowStartMs,Long postId,String detail){
  validate(eventId,topic,kind,detail,windowStartMs);
  long now=System.currentTimeMillis();
  String d=detail==null?"":detail;
  return insertNew(eventId,topic,kind,windowStartMs,now,postId,d);
 }
 /** Lock order: caller already holds interaction/ledger/bucket row locks; then ensure+FOR UPDATE this topic serial row only. */
 public void lockTopicForEmit(String topic){
  if(topic==null||topic.isBlank()||topic.length()>128)throw new FeedFailure(400,"INVALID_REQUEST");
  db.ensureTopicSerial(topic);
  if(db.lockTopicSerial(topic)==null)throw new FeedFailure(503,"TREND_TOPIC_LOCK");
 }
 private boolean insertNew(String eventId,String topic,String kind,long windowStartMs,long now,Long postId,String detail){
  try{return db.insert(eventId,SCHEMA_VERSION,topic,kind,windowStartMs,now,postId,detail,now)==1;}
  catch(DuplicateKeyException e){return false;}
  catch(DataIntegrityViolationException e){
   Throwable c=e.getMostSpecificCause();String m=c==null?e.getMessage():c.getMessage();
   if(m!=null && (m.contains("uq_risk_business") || m.contains("Duplicate") || m.contains("duplicate")))return false;
   throw e;
  }
 }
 public List<Risk> list(long windowMillis,String topic,int limit,Long beforeMs,String beforeEventId){
  if(windowMillis<=0)throw new FeedFailure(400,"INVALID_REQUEST");
  if(topic!=null && (topic.isBlank()||topic.length()>128))throw new FeedFailure(400,"INVALID_REQUEST");
  if((beforeMs==null)!=(beforeEventId==null||beforeEventId.isBlank()))throw new FeedFailure(400,"INVALID_REQUEST");
  int n=limit<=0?50:Math.min(limit,100);
  long since=Math.max(0,System.currentTimeMillis()-windowMillis);
  var rows=topic==null?db.sinceAll(since,beforeMs,beforeEventId,n):db.sinceTopic(topic,since,beforeMs,beforeEventId,n);
  return rows.stream().map(r->new Risk(r.eventId(),r.schemaVersion(),r.topic(),r.kind(),r.occurredAtMs(),r.postId(),r.detail())).toList();
 }
 private static void validate(String eventId,String topic,String kind,String detail,long windowStartMs){
  if(eventId==null||eventId.isBlank()||eventId.length()>256)throw new FeedFailure(400,"INVALID_REQUEST");
  if(topic==null||topic.isBlank()||topic.length()>128)throw new FeedFailure(400,"INVALID_REQUEST");
  if(kind==null||kind.isBlank()||kind.length()>32)throw new FeedFailure(400,"INVALID_REQUEST");
  if(windowStartMs<0)throw new FeedFailure(400,"INVALID_REQUEST");
  if(detail!=null && detail.length()>512)throw new FeedFailure(400,"INVALID_REQUEST");
 }
}
