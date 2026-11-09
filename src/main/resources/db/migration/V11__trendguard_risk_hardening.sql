-- RED-phase schema widen only; GREEN adds UNIQUE(kind,topic,window) + topic serial lock table.
ALTER TABLE trend_risk_events
  MODIFY event_id VARCHAR(256) NOT NULL,
  ADD COLUMN window_start_ms BIGINT NOT NULL DEFAULT 0 AFTER kind;
