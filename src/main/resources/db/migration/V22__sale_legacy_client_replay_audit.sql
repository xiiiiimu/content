-- Legacy per-campaign Redis reservations sometimes reused an already durable
-- account/clientKey in another campaign. Keep the first request authoritative;
-- preserve both identities and snapshots instead of creating another order.
CREATE TABLE material_sale_legacy_replays (
 legacy_reservation_id VARCHAR(64) PRIMARY KEY,
 legacy_campaign_id BIGINT NOT NULL,
 stream_id VARCHAR(64) NOT NULL,
 canonical_reservation_id VARCHAR(64) NOT NULL,
 user_id BIGINT NOT NULL,
 client_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 request_json JSON NOT NULL,
 canonical_json JSON NOT NULL,
 reason_code VARCHAR(32) NOT NULL DEFAULT 'CLIENT_KEY_REPLAY',
 recorded_at_ms BIGINT NOT NULL,
 KEY ix_sale_legacy_canonical(canonical_reservation_id),
 CHECK(reason_code='CLIENT_KEY_REPLAY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
