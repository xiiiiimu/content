import unittest
import json,tempfile,csv
from unittest.mock import patch
from pathlib import Path
from analyze_purchase_latency import distribution
from analyze_purchase_latency import analyze
from analyze_sale_jfr import duration
from analyze_sale_jfr import analyze as analyze_phases
from correlate_injector_waits import union_ms
from summarize_jmeter_safepoints import summarize

class LatencyMeasurementTest(unittest.TestCase):
    def test_background_traces_are_not_counted_as_order_settlement_or_lock_holding(self):
        from datetime import datetime,timezone
        def event(event_type,trace,start,length,**values):
            return dict(type=event_type,values=dict(traceId=trace,startTime=datetime.fromtimestamp(start/1000,timezone.utc).isoformat(),duration=f'PT{length/1000}S',**values))
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'events.json';out=root/'out';jtl=root/'results.jtl'
            row=dict(timeStamp='0',elapsed='1',responseCode='200',effClientKey='jm-c1-u1',reservationId='r1',userId='1',businessCode='',Connect='0',Latency='1')
            with jtl.open('w',newline='') as f:w=csv.DictWriter(f,fieldnames=row);w.writeheader();w.writerow(row)
            events=[event('content.SaleRequest','jm-c1-u1',0,1,kind='http',clientKey='jm-c1-u1',reservationId='r1',status=200),event('content.SaleRequest','settle-r1',10,30,kind='settle',reservationId='r1'),event('content.SalePhase','settle-r1',11,2,phase='sql.mapper.lockCampaign'),event('content.SalePhase','settle-r1',14,1,phase='jdbc.commit'),event('content.SalePhase','settle-r1',30,1,phase='jdbc.commit'),event('content.SaleRequest','persist-r1',5,4,kind='persist',reservationId='r1'),event('content.SaleRequest','dispatch-r1',7,10,kind='dispatch',reservationId='r1')]
            source.write_text(json.dumps(dict(recording=dict(events=events))))
            with patch('builtins.print'):analyze_phases(source,jtl,out)
            report=json.loads((out/'phase-distributions.json').read_text())
            self.assertEqual(1,report['settlementEvents']);self.assertEqual({'persist':1,'dispatch':1},report['backgroundEvents'])
            self.assertEqual(2,report['settlementPhaseDistributions']['campaign.lock.return.to.commit.return']['p50'])
    def test_order_commit_excludes_later_cache_sync_transactions(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);out=root/'out';phases=root/'phases.json'
            row=dict(timeStamp='1000',elapsed='2',responseCode='200',responseMessage='OK',effClientKey='jm-c1-u1',reservationId='r1',userId='1',businessCode='',label='purchase',Connect='0',Latency='2')
            with (root/'results.jtl').open('w',newline='') as f:
                w=csv.DictWriter(f,fieldnames=row);w.writeheader();w.writerow(row)
            phases.write_text(json.dumps([dict(clientKey='jm-c1-u1',server=dict(startMs=1000,durationMs=2),settlement=dict(startMs=1010,durationMs=20,phases=[dict(phase='jdbc.commit',startMs=1014,durationMs=2),dict(phase='jdbc.commit',startMs=1025,durationMs=1)]))]))
            with patch('analyze_purchase_latency.mysql',side_effect=[[['0']],[['jm-c1-u1','r1','1','SUCCEEDED','1000','1010','1020','3','PAID','1015','1020','SUCCEEDED']]]),patch('builtins.print'):
                analyze(root,out,1,phases)
            record=json.loads((out/'request-db-timelines.json').read_text())[0]
            self.assertEqual(1016,record['settlementCommitReturnedMs'])
    def test_nearest_rank_does_not_interpolate_tail(self):
        rows=[{'elapsed':str(i)} for i in range(1,201)]
        self.assertEqual(distribution(rows,'elapsed'),{'p50':100,'p95':190,'p99':198})
        self.assertEqual(distribution([],'elapsed'),{})
    def test_jfr_duration_preserves_submillisecond_and_minute_values(self):
        self.assertAlmostEqual(duration('PT0.000123456S'),.123456)
        self.assertAlmostEqual(duration('PT1M0.001S'),60001)
    def test_nested_and_touching_waits_are_counted_once(self):
        self.assertEqual(union_ms([(10,20),(12,18),(20,30),(40,45)]),25)
        self.assertEqual(union_ms([]),0)
    def test_safepoints_use_nanoseconds_and_exclude_preparation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);log=root/'gc.log';accept=root/'acceptance.json';output=root/'summary.json'
            accept.write_text(json.dumps({'firstRequestStartMs':1000,'lastHttpCompletionMs':2000}),encoding='utf8')
            log.write_text('[1970-01-01T00:00:00.500+0000][info][safepoint] Safepoint "Cleanup", Reaching safepoint: 1000000 ns, At safepoint: 1000000 ns, Total: 2000000 ns\n'
              '[1970-01-01T00:00:02.000+0000][info][safepoint] Safepoint "ICBufferFull", Reaching safepoint: 200000000 ns, At safepoint: 300000000 ns, Total: 500000000 ns\n',encoding='utf8')
            summarize(log,accept,output);result=json.loads(output.read_text(encoding='utf8'))
            self.assertTrue(result['available'])
            self.assertNotIn('Cleanup',result['overlappingHttpWindow'])
            self.assertEqual(result['overlappingHttpWindow']['ICBufferFull']['sumReachingMs'],200)
            self.assertEqual(result['overlappingHttpWindow']['ICBufferFull']['maxTotalMs'],500)

if __name__=='__main__':unittest.main()
