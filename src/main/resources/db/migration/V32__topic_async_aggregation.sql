-- Hardening B1: interactions write only topic_event_ledger; a background aggregator folds pending rows into
-- topic_minute_buckets and runs the spike check, so no interaction transaction touches a topic-wide shared row.
--
-- aggregated_at_ms: NULL = pending (written by the interaction path, not yet in a bucket); a time = folded at that time.
--   The default 0 marks rows that were already counted when they were written: every row that exists before this
--   migration (the old write path updated the bucket in the same transaction), and rows inserted outside the write path.
-- revoked_at_ms: a counted like was taken back (unlike or dislike). The row is renamed off its like:{post}:{user} id so the
--   user can like again, and set pending again; the aggregator subtracts it from its own minute bucket and deletes it.
ALTER TABLE topic_event_ledger
  ADD COLUMN aggregated_at_ms BIGINT NULL DEFAULT 0,
  ADD COLUMN revoked_at_ms BIGINT NULL DEFAULT NULL,
  ADD KEY ix_ledger_pending (aggregated_at_ms, occurred_at_ms);

-- One row: the aggregation lease (one executor across instances) and its recorded progress.
-- watermark_ms: every ledger event with occurred_at_ms below it is folded (TopicRetention's processed watermark, C-13).
-- It starts at 0, so the ledger purge deletes nothing until the aggregator has run once.
CREATE TABLE topic_aggregator_state (
  id TINYINT PRIMARY KEY,
  owner VARCHAR(128) NULL,
  lease_until_ms BIGINT NOT NULL DEFAULT 0,
  watermark_ms BIGINT NOT NULL DEFAULT 0,
  last_batch_at_ms BIGINT NOT NULL DEFAULT 0,
  folded_total BIGINT NOT NULL DEFAULT 0,
  revoked_total BIGINT NOT NULL DEFAULT 0,
  last_max_lag_ms BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT INTO topic_aggregator_state(id) VALUES (1);
