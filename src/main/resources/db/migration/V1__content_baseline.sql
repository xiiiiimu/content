CREATE TABLE author_feed_mode (
    author_id BIGINT PRIMARY KEY,
    mode VARCHAR(4) NOT NULL CHECK (mode IN ('PUSH', 'PULL')),
    mode_epoch BIGINT NOT NULL DEFAULT 0,
    publish_seq BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    author_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    body TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    published_at_ms BIGINT,
    publish_seq BIGINT,
    delivery_mode VARCHAR(4),
    delivery_epoch BIGINT,
    publish_request_id VARCHAR(128) COLLATE utf8mb4_bin,
    UNIQUE KEY uq_post_publish_request (author_id, publish_request_id),
    UNIQUE KEY uq_post_publish_seq (author_id, publish_seq),
    KEY ix_posts_author_feed (author_id, status, published_at_ms DESC, id DESC),
    FOREIGN KEY (author_id) REFERENCES author_feed_mode (author_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE follow_graph_version (
    follower_id BIGINT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE follows (
    follower_id BIGINT NOT NULL,
    followee_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    follow_epoch BIGINT NOT NULL,
    follow_cutoff_seq BIGINT NOT NULL,
    history_since_ms BIGINT NOT NULL,
    history_limit INT NOT NULL,
    PRIMARY KEY (follower_id, followee_id),
    KEY ix_follow_fanout (followee_id, status, follower_id)
) ENGINE=InnoDB;

CREATE TABLE follow_history (
    follower_id BIGINT NOT NULL,
    followee_id BIGINT NOT NULL,
    follow_epoch BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    PRIMARY KEY (follower_id, followee_id, follow_epoch, post_id)
) ENGINE=InnoDB;

CREATE TABLE outbox_events (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    attempt INT NOT NULL DEFAULT 0,
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6),
    next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_outbox_retry (status, next_retry_at, id),
    KEY ix_outbox_lease (status, lease_until, id)
) ENGINE=InnoDB;

CREATE TABLE consumer_inbox (
    consumer_name VARCHAR(64) NOT NULL,
    event_id BIGINT NOT NULL,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_name, event_id)
) ENGINE=InnoDB;

CREATE TABLE feed_delivery_tasks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    delivery_epoch BIGINT NOT NULL,
    scan_upper_follower_id BIGINT,
    last_follower_id BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(16) NOT NULL DEFAULT 'NEW',
    attempt INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6),
    UNIQUE KEY uq_delivery_event (event_id),
    KEY ix_delivery_retry (state, next_retry_at, id),
    KEY ix_delivery_lease (state, lease_until, id)
) ENGINE=InnoDB;

CREATE TABLE feed_mode_transitions (
    author_id BIGINT NOT NULL,
    from_epoch BIGINT NOT NULL,
    to_epoch BIGINT NOT NULL,
    old_mode VARCHAR(4) NOT NULL,
    new_mode VARCHAR(4) NOT NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    coverage_until DATETIME(6),
    state VARCHAR(16) NOT NULL,
    PRIMARY KEY (author_id, to_epoch)
) ENGINE=InnoDB;
