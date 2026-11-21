import csv,tempfile,unittest
from pathlib import Path
from run_v23_performance import events_report,distribution
from v23_plan import generate
import xml.etree.ElementTree as E

class V23MetricsTests(unittest.TestCase):
    def test_commit_is_first_main_transaction_not_cache_or_duplicate_attempt(self):
        fields='event kind traceId clientKey reservationId campaignId startMs endMs phase status'.split()
        def row(event,start,end,phase='',kind='settle'):
            return dict(zip(fields,[event,kind,'settle-r','','r','0',str(start),str(end),phase,'0']))
        events=[row('request',100,200),row('phase',120,121,'sql.SaleMapper.insertOrder'),
                row('phase',130,131,'sql.SaleMapper.finishRequest'),row('phase',132,140,'jdbc.commit'),
                row('phase',150,180,'jdbc.commit'),row('request',210,250),row('phase',220,230,'jdbc.commit')]
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'events.tsv'
            with p.open('w',newline='') as f:
                w=csv.DictWriter(f,fieldnames=fields,delimiter='\t');w.writeheader();w.writerows(events)
            result=events_report(p,[dict(reservationId='r',responseCode='200',effClientKey='key',timeStamp='90')],
                dict(requests=[['r','u','key','SUCCEEDED','1','PAID','125']],orders=[1,1,0,0]),
                dict(startEpochMs=100,endEpochMs=150))
            self.assertTrue(result['commitEvidenceComplete']);self.assertEqual(140,result['uniqueOrderCommits'][0]['commitReturnedAtMs'])
            self.assertEqual(1,result['windowCommittedOrders']);self.assertEqual(50,result['httpStartToServerCommitReturnedMs']['p95'])

    def test_missing_commit_is_unknown_not_zero(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'events.tsv';p.write_text('event\tkind\ttraceId\tclientKey\treservationId\tcampaignId\tstartMs\tendMs\tphase\tstatus\n')
            r=events_report(p,[dict(reservationId='r',responseCode='200',effClientKey='key')],dict(requests=[],orders=[1,1,0,0]),dict(startEpochMs=100,endEpochMs=200))
            self.assertIsNone(r['windowCommittedTps']);self.assertFalse(r['commitEvidenceComplete'])

    def test_one_same_sampler_and_no_heavy_listener_or_csv_tokens_in_plan(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'plan.jmx';generate(p);root=E.parse(p).getroot()
            self.assertEqual(1,len(list(root.iter('HTTPSamplerProxy'))));self.assertEqual(1,len(list(root.iter('ThreadGroup'))))
            self.assertFalse(list(root.iter('ResultCollector')));self.assertFalse(list(root.iter('CSVDataSet')))
            self.assertIn('${phaseLabel}',p.read_text())

    def test_nearest_rank_no_empty_fabrication(self):
        self.assertEqual(95,distribution(list(range(1,101)))['p95'])
        self.assertIsNone(distribution([])['p95'])

    def test_failed_settlement_root_cannot_prove_successful_commit_return(self):
        fields='event kind traceId clientKey reservationId campaignId startMs endMs phase status'.split()
        def row(event,start,end,phase='',status='0'):
            return dict(zip(fields,[event,'settle','settle-r','','r','0',str(start),str(end),phase,status]))
        rows=[row('request',100,200),row('phase',120,121,'sql.SaleMapper.insertOrder'),row('phase',130,131,'sql.SaleMapper.finishRequest'),row('phase',132,140,'jdbc.commit')]
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'events.tsv'
            with p.open('w',newline='') as f:
                writer=csv.DictWriter(f,fieldnames=fields,delimiter='\t');writer.writeheader();writer.writerows(rows)
            result=events_report(p,[dict(reservationId='r',responseCode='200',effClientKey='key',timeStamp='90')],dict(requests=[['r','u','key','SUCCEEDED','1','PAID','125']],orders=[1,1,0,0]),dict(startEpochMs=100,endEpochMs=150),require_success=True)
            self.assertFalse(result['commitEvidenceComplete']);self.assertIsNone(result['windowCommittedTps'])

if __name__=='__main__':unittest.main()
