import unittest
import campaign_lock_digest as d


class DigestTest(unittest.TestCase):
    def test_dispatch_rates_window_and_drain(self):
        sends = [1000, 1500, 2500, 3000] + [40000 + 500 * k for k in range(11)]
        self.assertEqual((1.5, 2.2), d.dispatch_rates(sends, 1000, 3000))

    def test_no_drain_rate_without_two_late_sends(self):
        self.assertEqual((0.5, None), d.dispatch_rates([1000, 50000], 0, 2000))

    def test_quantile(self):
        self.assertEqual(3, d.q([1, 2, 3, 4], .5)); self.assertIsNone(d.q([], .5))


if __name__ == '__main__':
    unittest.main()
