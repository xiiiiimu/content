-- Existing campaigns keep the legacy default-version lookup. New campaigns pin their version.
ALTER TABLE material_sale_campaigns
  ADD COLUMN frozen_version_id BIGINT NULL,
  ADD CONSTRAINT fk_sale_campaign_frozen_version
    FOREIGN KEY (frozen_version_id) REFERENCES material_versions(id);
