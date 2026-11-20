-- Round-1 R1: a post's topic is an author-set attribute of the post, the single source for topic statistics and the topic page.
-- Nullable: posts without a topic never enter topic statistics. MySQL 8 adds a trailing nullable column INSTANT and the index online.
ALTER TABLE posts
  ADD COLUMN topic VARCHAR(128) NULL,
  ADD KEY ix_posts_topic (topic, published_at_ms DESC, id DESC);
