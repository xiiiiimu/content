-- docs/commerce-campaign-lock-analysis.md option (b): the FK checks on material_purchase_requests and material_orders
-- took a shared lock on the hot material_sale_campaigns row, serializing bridge inserts and settles behind it.
-- Campaign rows are never deleted (only state/sold_count change), so the reference is now checked by reconciliation
-- (orphan_references in infra/jmeter/run_v23_performance.py). The campaign_id indexes are kept.
ALTER TABLE material_purchase_requests DROP FOREIGN KEY fk_purchase_campaign;
ALTER TABLE material_orders DROP FOREIGN KEY fk_order_campaign;
