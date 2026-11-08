ALTER TABLE feed_control
 ADD COLUMN recovery_token BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN recovery_lease_until DATETIME(6),
 ADD COLUMN recovery_since_ms BIGINT,
 ADD COLUMN recovery_snapshot_ms BIGINT;
ALTER TABLE feed_mode_transitions
 ADD COLUMN source_window_since_ms BIGINT,
 ADD COLUMN coverage_strategy VARCHAR(32),
 ADD COLUMN verified_at DATETIME(6);
CREATE INDEX ix_follow_readers ON follows(status,follower_id,followee_id);
CREATE TABLE feed_recovery_audit (
 generation BIGINT NOT NULL,
 recovery_token BIGINT NOT NULL,
 operator VARCHAR(255) NOT NULL,
 reason VARCHAR(512) NOT NULL,
 state VARCHAR(16) NOT NULL,
 readers BIGINT NOT NULL DEFAULT 0,
 members BIGINT NOT NULL DEFAULT 0,
 duration_ms BIGINT,
 error VARCHAR(512),
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 completed_at DATETIME(6),
 PRIMARY KEY(generation,recovery_token)
) ENGINE=InnoDB;
