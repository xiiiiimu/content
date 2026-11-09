package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.TrendGuardMapper;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;

/** V2.1 §14 + ADR-005: durable risk events + readonly listing. MySQL authoritative. */
public final class TrendGuardService {
 public static final int SCHEMA_VERSION=1;
 public record Risk(String eventId,int schemaVersion,String topic,String kind,long occurredAtMs,Long postId,String detail) {}
 private final TrendGuardMapper db;private final TransactionTemplate tx;
 public TrendGuardService(TrendGuardMapper db,TransactionTemplate tx){this.db=db;this.tx=tx;}
 /** Idempotent append. Prefer production emit via {@link TrendingService}; direct use is for recovery/tests only. */
 public boolean record(String eventId,String topic,String kind,Long postId,String detail){
  validate(eventId,topic,kind,detail);
  long now=System.currentTimeMillis();
  String d=detail==null?"":detail;
  return Boolean.TRUE.equals(tx.execute(status->db.insert(eventId,SCHEMA_VERSION,topic,kind,now,postId,d,now)==1));
 }
 /** Same-transaction emit used by TrendingService after ledger/bucket writes. */
 public boolean recordInCurrentTx(String eventId,String topic,String kind,Long postId,String detail){
  validate(eventId,topic,kind,detail);
  long now=System.currentTimeMillis();
  String d=detail==null?"":detail;
  return db.insert(eventId,SCHEMA_VERSION,topic,kind,now,postId,d,now)==1;
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
 private static void validate(String eventId,String topic,String kind,String detail){
  if(eventId==null||eventId.isBlank()||eventId.length()>64)throw new FeedFailure(400,"INVALID_REQUEST");
  if(topic==null||topic.isBlank()||topic.length()>128)throw new FeedFailure(400,"INVALID_REQUEST");
  if(kind==null||kind.isBlank()||kind.length()>32)throw new FeedFailure(400,"INVALID_REQUEST");
  if(detail!=null && detail.length()>512)throw new FeedFailure(400,"INVALID_REQUEST");
 }
}
