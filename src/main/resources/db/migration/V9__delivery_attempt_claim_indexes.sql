-- Attempt-aware claim indexes: exhausted/candidate must not PRIMARY-scan the DONE heap.
ALTER TABLE feed_delivery_tasks
  ADD KEY ix_delivery_retry_attempt (state, attempt, next_retry_at, id),
  ADD KEY ix_delivery_lease_attempt (state, attempt, lease_until, id);
