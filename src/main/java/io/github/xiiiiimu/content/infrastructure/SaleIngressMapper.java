package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SaleIngressMapper {
 String NOW="CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)";
 record Runtime(String state,long generation,String owner,long leaseUntilMs,String redisRunId){}
 record Dispatch(String reservationId,String state,int attempts,long token,long leaseUntilMs){}
 record CacheJob(String kind,String entityKey,long revision,long appliedRevision){}
 record CacheKey(String kind,String entityKey){}
 record Ownership(long userId,long postId,String reservationId,boolean giveaway){}
 record Block(long merchantId,long userId){}
 record Refund(long id,long orderId,long campaignId,boolean restoreStock){}
 record RestoreRequest(String reservationId,long campaignId,long postId,long merchantId,long userId,String clientKey,
  long priceCents,String state,String rejectReason,Long orderId,long createdAtMs){}
 @Select("SELECT state,generation,owner,lease_until_ms AS leaseUntilMs,redis_run_id AS redisRunId FROM material_sale_runtime WHERE id=1 FOR SHARE") Runtime guard();
 @Select("SELECT state,generation,owner,lease_until_ms AS leaseUntilMs,redis_run_id AS redisRunId FROM material_sale_runtime WHERE id=1 FOR UPDATE") Runtime lockRuntime();
 @Select("SELECT state,generation,owner,lease_until_ms AS leaseUntilMs,redis_run_id AS redisRunId FROM material_sale_runtime WHERE id=1") Runtime runtime();
 @Select("SELECT "+NOW) long now();
 @Update("UPDATE material_sale_runtime SET state='RECOVERING',generation=generation+1,owner=#{owner},lease_until_ms="+NOW+"+120000 WHERE id=1") int begin(String owner);
 @Update("UPDATE material_sale_runtime SET lease_until_ms="+NOW+"+120000 WHERE id=1 AND state='RECOVERING' AND generation=#{epoch} AND owner=#{owner} AND lease_until_ms>"+NOW)
 int renew(@Param("epoch")long epoch,@Param("owner")String owner);
 @Update("UPDATE material_sale_runtime SET state='RUNNING',owner=NULL,lease_until_ms=0,redis_run_id=#{runId} WHERE id=1 AND generation=#{epoch} AND owner=#{owner} AND state='RECOVERING' AND lease_until_ms>"+NOW)
 int finish(@Param("epoch")long epoch,@Param("owner")String owner,@Param("runId")String runId);

 @Insert("INSERT IGNORE INTO material_sale_dispatch(reservation_id) VALUES(#{id})") int enqueue(String id);
 @Select("SELECT reservation_id AS reservationId,state,attempts,token,lease_until_ms AS leaseUntilMs FROM material_sale_dispatch WHERE (state='PENDING' AND available_at_ms<="+NOW+") OR (state='SENDING' AND lease_until_ms<="+NOW+") ORDER BY available_at_ms,reservation_id LIMIT 1 FOR UPDATE SKIP LOCKED") Dispatch next();
 @Update("UPDATE material_sale_dispatch SET state='SENDING',attempts=attempts+1,token=token+1,lease_until_ms="+NOW+"+30000 WHERE reservation_id=#{id}") int claim(String id);
 @Select("SELECT reservation_id AS reservationId,state,attempts,token,lease_until_ms AS leaseUntilMs FROM material_sale_dispatch WHERE reservation_id=#{id}") Dispatch dispatch(String id);
 @Update("UPDATE material_sale_dispatch SET state='SENT',sent_at_ms="+NOW+",lease_until_ms=0 WHERE reservation_id=#{id} AND state='SENDING' AND token=#{token} AND lease_until_ms>"+NOW)
 int sent(@Param("id")String id,@Param("token")long token);
 @Update("UPDATE material_sale_dispatch SET state='PENDING',lease_until_ms=0,available_at_ms="+NOW+"+LEAST(60000,250*POW(2,LEAST(attempts-1,8))) WHERE reservation_id=#{id} AND state='SENDING' AND token=#{token} AND lease_until_ms>"+NOW)
 int retry(@Param("id")String id,@Param("token")long token);

 // Batch dispatch: split ordered scans (V25 indexes), one claim UPDATE, and outcome checks by id+token.
 String IDS="<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>";
 String PAIRS="reservation_id IN <foreach collection='claims' item='c' open='(' separator=',' close=')'>#{c.reservationId}</foreach>"
  +" AND (reservation_id,token) IN <foreach collection='claims' item='c' open='(' separator=',' close=')'>(#{c.reservationId},#{c.token})</foreach>";
 @Select("<script>SELECT reservation_id AS reservationId,state,attempts,token,lease_until_ms AS leaseUntilMs FROM material_sale_dispatch FORCE INDEX(ix_sale_dispatch_sending_expired)"
  +" WHERE state='SENDING' AND lease_until_ms&lt;="+NOW+"<if test='exclude!=null and !exclude.isEmpty()'> AND reservation_id NOT IN <foreach collection='exclude' item='x' open='(' separator=',' close=')'>#{x}</foreach></if>"
  +" ORDER BY lease_until_ms,reservation_id LIMIT #{n} FOR UPDATE SKIP LOCKED</script>")
 List<Dispatch> expired(@Param("n")int n,@Param("exclude")List<String> exclude);
 @Select("SELECT reservation_id AS reservationId,state,attempts,token,lease_until_ms AS leaseUntilMs FROM material_sale_dispatch FORCE INDEX(ix_sale_dispatch_pending_due)"
  +" WHERE state='PENDING' AND available_at_ms<="+NOW+" ORDER BY available_at_ms,reservation_id LIMIT #{n} FOR UPDATE SKIP LOCKED")
 List<Dispatch> due(@Param("n")int n);
 @Update("<script>UPDATE material_sale_dispatch SET state='SENDING',attempts=attempts+1,token=token+1,lease_until_ms="+NOW+"+30000 WHERE reservation_id IN "+IDS+"</script>")
 int claimAll(@Param("ids")List<String> ids);
 @Update("<script>UPDATE material_sale_dispatch SET state='SENT',sent_at_ms="+NOW+",lease_until_ms=0 WHERE "+PAIRS+" AND state='SENDING' AND lease_until_ms&gt;"+NOW+"</script>")
 int sentAll(@Param("claims")List<Dispatch> claims);
 @Update("<script>UPDATE material_sale_dispatch SET state='PENDING',lease_until_ms=0,available_at_ms="+NOW+"+LEAST(60000,250*POW(2,LEAST(attempts-1,8))) WHERE "+PAIRS+" AND state='SENDING' AND lease_until_ms&gt;"+NOW+"</script>")
 int retryAll(@Param("claims")List<Dispatch> claims);
 /** Locking read in the outcome transaction: which ids now hold the expected state with this claim's token. */
 @Select("<script>SELECT reservation_id FROM material_sale_dispatch WHERE "+PAIRS+" AND state=#{state} FOR UPDATE</script>")
 List<String> holding(@Param("claims")List<Dispatch> claims,@Param("state")String state);

 @Insert("INSERT INTO material_sale_cache_jobs(kind,entity_key) VALUES(#{kind},#{key}) ON DUPLICATE KEY UPDATE revision=revision+1")
 int dirty(@Param("kind")String kind,@Param("key")String key);
 @Select("SELECT kind,entity_key AS entityKey,revision,applied_revision AS appliedRevision FROM material_sale_cache_jobs WHERE revision>applied_revision ORDER BY kind,entity_key LIMIT #{n}") List<CacheJob> jobs(int n);
 @Select("SELECT kind,entity_key AS entityKey,revision,applied_revision AS appliedRevision FROM material_sale_cache_jobs WHERE kind=#{kind} AND entity_key=#{key} FOR UPDATE")
 CacheJob job(@Param("kind")String kind,@Param("key")String key);
 /** Hardening B5: the given jobs, row-locked in primary-key order (concurrent batches cannot deadlock each other). */
 @Select("<script>SELECT kind,entity_key AS entityKey,revision,applied_revision AS appliedRevision FROM material_sale_cache_jobs WHERE (kind,entity_key) IN <foreach collection='keys' item='k' open='(' separator=',' close=')'>(#{k.kind},#{k.entityKey})</foreach> ORDER BY kind,entity_key FOR UPDATE</script>")
 List<CacheJob> jobsForUpdate(@Param("keys")List<CacheKey> keys);
 @Update("UPDATE material_sale_cache_jobs SET applied_revision=#{revision} WHERE kind=#{kind} AND entity_key=#{key} AND revision=#{revision}")
 int applied(@Param("kind")String kind,@Param("key")String key,@Param("revision")long revision);

 @Select("SELECT id FROM material_sale_campaigns WHERE id>#{after} ORDER BY id LIMIT 200") List<Long> campaigns(long after);
 @Select("SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id=#{id} AND state='PROCESSING'") int reserved(long id);
 @Select("SELECT COUNT(*) FROM posts WHERE id=#{post} AND status='PUBLISHED' AND published_at_ms IS NOT NULL") int published(long post);
 @Select("SELECT merchant_id AS merchantId,target_user_id AS userId FROM merchant_blacklist WHERE revoked_at_ms IS NULL AND (merchant_id>#{merchant} OR (merchant_id=#{merchant} AND target_user_id>#{user})) ORDER BY merchant_id,target_user_id LIMIT 500")
 List<Block> blocks(@Param("merchant")long merchant,@Param("user")long user);
 @Select("SELECT reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,client_key AS clientKey,price_cents AS priceCents,state,reject_reason AS rejectReason,order_id AS orderId,created_at_ms AS createdAtMs FROM material_purchase_requests WHERE reservation_id>#{after} ORDER BY reservation_id LIMIT 500")
 List<RestoreRequest> requests(String after);
 @Select("SELECT reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,client_key AS clientKey,price_cents AS priceCents,state,reject_reason AS rejectReason,order_id AS orderId,created_at_ms AS createdAtMs FROM material_purchase_requests WHERE reservation_id=#{id}") RestoreRequest request(String id);
 @Select("SELECT DISTINCT account_id FROM material_entitlements WHERE post_id=#{post} AND source_kind='GIVEAWAY' AND state='ACTIVE'") List<Long> winners(long post);
 @Select("SELECT COUNT(*) FROM material_entitlements WHERE post_id=#{post} AND account_id=#{user} AND source_kind='GIVEAWAY' AND state='ACTIVE'")
 int winner(@Param("user")long user,@Param("post")long post);
 @Select("SELECT id FROM material_orders WHERE state='REFUNDED' AND id>#{after} ORDER BY id LIMIT 500") List<Long> refunds(long after);
 @Update("UPDATE material_refund_requests SET restore_stock=#{restore} WHERE id=#{id}") int refundRestore(@Param("id")long id,@Param("restore")boolean restore);
 @Select("SELECT f.id,f.order_id AS orderId,o.campaign_id AS campaignId,f.restore_stock AS restoreStock FROM material_refund_requests f JOIN material_orders o ON o.id=f.order_id WHERE f.id=#{id} AND f.state='SUCCEEDED'") Refund refund(long id);
 @Insert("INSERT INTO material_sale_legacy_streams(campaign_id,cursor_id) VALUES(#{campaign},#{cursor}) ON DUPLICATE KEY UPDATE cursor_id=VALUES(cursor_id)")
 int saveLegacyCursor(@Param("campaign")long campaign,@Param("cursor")String cursor);
 @Select("SELECT cursor_id FROM material_sale_legacy_streams WHERE campaign_id=#{id}") String legacyCursor(long id);
 @Insert("INSERT INTO material_sale_legacy_replays(legacy_reservation_id,legacy_campaign_id,stream_id,canonical_reservation_id,user_id,client_key,request_json,canonical_json,recorded_at_ms) VALUES(#{legacy},#{campaign},#{stream},#{canonical},#{user},#{client},#{request},#{response},"+NOW+") ON DUPLICATE KEY UPDATE legacy_reservation_id=VALUES(legacy_reservation_id)")
 int legacyReplay(@Param("legacy")String legacy,@Param("campaign")long campaign,@Param("stream")String stream,
  @Param("canonical")String canonical,@Param("user")long user,@Param("client")String client,@Param("request")String request,@Param("response")String response);
 @Select("SELECT canonical_reservation_id FROM material_sale_legacy_replays WHERE legacy_reservation_id=#{id}") String legacyCanonical(String id);
}
