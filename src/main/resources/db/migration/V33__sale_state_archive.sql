-- Hardening C3: ended campaigns' sale state leaves Redis in two resumable steps.
-- cache_archived_at_ms: archived on the MySQL side (its cache jobs no longer write Redis);
-- cache_released_at_ms: its Redis fields were deleted.
ALTER TABLE material_sale_campaigns
 ADD COLUMN cache_archived_at_ms BIGINT NULL,
 ADD COLUMN cache_released_at_ms BIGINT NULL;

-- C3 option B: clientKey identity is per campaign. The same key in another campaign is a different purchase request.
-- Existing rows are unique on (user_id, client_key), hence also on the new key. V21's binary collation is kept.
ALTER TABLE material_purchase_requests
 DROP INDEX uq_purchase_client,
 ADD UNIQUE KEY uq_purchase_client_campaign (user_id, campaign_id, client_key);
