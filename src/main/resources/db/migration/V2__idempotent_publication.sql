-- Binary equality includes trailing spaces, unlike V1's PAD SPACE collation.
ALTER TABLE posts MODIFY publish_request_id VARBINARY(512);

CREATE TABLE publish_requests (
    author_id BIGINT NOT NULL,
    operation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key VARBINARY(512) NOT NULL,
    post_id BIGINT NOT NULL,
    expected_version BIGINT NOT NULL CHECK (expected_version >= 0),
    response_snapshot JSON NOT NULL,
    PRIMARY KEY (author_id, operation, request_key),
    FOREIGN KEY (author_id) REFERENCES author_feed_mode(author_id),
    FOREIGN KEY (post_id) REFERENCES posts(id)
) ENGINE=InnoDB;

-- Only PostPublished has this uniqueness rule; other lifecycle events can repeat.
ALTER TABLE outbox_events
    ADD COLUMN published_post_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN event_type = 'PostPublished' THEN aggregate_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uq_outbox_post_published (published_post_id);

