ALTER TABLE outbox_events
    ADD COLUMN last_attempt_at DATETIME(6),
    ADD COLUMN last_failure_at DATETIME(6),
    ADD COLUMN last_error VARCHAR(1024),
    ADD COLUMN sent_at DATETIME(6),
    ADD KEY ix_outbox_relay_ready (event_type,status,next_retry_at,id),
    ADD KEY ix_outbox_relay_expired (event_type,status,lease_until,id);

CREATE TABLE outbox_replay_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    operator VARCHAR(256) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    old_token BIGINT NOT NULL,
    new_token BIGINT NOT NULL,
    previous_attempt INT NOT NULL,
    replayed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_outbox_replay_token (event_id,new_token),
    FOREIGN KEY (event_id) REFERENCES outbox_events(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
