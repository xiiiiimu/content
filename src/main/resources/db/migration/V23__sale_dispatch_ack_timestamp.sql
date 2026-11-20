-- ACK metadata belongs to the dispatch owner, not the consumer's request row.
-- Preserve legacy timestamps without inventing unknown historical ACK times.
ALTER TABLE material_sale_dispatch ADD COLUMN sent_at_ms BIGINT NULL;
UPDATE material_sale_dispatch d
 JOIN material_purchase_requests r ON r.reservation_id=d.reservation_id
 SET d.sent_at_ms=r.mq_sent_at_ms
 WHERE d.state='SENT' AND r.mq_sent_at_ms>0;
