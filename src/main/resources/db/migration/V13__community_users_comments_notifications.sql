-- V1.1 community: users, comment replies, notifications (compatible with existing posts/follows/outbox).

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at_ms BIGINT NOT NULL,
    UNIQUE KEY uq_users_username (username),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE post_comments
    ADD COLUMN parent_id BIGINT NULL AFTER user_id,
    ADD KEY ix_comments_parent (post_id, parent_id, id);

CREATE TABLE notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recipient_user_id BIGINT NOT NULL,
    source_event_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    comment_id BIGINT NOT NULL,
    parent_comment_id BIGINT NULL,
    created_at_ms BIGINT NOT NULL,
    read_at_ms BIGINT NULL,
    UNIQUE KEY uq_notif_event_recipient (source_event_id, recipient_user_id),
    KEY ix_notif_inbox (recipient_user_id, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
