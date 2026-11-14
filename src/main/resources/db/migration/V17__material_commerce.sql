-- Material limited-sale commerce: campaigns, purchase requests, orders, refunds, blacklist, decision locks.

CREATE TABLE material_sale_campaigns (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  post_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  price_cents BIGINT NOT NULL,
  stock_cap INT NOT NULL,
  sold_count INT NOT NULL DEFAULT 0,
  start_ms BIGINT NOT NULL,
  end_ms BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  link_token VARCHAR(64) NOT NULL,
  open_guard TINYINT NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uq_campaign_link (link_token),
  UNIQUE KEY uq_campaign_open (post_id, open_guard),
  KEY ix_campaign_merchant (merchant_id, state),
  CONSTRAINT chk_campaign_state CHECK (state IN ('OPEN','CLOSED')),
  CONSTRAINT chk_campaign_stock CHECK (stock_cap > 0 AND sold_count >= 0 AND sold_count <= stock_cap),
  CONSTRAINT fk_campaign_post FOREIGN KEY (post_id) REFERENCES posts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE material_purchase_requests (
  reservation_id VARCHAR(64) NOT NULL PRIMARY KEY,
  campaign_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  client_key VARCHAR(128) NOT NULL,
  price_cents BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
  reject_reason VARCHAR(64) NULL,
  order_id BIGINT NULL,
  mq_sent_at_ms BIGINT NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uq_purchase_client (user_id, client_key),
  KEY ix_purchase_campaign_state (campaign_id, state),
  KEY ix_purchase_user_post (user_id, post_id),
  CONSTRAINT chk_purchase_req_state CHECK (state IN ('PROCESSING','SUCCEEDED','REJECTED')),
  CONSTRAINT fk_purchase_campaign FOREIGN KEY (campaign_id) REFERENCES material_sale_campaigns(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE material_orders (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reservation_id VARCHAR(64) NOT NULL,
  campaign_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  price_cents BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PAID',
  paid_at_ms BIGINT NOT NULL,
  refunded_at_ms BIGINT NULL,
  refund_reason VARCHAR(32) NULL,
  UNIQUE KEY uq_order_reservation (reservation_id),
  UNIQUE KEY uq_order_user_post (user_id, post_id),
  KEY ix_order_merchant_paid (merchant_id, paid_at_ms),
  KEY ix_order_post_state (post_id, state, paid_at_ms),
  CONSTRAINT chk_order_state CHECK (state IN ('PAID','REFUNDED')),
  CONSTRAINT chk_order_refund_reason CHECK (
    refund_reason IS NULL OR refund_reason IN ('GIVEAWAY_AUTO','BUYER_REQUESTED','BUYER_FORCED')
  ),
  CONSTRAINT fk_order_campaign FOREIGN KEY (campaign_id) REFERENCES material_sale_campaigns(id),
  CONSTRAINT fk_order_reservation FOREIGN KEY (reservation_id) REFERENCES material_purchase_requests(reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE material_refund_requests (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  reason_kind VARCHAR(32) NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  requester_id BIGINT NOT NULL,
  resolver_id BIGINT NULL,
  success_guard TINYINT NULL,
  created_at_ms BIGINT NOT NULL,
  resolved_at_ms BIGINT NULL,
  UNIQUE KEY uq_refund_success (order_id, success_guard),
  KEY ix_refund_order (order_id, state),
  CONSTRAINT chk_refund_reason CHECK (reason_kind IN ('GIVEAWAY_AUTO','BUYER_REQUESTED','BUYER_FORCED')),
  CONSTRAINT chk_refund_state CHECK (state IN ('PENDING','REJECTED','SUCCEEDED')),
  CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES material_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE merchant_blacklist (
  merchant_id BIGINT NOT NULL,
  target_user_id BIGINT NOT NULL,
  reason VARCHAR(512) NULL,
  operator_id BIGINT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  revoked_at_ms BIGINT NULL,
  PRIMARY KEY (merchant_id, target_user_id),
  KEY ix_blacklist_active (merchant_id, revoked_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE account_material_decision_locks (
  account_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  PRIMARY KEY (account_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE material_sale_consume_receipts (
  reservation_id VARCHAR(64) NOT NULL PRIMARY KEY,
  decision VARCHAR(32) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  CONSTRAINT fk_sale_receipt FOREIGN KEY (reservation_id) REFERENCES material_purchase_requests(reservation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
