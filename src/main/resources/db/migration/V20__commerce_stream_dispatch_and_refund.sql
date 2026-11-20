-- New ingress is Redis-first; request + durable MQ dispatch are committed by the Stream bridge.
CREATE TABLE material_sale_runtime (
 id TINYINT PRIMARY KEY,
 state VARCHAR(16) NOT NULL,
 generation BIGINT NOT NULL DEFAULT 0,
 owner VARCHAR(64) NULL,
 lease_until_ms BIGINT NOT NULL DEFAULT 0,
 redis_run_id VARCHAR(64) NULL,
 CHECK (state IN ('RUNNING','RECOVERING'))
) ENGINE=InnoDB;
INSERT INTO material_sale_runtime(id,state) VALUES(1,'RECOVERING');

CREATE TABLE material_sale_dispatch (
 reservation_id VARCHAR(64) PRIMARY KEY,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 token BIGINT NOT NULL DEFAULT 0,
 lease_until_ms BIGINT NOT NULL DEFAULT 0,
 available_at_ms BIGINT NOT NULL DEFAULT 0,
 KEY ix_sale_dispatch_due(state,available_at_ms,lease_until_ms),
 CONSTRAINT fk_sale_dispatch_request FOREIGN KEY(reservation_id) REFERENCES material_purchase_requests(reservation_id) ON DELETE CASCADE,
 CHECK(state IN ('PENDING','SENDING','SENT'))
) ENGINE=InnoDB;
INSERT INTO material_sale_dispatch(reservation_id,state)
 SELECT reservation_id,IF(mq_sent_at_ms IS NULL OR mq_sent_at_ms=0,'PENDING','SENT')
 FROM material_purchase_requests WHERE state='PROCESSING';

CREATE TABLE material_sale_cache_jobs (
 kind VARCHAR(24) NOT NULL,
 entity_key VARCHAR(160) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 applied_revision BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(kind,entity_key),
 KEY ix_sale_cache_pending(applied_revision,revision)
) ENGINE=InnoDB;

CREATE TABLE material_sale_legacy_streams (
 campaign_id BIGINT PRIMARY KEY,
 cursor_id VARCHAR(64) NOT NULL DEFAULT '0-0'
) ENGINE=InnoDB;

ALTER TABLE material_orders DROP CHECK chk_order_refund_reason;
ALTER TABLE material_orders ADD CONSTRAINT chk_order_refund_reason CHECK
 (refund_reason IS NULL OR refund_reason IN ('GIVEAWAY_AUTO','GIVEAWAY_REFUND','BUYER_REQUESTED','BUYER_FORCED'));
ALTER TABLE material_refund_requests DROP CHECK chk_refund_reason;
ALTER TABLE material_refund_requests ADD CONSTRAINT chk_refund_reason CHECK
 (reason_kind IN ('GIVEAWAY_AUTO','GIVEAWAY_REFUND','BUYER_REQUESTED','BUYER_FORCED'));
ALTER TABLE material_refund_requests ADD COLUMN restore_stock TINYINT NOT NULL DEFAULT 0;
