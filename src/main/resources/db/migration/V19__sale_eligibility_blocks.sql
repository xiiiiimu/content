-- Durable source of truth for campaign eligibility denies (giveaway winners + blacklist snapshot / dynamic adds).
-- Redis deny hash is a cache; missing cache must fail closed, then restore from this table.
CREATE TABLE material_sale_eligibility_blocks (
  campaign_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reason VARCHAR(32) NOT NULL,
  created_at_ms BIGINT NOT NULL,
  PRIMARY KEY (campaign_id, user_id),
  KEY idx_sale_elig_user (user_id),
  CONSTRAINT fk_sale_elig_campaign FOREIGN KEY (campaign_id) REFERENCES material_sale_campaigns(id)
);
