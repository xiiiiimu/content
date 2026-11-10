CREATE TABLE trend_topic_serial (
    topic VARCHAR(128) PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Full business key: one TOPIC_SPIKE (or kind) per topic per window.
ALTER TABLE trend_risk_events
  ADD UNIQUE KEY uq_risk_business (kind, topic, window_start_ms);
