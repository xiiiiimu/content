CREATE TABLE post_likes (
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (post_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE post_comments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    body TEXT NOT NULL,
    idempotency_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    created_at_ms BIGINT NOT NULL,
    UNIQUE KEY uq_comment_idem (user_id, idempotency_key),
    KEY ix_comments_post (post_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE post_stats (
    post_id BIGINT PRIMARY KEY,
    like_count BIGINT NOT NULL DEFAULT 0,
    comment_count BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE topic_event_ledger (
    event_id VARCHAR(64) PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    post_id BIGINT NOT NULL,
    occurred_at_ms BIGINT NOT NULL,
    delta INT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    KEY ix_topic_time (topic, occurred_at_ms)
) ENGINE=InnoDB;

CREATE TABLE topic_minute_buckets (
    topic VARCHAR(128) NOT NULL,
    bucket_start_ms BIGINT NOT NULL,
    net_events BIGINT NOT NULL DEFAULT 0,
    active_interactions BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (topic, bucket_start_ms)
) ENGINE=InnoDB;

CREATE TABLE media_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    expected_post_version BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'NEW',
    attempt INT NOT NULL DEFAULT 0,
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6),
    next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    output_key VARCHAR(512),
    last_error VARCHAR(512),
    UNIQUE KEY uq_media_post (post_id),
    KEY ix_media_retry (state, next_retry_at, id)
) ENGINE=InnoDB;
