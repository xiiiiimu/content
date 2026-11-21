import unittest
from run_v23_performance import orphan_references


class OrphanReferencesTest(unittest.TestCase):
    """V26 dropped fk_purchase_campaign / fk_order_campaign; reconciliation must catch what the FKs used to prevent."""

    def test_counts_requests_and_orders_without_campaign_and_campaign_mismatch(self):
        seen = []

        def fake(sql):
            seen.append(sql)
            return [['2'], ['1'], ['3']]
        r = orphan_references(fake)
        self.assertEqual({'purchaseRequestsWithoutCampaign': 2, 'ordersWithoutCampaign': 1, 'ordersCampaignMismatch': 3}, r)
        self.assertTrue(seen and 'material_sale_campaigns' in seen[0] and 'material_orders' in seen[0])

    def test_clean_schema_is_all_zero(self):
        self.assertEqual({'purchaseRequestsWithoutCampaign': 0, 'ordersWithoutCampaign': 0, 'ordersCampaignMismatch': 0},
                         orphan_references(lambda sql: [['0'], ['0'], ['0']]))


if __name__ == '__main__':
    unittest.main()
