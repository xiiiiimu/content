package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Bounded set operations; request uniqueness is never weakened with INSERT IGNORE. */
@Mapper
public interface SaleBridgeMapper {
 record Gate(long generation,long revision){}
 @Select("SELECT r.generation,c.revision FROM material_sale_runtime r JOIN material_sale_quarantine_clock c ON c.id=1 WHERE r.id=1 FOR SHARE") Gate gate();
 @Update("UPDATE material_sale_quarantine_clock SET revision=revision+1 WHERE id=1") int bump();
 @Select("<script>SELECT id,post_id AS postId,merchant_id AS merchantId,price_cents AS priceCents,stock_cap AS stockCap,sold_count AS soldCount,start_ms AS startMs,end_ms AS endMs,state,link_token AS linkToken,open_guard AS openGuard,frozen_version_id AS frozenVersionId FROM material_sale_campaigns WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
 List<SaleMapper.Campaign> campaigns(@Param("ids")List<Long> ids);
 @Select("<script>SELECT reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,client_key AS clientKey,price_cents AS priceCents,state,reject_reason AS rejectReason,order_id AS orderId,created_at_ms AS createdAtMs FROM material_purchase_requests WHERE reservation_id IN <foreach collection='rows' item='r' open='(' separator=',' close=')'>#{r.reservationId}</foreach> OR (user_id,campaign_id,client_key) IN <foreach collection='rows' item='r' open='(' separator=',' close=')'>(#{r.userId},#{r.campaignId},#{r.clientKey})</foreach></script>")
 List<SaleIngressMapper.RestoreRequest> requests(@Param("rows")List<SaleEntryRedis.Request> rows);
 @Insert("<script>INSERT INTO material_purchase_requests(reservation_id,campaign_id,post_id,merchant_id,user_id,client_key,price_cents,state,created_at_ms,updated_at_ms) VALUES <foreach collection='rows' item='r' separator=','>(#{r.reservationId},#{r.campaignId},#{r.postId},#{r.merchantId},#{r.userId},#{r.clientKey},#{r.priceCents},'PROCESSING',#{r.createdAtMs},#{r.createdAtMs})</foreach></script>")
 int insertRequests(@Param("rows")List<SaleEntryRedis.Request> rows);
 @Insert("<script>INSERT INTO material_sale_dispatch(reservation_id) VALUES <foreach collection='ids' item='id' separator=','>(#{id})</foreach> ON DUPLICATE KEY UPDATE reservation_id=material_sale_dispatch.reservation_id</script>")
 int enqueue(@Param("ids")List<String> ids);
 @Insert("<script>INSERT INTO material_sale_reservation_fences(reservation_id) VALUES <foreach collection='ids' item='id' separator=','>(#{id})</foreach> ON DUPLICATE KEY UPDATE reservation_id=material_sale_reservation_fences.reservation_id</script>")
 int ensureFences(@Param("ids")List<String> ids);
 record Fence(String reservationId,String state){}
 @Select("<script>SELECT reservation_id AS reservationId,state FROM material_sale_reservation_fences WHERE reservation_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY reservation_id FOR SHARE</script>")
 List<Fence> fences(@Param("ids")List<String> ids);
 @Select("SELECT state FROM material_sale_reservation_fences WHERE reservation_id=#{id} FOR SHARE") String fence(String id);
 @Select("SELECT state FROM material_sale_reservation_fences WHERE reservation_id=#{id} FOR UPDATE") String lockFence(String id);
 @Update("UPDATE material_sale_reservation_fences SET state='BLOCKED' WHERE reservation_id=#{id}") int block(String id);
 @Select("SELECT COUNT(*) FROM material_orders WHERE reservation_id=#{id}") int orders(String id);
 record Quarantine(String streamId,String payload,String reason,String canonicalReservationId,String trustedJson,Long trustedGeneration,String reservationId,Long campaignId,String disposition){}
 String COLS="stream_id AS streamId,payload,reason,canonical_reservation_id AS canonicalReservationId,trusted_json AS trustedJson,trusted_generation AS trustedGeneration,reservation_id AS reservationId,campaign_id AS campaignId,disposition";
 @Select("SELECT "+COLS+" FROM material_sale_stream_quarantine WHERE stream_id=#{id} FOR UPDATE") Quarantine quarantine(String id);
 @Insert("INSERT INTO material_sale_stream_quarantine(stream_id,payload,reason,canonical_reservation_id,trusted_json,trusted_generation,reservation_id,campaign_id,disposition,created_at_ms,updated_at_ms) VALUES(#{streamId},#{payload},#{reason},#{canonicalReservationId},#{trustedJson},#{trustedGeneration},#{reservationId},#{campaignId},#{disposition},"+SaleIngressMapper.NOW+","+SaleIngressMapper.NOW+") ON DUPLICATE KEY UPDATE stream_id=material_sale_stream_quarantine.stream_id")
 int audit(Quarantine row);
 @Select("SELECT "+COLS+" FROM material_sale_stream_quarantine WHERE disposition IN ('HOLD','RELEASE_PENDING') ORDER BY (disposition='RELEASE_PENDING') DESC,stream_id LIMIT 200") List<Quarantine> unresolved();
 @Select("SELECT COUNT(DISTINCT q.reservation_id) FROM material_sale_stream_quarantine q WHERE q.campaign_id=#{id} AND q.disposition='HOLD' AND q.reservation_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM material_purchase_requests r WHERE r.reservation_id=q.reservation_id) AND NOT EXISTS(SELECT 1 FROM material_orders o WHERE o.reservation_id=q.reservation_id)") int held(long id);
 @Select("SELECT COUNT(*) FROM material_sale_stream_quarantine WHERE disposition='HOLD' AND reservation_id IS NULL") int unknownHolds();
 @Select("SELECT stream_id FROM material_sale_stream_quarantine WHERE disposition='HOLD' AND reservation_id IS NULL ORDER BY stream_id LIMIT 1 FOR SHARE") String unknownHoldCurrent();
 @Select("SELECT DISTINCT campaign_id FROM material_sale_stream_quarantine WHERE disposition IN ('HOLD','RELEASE_PENDING') AND campaign_id IS NOT NULL") List<Long> pausedCampaigns();
 @Update("UPDATE material_sale_stream_quarantine SET disposition=#{state},updated_at_ms="+SaleIngressMapper.NOW+" WHERE stream_id=#{id} AND disposition='RELEASE_PENDING'")
 int disposition(@Param("id")String id,@Param("state")String state);
 @Select("SELECT "+COLS+" FROM material_sale_stream_quarantine WHERE stream_id>#{after} ORDER BY stream_id LIMIT 200") List<Quarantine> page(String after);
 @Update("UPDATE material_sale_stream_quarantine q JOIN material_sale_reservation_fences f ON f.reservation_id=q.reservation_id SET q.disposition='RELEASED',q.updated_at_ms="+SaleIngressMapper.NOW+" WHERE q.disposition='RELEASE_PENDING' AND q.trusted_json IS NOT NULL AND f.state='BLOCKED' AND NOT EXISTS(SELECT 1 FROM material_orders o WHERE o.reservation_id=q.reservation_id) AND NOT EXISTS(SELECT 1 FROM material_purchase_requests r WHERE r.reservation_id=q.reservation_id)")
 int reconcileReleases();
}
