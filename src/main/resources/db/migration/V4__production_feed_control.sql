CREATE TABLE feed_control (
 id INT PRIMARY KEY,
 state VARCHAR(16) NOT NULL,
 generation BIGINT NOT NULL,
 reason VARCHAR(512),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
INSERT INTO feed_control(id,state,generation) VALUES(1,'PRIMARY',1);
CREATE INDEX ix_posts_order ON posts(published_at_ms DESC,id DESC);
CREATE INDEX ix_posts_mode_order ON posts(delivery_mode,published_at_ms DESC,id DESC);
