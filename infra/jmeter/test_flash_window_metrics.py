import json, tempfile, unittest
from pathlib import Path
import flash_window_metrics as m

HDR = 'timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n'


def sample(start, elapsed, status, code='', commit=None):
    x = {'startMs': start, 'endMs': start + elapsed, 'elapsed': elapsed, 'status': status, 'businessCode': code,
         'database': {'paidAtMs': (commit or 0) - 5} if status == '200' else None}
    if commit is not None:
        x['settlementCommitReturnedMs'] = commit
    return x


class WindowMetricsTest(unittest.TestCase):
    def build(self, rows, drain=0):
        d = Path(tempfile.mkdtemp())
        (d / 't.json').write_text(json.dumps(rows))
        (d / 'r.jtl').write_text(HDR + ''.join(f"{r['startMs']},{r['elapsed']},POST-purchase,{r['status']},,t,text,true,,1,1,1,1,u,1,0,1\n" for r in rows))
        return m.run({'run': 'x', 'campaign': 1, 'build': 'b', 'sync': True, 'note': '', 'timelines': str(d / 't.json'),
                      'jtl': str(d / 'r.jtl'), 'drainConfirmedAtMs': drain})

    def test_nearest_rank(self):
        self.assertEqual(m.pct(list(range(1, 101)), .95), 95)
        self.assertEqual(m.pct([7], .99), 7)

    def test_classes_window_and_commit_tps(self):
        rows = [sample(0, 1000, '200', commit=1500), sample(0, 2000, '409', 'SOLD_OUT'),
                sample(0, 2000, 'Non HTTP response code: org.apache.http.conn.ConnectTimeoutException'),
                sample(0, 500, '503')]
        r = self.build(rows)
        self.assertEqual(r['window']['seconds'], 2.0)
        self.assertEqual(r['counts']['connectTimeout'], 1)
        self.assertEqual(r['counts']['http5xx'], 1)
        self.assertEqual(r['completionQps']['normalBusinessTotal'], 1.0)
        self.assertEqual(r['orderTps']['windowTps'], 0.5)
        self.assertEqual(r['e2eLatencyMs']['acceptedToCommitted']['p50'], 1500)
        self.assertEqual(r['e2eLatencyMs']['excludedNoFinalOutcome'], 2)

    def test_missing_commit_is_not_inferred(self):
        rows = [sample(0, 1000, '200'), sample(0, 2000, '409', 'SOLD_OUT')]
        rows[0]['database'] = {'paidAtMs': 1800}
        r = self.build(rows, drain=4000)
        self.assertIsNone(r['orderTps']['windowTps'])
        self.assertIsNone(r['e2eLatencyMs']['acceptedToCommitted'])
        self.assertEqual(r['orderTps']['drainConfirmedTps'], 0.25)


if __name__ == '__main__':
    unittest.main()
