import unittest

from purchase_capacity import summarize


class CapacityContract(unittest.TestCase):
    def setUp(self):
        self.row = dict(timeStamp='1500', elapsed='100', responseCode='200',
                        responseMessage='', reservationId='r1', userId='9',
                        effClientKey='jm-c42-u9', threadName='buyer-1', label='POST-purchase')
        self.durable = {'r1': ('9', 'jm-c42-u9', 'SUCCEEDED', '7', 'PAID',
                               '2050', '1550', '1', 'SUCCEEDED')}

    def measure(self, rows=None, sold=1):
        return summarize([self.row] if rows is None else rows, 1,
                         dict(startEpochMs=1000, endEpochMs=2000), 3000,
                         42, self.durable, {'9': 'jm-c42-u9'}, sold)

    def test_distinct_measurement_windows(self):
        report = self.measure()
        self.assertEqual(report['requestQps'], 10)
        self.assertEqual(report['formalRequestQps'], 1)
        self.assertEqual(report['settleWindowTps'], 0)
        self.assertEqual(report['settleFullTps'], .5)
        self.assertEqual(report['settlementP95Ms'], 500)
        self.assertEqual(report['failures'], [])

    def test_old_campaign_and_missing_purchase_entitlement_fail(self):
        self.row['effClientKey'] = 'jm-c41-u9'
        self.durable['r1'] = (*self.durable['r1'][:7], '0', 'SUCCEEDED')
        report = self.measure()
        self.assertEqual(report['mismatchCounts'], dict(unexpectedClientKey=1,
                         reservationOwnershipMismatch=1, invalidSettlementChain=1))
        self.assertIn('reconciliation mismatch', report['failures'])

    def test_connection_and_http_503_have_separate_gates(self):
        failed = dict(self.row, responseCode='Non HTTP response code: HttpHostConnectException',
                      responseMessage='Connection refused', reservationId='')
        report = self.measure([failed, dict(self.row, responseCode='503')])
        self.assertIn('connection failures', report['failures'])
        self.assertIn('HTTP 5xx', report['failures'])
        self.assertEqual(report['perSecond']['1']['httpResponses'], 1)
        self.assertEqual(report['perSecond']['1']['connectFailures'], 1)

    def test_processing_and_twice_returned_reservation_fail(self):
        self.durable['r1'] = ('9', 'jm-c42-u9', 'PROCESSING', 'NULL', 'NULL',
                               'NULL', '1550', '0', 'NULL')
        report = self.measure([self.row, self.row], sold=0)
        self.assertIn('settlement not drained', report['failures'])
        self.assertEqual(report['mismatchCounts']['durableWithoutExactlyOneHttp200'], 1)

    def test_rate_gate_checks_rolling_seconds_across_wall_clock_buckets(self):
        rows = [dict(self.row, timeStamp='1990'), dict(self.row, timeStamp='2005')]
        report = summarize(rows, 2, dict(startEpochMs=1000, endEpochMs=3000), 4000,
                           42, self.durable, {'9': 'jm-c42-u9'}, 1, rate_limit=1)
        self.assertEqual(report['maxStartsPerRollingSecond'], 2)
        self.assertIn('measured starts exceeded the rolling one-second target', report['failures'])


if __name__ == '__main__':
    unittest.main()
