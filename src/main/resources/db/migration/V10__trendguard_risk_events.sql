CREATE TABLE trend_risk_events (
    event_id VARCHAR(64) PRIMARY KEY,
    schema_version INT NOT NULL DEFAULT 1,
    topic VARCHAR(128) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    occurred_at_ms BIGINT NOT NULL,
    post_id BIGINT NULL,
    detail VARCHAR(512) NOT NULL DEFAULT '',
    created_at_ms BIGINT NOT NULL,
    KEY ix_risk_time (occurred_at_ms, event_id),
    KEY ix_risk_topic_time (topic, occurred_at_ms, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
