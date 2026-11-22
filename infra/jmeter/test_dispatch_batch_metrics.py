import io, json, tempfile, unittest
from contextlib import redirect_stdout
from pathlib import Path
from dispatch_batch_metrics import analyze

T = '\t'


def tsv(rows):
    return '\n'.join(T.join(map(str, r)) for r in rows) + '\n'


HEAD = ['event', 'kind', 'traceId', 'clientKey', 'reservationId', 'campaignId', 'startMs', 'endMs', 'phase', 'status', 'threadId']


def ev(event, kind, trace, start, end, phase=''):
    return [event, kind, trace, '', '', 0, start, end, phase, 0, 1]


class DispatchMetricsTest(unittest.TestCase):
    def run_dir(self, profile, events, dispatch_rows=None, obs=(), lag=()):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d)
            (p / 'summary.json').write_text(json.dumps({'window': {'startEpochMs': 0, 'endEpochMs': 1000}}))
            (p / 'config.json').write_text(json.dumps({'dispatchProfile': profile, 'brokerLagSampler': {'intervalMs': 5000}}))
            (p / 'server-events.tsv').write_text(tsv([HEAD] + events))
            if dispatch_rows is not None:
                head = ['startMs', 'endMs', 'requested', 'idleSenders', 'claimed', 'sent', 'failed', 'budgetSkipped', 'confirmMissed',
                        'retryMissed', 'claimMillis', 'sendMillis', 'outcomeMillis', 'outcomePersisted', 'reservationIds']
                (p / 'server-events.tsv.dispatch.tsv').write_text(tsv([head] + dispatch_rows))
            (p / 'observations.ndjson').write_text(''.join(json.dumps(o) + '\n' for o in obs))
            (p / 'broker-lag.ndjson').write_text(''.join(json.dumps(x) + '\n' for x in lag))
            with redirect_stdout(io.StringIO()):
                return analyze(p)

    def test_batch_counts_one_claim_and_one_confirm_commit_for_two_rows(self):
        events = [ev('request', 'dispatch-claim', 'c1', 100, 160), ev('phase', 'dispatch-claim', 'c1', 100, 160, 'dispatch.claim'),
                  ev('phase', 'dispatch-claim', 'c1', 110, 120, 'sql.SaleIngressMapper.due'), ev('phase', 'dispatch-claim', 'c1', 120, 130, 'sql.SaleIngressMapper.claimAll'),
                  ev('phase', 'dispatch-claim', 'c1', 150, 160, 'jdbc.commit'),
                  ev('request', 'dispatch-claim', 'c2', 900, 910), ev('phase', 'dispatch-claim', 'c2', 900, 910, 'dispatch.claim'),
                  ev('request', 'dispatch-confirm', 'k1', 200, 260), ev('phase', 'dispatch-confirm', 'k1', 200, 260, 'dispatch.confirm'),
                  ev('phase', 'dispatch-confirm', 'k1', 250, 260, 'jdbc.commit')]
        r = self.run_dir('B:batch,20,1', events, [[260, 260, 2, 1, 2, 2, 0, 0, 0, 0, 60, 40, 60, 'true', 'r1,r2']])
        self.assertEqual({'0': 1, '2': 1}, r['claimSizeDistribution'])
        self.assertEqual(2, r['window']['commitsTotal'])
        self.assertEqual(2, r['window']['sqlTotal'])
        self.assertFalse(any(r['leaseViolations'].values()))
        self.assertEqual(160, r['leaseUseMs']['max'])

    def test_lease_violations_and_failed_outcome_are_reported(self):
        r = self.run_dispatch_violation()
        self.assertEqual(1, r['leaseViolations']['budgetSkipped'])
        self.assertEqual(1, r['leaseViolations']['confirmMissed'])
        self.assertEqual(1, r['leaseViolations']['outcomeNotPersisted'])
        self.assertEqual(1, r['leaseViolations']['leaseUseOver25000Ms'])

    def run_dispatch_violation(self):
        return self.run_dir('C:batch,20,4', [], [[1, 1, 8, 4, 8, 6, 0, 1, 1, 0, 100, 25000, 1000, 'false', 'x']])

    def test_serial_claims_and_backlog_transfer_series(self):
        events = [ev('request', 'dispatch', 's1', 100, 300), ev('phase', 'dispatch', 's1', 100, 150, 'dispatch.claim'),
                  ev('phase', 'dispatch', 's1', 150, 200, 'mq.send'), ev('phase', 'dispatch', 's1', 200, 300, 'dispatch.confirm'),
                  ev('request', 'dispatch', 's2', 400, 420), ev('phase', 'dispatch', 's2', 400, 420, 'dispatch.claim'),
                  ev('request', 'settle', 't1', 500, 540), ev('phase', 'settle', 't1', 530, 540, 'jdbc.commit')]
        obs = [{'startEpochMs': 0, 'database': {'dispatch': [['3', '2', '1', '0', '1']], 'campaign': [['3', '3', '0', '0']], 'receipts': [['0']],
                                                'lockWaitsByTable': [['material_sale_campaigns', '2']]}, 'redis': {'globalStreamLag': 5, 'globalStreamPending': 0}},
               {'startEpochMs': 1500, 'database': {'dispatch': [['3', '0', '0', '3', '1']], 'campaign': [['3', '0', '3', '3']], 'receipts': [['3']],
                                                   'lockWaitsByTable': []}, 'redis': {'globalStreamLag': 0, 'globalStreamPending': 0}}]
        lag = [{'startEpochMs': 0, 'ok': True, 'diffTotal': 0}, {'startEpochMs': 900, 'ok': True, 'diffTotal': 7}, {'startEpochMs': 1400, 'ok': False}]
        r = self.run_dir('A:serial,20,1', events, None, obs, lag)
        self.assertEqual({'0': 1, '1': 1}, r['claimSizeDistribution'])
        self.assertEqual(3, r['backlog']['peaks']['mqPending']['value'])
        self.assertEqual(7, r['backlog']['peaks']['brokerBacklog']['value'])
        self.assertEqual(2, r['backlog']['lagSamplesOk'])
        self.assertEqual(500, r['backlog']['firstObservedDrainAfterWindowMs'])
        self.assertEqual({'material_sale_campaigns': 2}, r['lockWaits']['observedWaitsByTable'])
        self.assertEqual(1, r['settle']['committedInWindow'])


if __name__ == '__main__':
    unittest.main()
