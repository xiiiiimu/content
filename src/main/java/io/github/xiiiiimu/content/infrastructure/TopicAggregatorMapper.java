package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Hardening B1: SQL of the background topic aggregator (lease, pending claim, bucket folding, progress). */
@Mapper
public interface TopicAggregatorMapper {
 record Pending(String id,String topic,long post,long at,int delta,Long revokedAt) {}
 record State(String owner,long leaseUntilMs,long watermarkMs,long lastBatchAtMs,long foldedTotal,long revokedTotal,long lastMaxLagMs) {}
 /**
  * Takes or renews the lease and holds the state row's X lock until the batch commits: every batch, from any instance,
  * serializes on this row, and a holder whose lease expired and was taken over can no longer commit a batch (fencing).
  */
 @Update("UPDATE topic_aggregator_state SET owner=#{owner},lease_until_ms=#{until} WHERE id=1 AND (owner=#{owner} OR owner IS NULL OR lease_until_ms<#{now})")
 int acquire(@Param("owner")String owner,@Param("until")long until,@Param("now")long now);
 @Update("UPDATE topic_aggregator_state SET owner=NULL,lease_until_ms=0 WHERE id=1 AND owner=#{owner}")
 int release(String owner);
 @Select("SELECT owner,lease_until_ms AS leaseUntilMs,watermark_ms AS watermarkMs,last_batch_at_ms AS lastBatchAtMs,folded_total AS foldedTotal,revoked_total AS revokedTotal,last_max_lag_ms AS lastMaxLagMs FROM topic_aggregator_state WHERE id=1")
 State state();
 /** Serializes a rebuild with aggregation batches (no lease needed: a rebuild is an operator action). */
 @Select("SELECT owner FROM topic_aggregator_state WHERE id=1 FOR UPDATE")
 String lockState();
 /**
  * Oldest pending rows first (ix_ledger_pending). SKIP LOCKED: a row held by an in-flight interaction (an unlike about to
  * withdraw or revoke it) is left for the next batch instead of making the aggregator wait on a live transaction.
  */
 @Select("SELECT event_id AS id,topic,post_id AS post,occurred_at_ms AS at,delta,revoked_at_ms AS revokedAt FROM topic_event_ledger WHERE aggregated_at_ms IS NULL ORDER BY occurred_at_ms LIMIT #{limit} FOR UPDATE SKIP LOCKED")
 List<Pending> claim(int limit);
 @Update("""
  <script>UPDATE topic_event_ledger SET aggregated_at_ms=#{at} WHERE aggregated_at_ms IS NULL AND revoked_at_ms IS NULL AND event_id IN
  <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>
  """)
 int markFolded(@Param("ids")List<String> ids,@Param("at")long at);
 @Delete("""
  <script>DELETE FROM topic_event_ledger WHERE aggregated_at_ms IS NULL AND revoked_at_ms IS NOT NULL AND event_id IN
  <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>
  """)
 int deleteRevoked(@Param("ids")List<String> ids);
 @Insert("INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions) VALUES(#{topic},#{bucket},#{net},#{active}) ON DUPLICATE KEY UPDATE net_events=net_events+#{net},active_interactions=active_interactions+#{active}")
 void add(@Param("topic")String topic,@Param("bucket")long bucket,@Param("net")long net,@Param("active")long active);
 /** Takes revoked likes back from the bucket they were counted in; never below zero; a purged bucket → 0 rows, nothing to take back. */
 @Update("UPDATE topic_minute_buckets SET net_events=GREATEST(net_events-#{net},0),active_interactions=GREATEST(active_interactions-#{active},0) WHERE topic=#{topic} AND bucket_start_ms=#{bucket}")
 int subtract(@Param("topic")String topic,@Param("bucket")long bucket,@Param("net")long net,@Param("active")long active);
 /** Includes rows skipped as locked, so the watermark never passes an event that is still pending. */
 @Select("SELECT MIN(occurred_at_ms) FROM topic_event_ledger WHERE aggregated_at_ms IS NULL")
 Long oldestPending();
 @Update("UPDATE topic_aggregator_state SET watermark_ms=#{watermark},last_batch_at_ms=#{at},folded_total=folded_total+#{folded},revoked_total=revoked_total+#{revoked},last_max_lag_ms=#{lag} WHERE id=1")
 void progress(@Param("watermark")long watermark,@Param("at")long at,@Param("folded")long folded,@Param("revoked")long revoked,@Param("lag")long lag);
 @Update("UPDATE topic_aggregator_state SET watermark_ms=#{watermark} WHERE id=1")
 void watermark(long watermark);
 /** Spike check: net of one topic since a time, a primary-key prefix range (topic, bucket_start_ms). */
 @Select("SELECT COALESCE(SUM(net_events),0) FROM topic_minute_buckets WHERE topic=#{topic} AND bucket_start_ms>=#{since}")
 long topicNetSince(@Param("topic")String topic,@Param("since")long since);
 @Delete("DELETE FROM topic_minute_buckets") void clearBuckets();
 /** Rebuild input: rows whose +delta is in a bucket right now (folded, or revoked but not yet subtracted). */
 @Insert("""
  INSERT INTO topic_minute_buckets(topic,bucket_start_ms,net_events,active_interactions)
  SELECT topic,FLOOR(occurred_at_ms/60000)*60000,SUM(delta),SUM(delta<>0) FROM topic_event_ledger
   WHERE occurred_at_ms>=#{since} AND (aggregated_at_ms IS NOT NULL OR revoked_at_ms IS NOT NULL)
   GROUP BY topic,FLOOR(occurred_at_ms/60000)*60000
  """)
 int refold(long since);
 @Select("SELECT COUNT(*) FROM topic_event_ledger WHERE occurred_at_ms>=#{since} AND (aggregated_at_ms IS NOT NULL OR revoked_at_ms IS NOT NULL)")
 int refoldedEvents(long since);
}
