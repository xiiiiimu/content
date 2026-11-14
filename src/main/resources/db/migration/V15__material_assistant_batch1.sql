-- Batch-1 material assistant: structured aspect, tasks, feedback subscribe/resolve, notify dedupe.

ALTER TABLE material_profiles
  ADD COLUMN aspect_ratio VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER license_commercial;

ALTER TABLE material_profiles
  ADD CONSTRAINT chk_material_aspect CHECK (aspect_ratio IN ('PORTRAIT','LANDSCAPE','SQUARE','UNKNOWN'));

ALTER TABLE notifications
  ADD COLUMN ref_version_id BIGINT NULL AFTER parent_comment_id,
  ADD COLUMN dedupe_key VARCHAR(191) NULL AFTER ref_version_id,
  ADD UNIQUE KEY uq_notif_dedupe (recipient_user_id, dedupe_key);

CREATE TABLE assistant_tasks (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  trigger_comment_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  actor_user_id BIGINT NOT NULL,
  parent_comment_id BIGINT NULL,
  body VARCHAR(4000) NOT NULL,
  intent VARCHAR(32) NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'NEW',
  attempt INT NOT NULL DEFAULT 0,
  lease_token BIGINT NOT NULL DEFAULT 0,
  lease_until DATETIME(6) NULL,
  next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error VARCHAR(512) NULL,
  result_json JSON NULL,
  reply_comment_id BIGINT NULL,
  created_at_ms BIGINT NOT NULL,
  UNIQUE KEY uq_assistant_trigger (trigger_comment_id),
  KEY ix_assistant_claim (state, next_retry_at, id),
  CONSTRAINT chk_assistant_state CHECK (state IN ('NEW','RUNNING','DONE','FAILED','CLARIFY')),
  CONSTRAINT fk_assistant_comment FOREIGN KEY (trigger_comment_id) REFERENCES post_comments(id),
  CONSTRAINT fk_assistant_post FOREIGN KEY (post_id) REFERENCES posts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feedback_subscriptions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  feedback_comment_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at_ms BIGINT NOT NULL,
  cancelled_at_ms BIGINT NULL,
  UNIQUE KEY uq_feedback_sub (account_id, feedback_comment_id),
  KEY ix_feedback_sub_comment (feedback_comment_id, state),
  CONSTRAINT chk_feedback_sub_state CHECK (state IN ('ACTIVE','CANCELLED')),
  CONSTRAINT fk_feedback_sub_comment FOREIGN KEY (feedback_comment_id) REFERENCES post_comments(id),
  CONSTRAINT fk_feedback_sub_post FOREIGN KEY (post_id) REFERENCES posts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feedback_resolutions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  feedback_comment_id BIGINT NOT NULL,
  post_id BIGINT NOT NULL,
  new_version_id BIGINT NOT NULL,
  offset_ms BIGINT NULL,
  author_id BIGINT NOT NULL,
  created_at_ms BIGINT NOT NULL,
  UNIQUE KEY uq_feedback_resolution (feedback_comment_id, new_version_id),
  KEY ix_feedback_resolution_version (new_version_id),
  CONSTRAINT fk_feedback_res_comment FOREIGN KEY (feedback_comment_id) REFERENCES post_comments(id),
  CONSTRAINT fk_feedback_res_version FOREIGN KEY (new_version_id) REFERENCES material_versions(id),
  CONSTRAINT fk_feedback_res_post FOREIGN KEY (post_id) REFERENCES posts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE feedback_notify_log (
  account_id BIGINT NOT NULL,
  feedback_comment_id BIGINT NOT NULL,
  new_version_id BIGINT NOT NULL,
  notification_id BIGINT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (account_id, feedback_comment_id, new_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
