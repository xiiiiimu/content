-- Hardening C2 (C-5): when a delivery task reached DONE, so retention ages DONE tasks by completion rather than by claim.
-- Nullable trailing column: MySQL 8 adds it INSTANT. Rows that were DONE before this migration keep NULL and are aged by
-- last_attempt_at (their last claim), an earlier time than the real completion.
ALTER TABLE feed_delivery_tasks ADD COLUMN completed_at DATETIME(6) NULL;
