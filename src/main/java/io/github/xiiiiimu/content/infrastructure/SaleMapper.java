package io.github.xiiiiimu.content.infrastructure;

import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface SaleMapper {
 record Campaign(long id,long postId,long merchantId,long priceCents,int stockCap,int soldCount,
                 long startMs,long endMs,String state,String linkToken,Integer openGuard) {}
 record PurchaseRequest(String reservationId,long campaignId,long postId,long merchantId,long userId,
                        String clientKey,long priceCents,String state,String rejectReason,Long orderId) {}
 record Order(long id,String reservationId,long campaignId,long postId,long merchantId,long userId,
              long priceCents,String state,long paidAtMs,Long refundedAtMs,String refundReason) {}
 record RefundRequest(long id,long orderId,String reasonKind,String state,long requesterId,Long resolverId) {}
 record Entitlement(long id,long accountId,long postId,long versionId,String license,String sourceKind,long sourceId,String state) {}

 @Insert("""
  INSERT INTO material_sale_campaigns(post_id,merchant_id,price_cents,stock_cap,sold_count,start_ms,end_ms,state,link_token,open_guard,created_at_ms,updated_at_ms)
  VALUES(#{post},#{merchant},#{price},#{cap},0,#{start},#{end},'OPEN',#{token},1,#{at},#{at})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insertCampaign(CampaignInsert row);
 class CampaignInsert { public long id; public long post; public long merchant; public long price; public int cap; public long start; public long end; public String token; public long at; }

 @Select("""
  SELECT id,post_id AS postId,merchant_id AS merchantId,price_cents AS priceCents,stock_cap AS stockCap,sold_count AS soldCount,
         start_ms AS startMs,end_ms AS endMs,state,link_token AS linkToken,open_guard AS openGuard
  FROM material_sale_campaigns WHERE id=#{id}
  """)
 Campaign campaign(long id);

 @Select("""
  SELECT id,post_id AS postId,merchant_id AS merchantId,price_cents AS priceCents,stock_cap AS stockCap,sold_count AS soldCount,
         start_ms AS startMs,end_ms AS endMs,state,link_token AS linkToken,open_guard AS openGuard
  FROM material_sale_campaigns WHERE id=#{id} FOR UPDATE
  """)
 Campaign lockCampaign(long id);

 @Select("""
  SELECT id,post_id AS postId,merchant_id AS merchantId,price_cents AS priceCents,stock_cap AS stockCap,sold_count AS soldCount,
         start_ms AS startMs,end_ms AS endMs,state,link_token AS linkToken,open_guard AS openGuard
  FROM material_sale_campaigns WHERE post_id=#{post} AND state='OPEN' LIMIT 1
  """)
 Campaign openByPost(long post);

 @Select("""
  SELECT id,post_id AS postId,merchant_id AS merchantId,price_cents AS priceCents,stock_cap AS stockCap,sold_count AS soldCount,
         start_ms AS startMs,end_ms AS endMs,state,link_token AS linkToken,open_guard AS openGuard
  FROM material_sale_campaigns WHERE link_token=#{token} LIMIT 1
  """)
 Campaign byToken(String token);

 @Update("UPDATE material_sale_campaigns SET state='CLOSED',open_guard=NULL,updated_at_ms=#{at} WHERE id=#{id} AND state='OPEN'")
 int closeCampaign(@Param("id")long id,@Param("at")long at);

 @Update("UPDATE material_sale_campaigns SET sold_count=sold_count+1,updated_at_ms=#{at} WHERE id=#{id} AND state='OPEN' AND sold_count<stock_cap")
 int tryIncrementSold(@Param("id")long id,@Param("at")long at);

 @Update("UPDATE material_sale_campaigns SET sold_count=sold_count-1,updated_at_ms=#{at} WHERE id=#{id} AND sold_count>0")
 int tryDecrementSold(@Param("id")long id,@Param("at")long at);

 @Insert("""
  INSERT INTO material_purchase_requests(reservation_id,campaign_id,post_id,merchant_id,user_id,client_key,price_cents,state,created_at_ms,updated_at_ms)
  VALUES(#{reservationId},#{campaignId},#{postId},#{merchantId},#{userId},#{clientKey},#{priceCents},'PROCESSING',#{at},#{at})
  """)
 int insertRequest(@Param("reservationId")String reservationId,@Param("campaignId")long campaignId,@Param("postId")long postId,
                   @Param("merchantId")long merchantId,@Param("userId")long userId,@Param("clientKey")String clientKey,
                   @Param("priceCents")long priceCents,@Param("at")long at);

 @Select("""
  SELECT reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,
         client_key AS clientKey,price_cents AS priceCents,state,reject_reason AS rejectReason,order_id AS orderId
  FROM material_purchase_requests WHERE reservation_id=#{id}
  """)
 PurchaseRequest request(String id);

 @Select("""
  SELECT reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,
         client_key AS clientKey,price_cents AS priceCents,state,reject_reason AS rejectReason,order_id AS orderId
  FROM material_purchase_requests WHERE reservation_id=#{id} FOR UPDATE
  """)
 PurchaseRequest lockRequest(String id);

 @Select("""
  SELECT reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,
         client_key AS clientKey,price_cents AS priceCents,state,reject_reason AS rejectReason,order_id AS orderId
  FROM material_purchase_requests WHERE user_id=#{user} AND client_key=#{key} LIMIT 1
  """)
 PurchaseRequest byClient(@Param("user")long user,@Param("key")String key);

 @Select("""
  SELECT reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,
         client_key AS clientKey,price_cents AS priceCents,state,reject_reason AS rejectReason,order_id AS orderId
  FROM material_purchase_requests WHERE state='PROCESSING' AND (mq_sent_at_ms IS NULL OR mq_sent_at_ms=0) ORDER BY created_at_ms LIMIT #{limit}
  """)
 List<PurchaseRequest> pendingMq(int limit);

 @Update("UPDATE material_purchase_requests SET mq_sent_at_ms=#{at},updated_at_ms=#{at} WHERE reservation_id=#{id} AND state='PROCESSING'")
 int markMqSent(@Param("id")String id,@Param("at")long at);

 @Update("""
  UPDATE material_purchase_requests SET state=#{state},reject_reason=#{reason},order_id=#{order},updated_at_ms=#{at}
  WHERE reservation_id=#{id} AND state='PROCESSING'
  """)
 int finishRequest(@Param("id")String id,@Param("state")String state,@Param("reason")String reason,@Param("order")Long order,@Param("at")long at);

 @Insert("""
  INSERT INTO material_orders(reservation_id,campaign_id,post_id,merchant_id,user_id,price_cents,state,paid_at_ms)
  VALUES(#{reservationId},#{campaignId},#{postId},#{merchantId},#{userId},#{priceCents},'PAID',#{paidAt})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insertOrder(OrderInsert row);
 class OrderInsert { public long id; public String reservationId; public long campaignId; public long postId; public long merchantId; public long userId; public long priceCents; public long paidAt; }

 @Select("""
  SELECT id,reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,
         price_cents AS priceCents,state,paid_at_ms AS paidAtMs,refunded_at_ms AS refundedAtMs,refund_reason AS refundReason
  FROM material_orders WHERE id=#{id}
  """)
 Order order(long id);

 @Select("""
  SELECT id,reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,
         price_cents AS priceCents,state,paid_at_ms AS paidAtMs,refunded_at_ms AS refundedAtMs,refund_reason AS refundReason
  FROM material_orders WHERE id=#{id} FOR UPDATE
  """)
 Order lockOrder(long id);

 @Select("""
  SELECT id,reservation_id AS reservationId,campaign_id AS campaignId,post_id AS postId,merchant_id AS merchantId,user_id AS userId,
         price_cents AS priceCents,state,paid_at_ms AS paidAtMs,refunded_at_ms AS refundedAtMs,refund_reason AS refundReason
  FROM material_orders WHERE user_id=#{user} AND post_id=#{post} LIMIT 1
  """)
 Order orderByUserPost(@Param("user")long user,@Param("post")long post);

 @Update("""
  UPDATE material_orders SET state='REFUNDED',refunded_at_ms=#{at},refund_reason=#{reason}
  WHERE id=#{id} AND state='PAID'
  """)
 int markRefunded(@Param("id")long id,@Param("reason")String reason,@Param("at")long at);

 @Insert("""
  INSERT INTO material_refund_requests(order_id,reason_kind,state,requester_id,created_at_ms)
  VALUES(#{order},#{kind},'PENDING',#{requester},#{at})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insertRefund(RefundInsert row);
 class RefundInsert { public long id; public long order; public String kind; public long requester; public long at; }

 @Select("""
  SELECT id,order_id AS orderId,reason_kind AS reasonKind,state,requester_id AS requesterId,resolver_id AS resolverId
  FROM material_refund_requests WHERE id=#{id} FOR UPDATE
  """)
 RefundRequest lockRefund(long id);

 @Select("""
  SELECT id,order_id AS orderId,reason_kind AS reasonKind,state,requester_id AS requesterId,resolver_id AS resolverId
  FROM material_refund_requests WHERE order_id=#{order} AND state='SUCCEEDED' LIMIT 1
  """)
 RefundRequest succeededRefund(long order);

 @Select("""
  SELECT id,order_id AS orderId,reason_kind AS reasonKind,state,requester_id AS requesterId,resolver_id AS resolverId
  FROM material_refund_requests WHERE order_id=#{order} AND state='PENDING' ORDER BY id DESC LIMIT 1
  """)
 RefundRequest pendingRefund(long order);

 @Update("""
  UPDATE material_refund_requests SET state=#{state},resolver_id=#{resolver},success_guard=#{guard},resolved_at_ms=#{at}
  WHERE id=#{id} AND state='PENDING'
  """)
 int finishRefund(@Param("id")long id,@Param("state")String state,@Param("resolver")long resolver,@Param("guard")Integer guard,@Param("at")long at);

 @Insert("""
  INSERT INTO merchant_blacklist(merchant_id,target_user_id,reason,operator_id,created_at_ms,revoked_at_ms)
  VALUES(#{merchant},#{target},#{reason},#{operator},#{at},NULL)
  ON DUPLICATE KEY UPDATE reason=VALUES(reason),operator_id=VALUES(operator_id),created_at_ms=VALUES(created_at_ms),revoked_at_ms=NULL
  """)
 int upsertBlacklist(@Param("merchant")long merchant,@Param("target")long target,@Param("reason")String reason,@Param("operator")long operator,@Param("at")long at);

 @Update("UPDATE merchant_blacklist SET revoked_at_ms=#{at} WHERE merchant_id=#{merchant} AND target_user_id=#{target} AND revoked_at_ms IS NULL")
 int revokeBlacklist(@Param("merchant")long merchant,@Param("target")long target,@Param("at")long at);

 @Select("SELECT COUNT(*) FROM merchant_blacklist WHERE merchant_id=#{merchant} AND target_user_id=#{target} AND revoked_at_ms IS NULL")
 int activeBlacklist(@Param("merchant")long merchant,@Param("target")long target);

 @Insert("""
  INSERT INTO account_material_decision_locks(account_id,post_id,updated_at_ms) VALUES(#{account},#{post},#{at})
  ON DUPLICATE KEY UPDATE updated_at_ms=VALUES(updated_at_ms)
  """)
 int touchLock(@Param("account")long account,@Param("post")long post,@Param("at")long at);

 @Select("SELECT account_id FROM account_material_decision_locks WHERE account_id=#{account} AND post_id=#{post} FOR UPDATE")
 Long lockDecision(@Param("account")long account,@Param("post")long post);

 @Insert("INSERT IGNORE INTO material_sale_consume_receipts(reservation_id,decision,created_at_ms) VALUES(#{id},#{decision},#{at})")
 int insertReceipt(@Param("id")String id,@Param("decision")String decision,@Param("at")long at);

 @Select("SELECT decision FROM material_sale_consume_receipts WHERE reservation_id=#{id}")
 String receipt(String id);

 @Insert("""
  INSERT INTO material_entitlements(account_id,post_id,version_id,license,source_kind,source_id,state,created_at_ms)
  VALUES(#{account},#{post},#{version},#{license},#{kind},#{source},'ACTIVE',#{at})
  """)
 @Options(useGeneratedKeys=true,keyProperty="id")
 int insertEntitlement(EntitlementInsert row);
 class EntitlementInsert { public long id; public long account; public long post; public long version; public String license; public String kind; public long source; public long at; }

 @Update("""
  UPDATE material_entitlements SET state='REVOKED',revoked_at_ms=#{at}
  WHERE source_kind='PURCHASE' AND source_id=#{order} AND account_id=#{account} AND state='ACTIVE'
  """)
 int revokePurchaseEntitlement(@Param("order")long order,@Param("account")long account,@Param("at")long at);

 @Select("""
  SELECT id,account_id AS accountId,post_id AS postId,version_id AS versionId,license,source_kind AS sourceKind,source_id AS sourceId,state
  FROM material_entitlements WHERE account_id=#{account} AND post_id=#{post} AND state='ACTIVE'
  """)
 List<Entitlement> activeEntitlements(@Param("account")long account,@Param("post")long post);

 @Select("""
  SELECT DISTINCT user_id FROM material_orders
  WHERE merchant_id=#{merchant}
    AND (#{post} IS NULL OR post_id=#{post})
    AND (
      state='PAID'
      OR (state='REFUNDED' AND refund_reason='GIVEAWAY_AUTO')
    )
    AND (#{start} IS NULL OR paid_at_ms>=#{start})
    AND (#{end} IS NULL OR paid_at_ms<=#{end})
  """)
 List<Long> purchaseEligible(@Param("merchant")long merchant,@Param("post")Long post,@Param("start")Long start,@Param("end")Long end);
}
