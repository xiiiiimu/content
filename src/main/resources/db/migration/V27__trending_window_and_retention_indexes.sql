-- Round-1 R2: time-leading indexes.
-- ix_bucket_start serves the trending window read (bucket_start_ms >= ?) and the retention purge.
-- It deliberately excludes net_events/active_interactions so hot-path bucket increments never touch this index.
ALTER TABLE topic_minute_buckets ADD KEY ix_bucket_start (bucket_start_ms);
-- ix_ledger_time serves the retention purge and the windowed rebuild (occurred_at_ms >= ?).
ALTER TABLE topic_event_ledger ADD KEY ix_ledger_time (occurred_at_ms);
