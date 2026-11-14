-- Batch-2: favorites, giveaways, frozen candidates, winners/entitlements.

CREATE TABLE post_favorites (
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (post_id, user_id),
  KEY ix_favorites_user (user_id, created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE post_stats
  ADD COLUMN favorite_count BIGINT NOT NULL DEFAULT 0 AFTER report_count;

CREATE TABLE giveaways (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_comment_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING_CLARIFY',
  rule_tree_json JSON NOT NULL,
  rule_version INT NOT NULL DEFAULT 1,
  tool_versions_json JSON NOT NULL,
  timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
  window_start_ms BIGINT NULL,
  deadline_ms BIGINT NULL,
  winner_count INT NOT NULL DEFAULT 1,
  reward_version_id BIGINT NULL,
  reward_license VARCHAR(32) NOT NULL DEFAULT 'DOWNLOAD',
  summary_text VARCHAR(2000) NULL,
  announce_comment_id BIGINT NULL,
  frozen_at_ms BIGINT NULL,
  drawn_at_ms BIGINT NULL,
  last_error VARCHAR(512) NULL,
  created_at_ms BIGINT NOT NULL,
  updated_at_ms BIGINT NOT NULL,
  UNIQUE KEY uq_giveaway_source (source_comment_id),
  KEY ix_giveaway_deadline (state, deadline_ms, id),
  CONSTRAINT chk_giveaway_state CHECK (state IN ('PENDING_CLARIFY','OPEN','SEALING','SEALED','DRAWN','COMPLETED','CANCELLED')),
  CONSTRAINT fk_giveaway_source FOREIGN KEY (source_comment_id) REFERENCES post_comments(id),
  CONSTRAINT fk_giveaway_post FOREIGN KEY (post_id) REFERENCES posts(id),
  CONSTRAINT fk_giveaway_reward FOREIGN KEY (reward_version_id) REFERENCES material_versions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE giveaway_candidates (
  giveaway_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  evidence_json JSON NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (giveaway_id, account_id),
  CONSTRAINT fk_giveaway_candidate FOREIGN KEY (giveaway_id) REFERENCES giveaways(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE giveaway_winners (
  giveaway_id BIGINT NOT NULL,
  account_id BIGINT NOT NULL,
  draw_rank INT NOT NULL,
  entitlement_id BIGINT NULL,
  notified_at_ms BIGINT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (giveaway_id, account_id),
  UNIQUE KEY uq_giveaway_rank (giveaway_id, draw_rank),
  CONSTRAINT fk_giveaway_winner FOREIGN KEY (giveaway_id) REFERENCES giveaways(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE material_entitlements (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  version_id BIGINT NOT NULL,
  license VARCHAR(32) NOT NULL,
  source_kind VARCHAR(32) NOT NULL,
  source_id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at_ms BIGINT NOT NULL,
  revoked_at_ms BIGINT NULL,
  UNIQUE KEY uq_entitlement_source (source_kind, source_id, account_id),
  KEY ix_entitlement_account (account_id, post_id, version_id),
  CONSTRAINT chk_entitlement_state CHECK (state IN ('ACTIVE','REVOKED','FAILED')),
  CONSTRAINT fk_entitlement_post FOREIGN KEY (post_id) REFERENCES posts(id),
  CONSTRAINT fk_entitlement_version FOREIGN KEY (version_id) REFERENCES material_versions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
