-- V1.1 material posts: kind, versions, author tags, share grants, timestamp feedback, dislike/report.

ALTER TABLE posts
    ADD COLUMN post_kind VARCHAR(16) NOT NULL DEFAULT 'TEXT' AFTER status,
    ADD CONSTRAINT chk_posts_kind CHECK (post_kind IN ('TEXT','MATERIAL'));

CREATE TABLE material_profiles (
    post_id BIGINT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(2000) NOT NULL DEFAULT '',
    share_mode VARCHAR(32) NOT NULL DEFAULT 'PUBLIC_PREVIEW',
    password_hash VARCHAR(100) NULL,
    share_epoch BIGINT NOT NULL DEFAULT 0,
    license_download TINYINT(1) NOT NULL DEFAULT 0,
    license_commercial TINYINT(1) NOT NULL DEFAULT 0,
    default_version_id BIGINT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    CONSTRAINT chk_material_share CHECK (share_mode IN ('AUTHOR_ONLY','PUBLIC_PREVIEW','PASSWORD_ORIGINAL')),
    CONSTRAINT fk_material_profile_post FOREIGN KEY (post_id) REFERENCES posts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE material_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    revision INT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    raw_object_key VARCHAR(512) NOT NULL,
    preview_object_key VARCHAR(512) NULL,
    cover_object_key VARCHAR(512) NULL,
    duration_ms BIGINT NULL,
    meta_json JSON NULL,
    attempt INT NOT NULL DEFAULT 0,
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_until DATETIME(6) NULL,
    next_retry_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_error VARCHAR(512) NULL,
    created_at_ms BIGINT NOT NULL,
    UNIQUE KEY uq_material_revision (post_id, revision),
    KEY ix_material_process (state, next_retry_at, id),
    CONSTRAINT chk_material_version_state CHECK (state IN ('PENDING','PROCESSING','READY','FAILED')),
    CONSTRAINT fk_material_version_post FOREIGN KEY (post_id) REFERENCES posts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE material_tags (
    post_id BIGINT NOT NULL,
    tag VARCHAR(64) NOT NULL,
    PRIMARY KEY (post_id, tag),
    KEY ix_material_tag (tag, post_id),
    CONSTRAINT fk_material_tag_post FOREIGN KEY (post_id) REFERENCES posts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE post_comments
    ADD COLUMN material_version_id BIGINT NULL AFTER parent_id,
    ADD COLUMN offset_ms BIGINT NULL AFTER material_version_id,
    ADD COLUMN accepted_at_ms BIGINT NULL AFTER offset_ms,
    ADD KEY ix_comments_material_version (post_id, material_version_id, id);

CREATE TABLE post_dislikes (
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (post_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE post_reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    reporter_user_id BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    detail VARCHAR(1000) NOT NULL DEFAULT '',
    context_json JSON NULL,
    created_at_ms BIGINT NOT NULL,
    KEY ix_reports_post (post_id, id),
    KEY ix_reports_reporter (reporter_user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE post_stats
    ADD COLUMN dislike_count BIGINT NOT NULL DEFAULT 0 AFTER like_count,
    ADD COLUMN report_count BIGINT NOT NULL DEFAULT 0 AFTER comment_count;
