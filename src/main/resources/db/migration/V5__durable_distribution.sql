ALTER TABLE consumer_inbox
 ADD COLUMN event_type VARCHAR(32),
 ADD COLUMN payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
 ADD COLUMN decision VARCHAR(32);
ALTER TABLE feed_delivery_tasks
 ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'PUBLISH',
 ADD COLUMN post_id BIGINT,
 ADD COLUMN follower_id BIGINT,
 ADD COLUMN follow_epoch BIGINT,
 ADD COLUMN payload JSON,
 ADD COLUMN last_post_id BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN lease_generation BIGINT,
 ADD COLUMN last_error VARCHAR(1024),
 ADD COLUMN last_attempt_at DATETIME(6);
CREATE TABLE feed_event_conflicts (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 consumer_name VARCHAR(64) NOT NULL,
 event_id BIGINT NOT NULL,
 received_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reason VARCHAR(512) NOT NULL,
 payload MEDIUMTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uq_feed_conflict(consumer_name,event_id,received_hash)
) ENGINE=InnoDB;
CREATE TABLE delivery_replay_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 event_id BIGINT NOT NULL,
 old_token BIGINT NOT NULL,
 new_token BIGINT NOT NULL,
 previous_attempt INT NOT NULL,
 operator VARCHAR(255) NOT NULL,
 reason VARCHAR(512) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
