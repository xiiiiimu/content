import csv,io,json,tempfile,unittest
from contextlib import redirect_stdout
from pathlib import Path
from bridge_batch_metrics import analyze

class BridgeMetricsTest(unittest.TestCase):
 def make(self,duplicate=False):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)
   (p/'summary.json').write_text(json.dumps({'config':{'bridgeBatchSize':20},'window':{'startEpochMs':0,'endEpochMs':1000},'groups':{'accepted':{'count':2}}}))
   (p/'reconciliation.json').write_text(json.dumps({'requests':[['r1'],['r2']]}))
   (p/'results.jtl').write_text('label,timeStamp\nPOST-purchase,100\nPOST-purchase,150\n')
   (p/'server-events.tsv.batches.tsv').write_text('traceId\treadCount\tnormalCount\tnewCount\tnewReservationIds\tcommitReturnedAtMs\treservationIds\nb1\t2\t2\t2\t'+('r1,r1' if duplicate else 'r1,r2')+'\t301\tr1,r2\n')
   (p/'server-events.tsv').write_text('event\tkind\ttraceId\tstartMs\tendMs\tphase\nphase\tpersist-batch\tb1\t100\t300\tbridge.transaction\nphase\tpersist-batch\tb1\t120\t130\tsql.bulkInsert\nphase\tpersist-batch\tb1\t290\t300\tjdbc.commit\n')
   with redirect_stdout(io.StringIO()):return analyze(p)
 def test_one_bulk_sql_and_one_commit_for_two_rows(self):
  r=self.make();self.assertTrue(r['identityCommitCoverageComplete']);self.assertEqual(1,r['normalBatchSqlCount']);self.assertEqual(1,r['normalBatchSuccessfulTransactions']);self.assertEqual(2,r['windowBridgePersistRate']);self.assertEqual({2:1},r['actualReadSizeDistribution'])
 def test_duplicate_ids_cannot_inflate_verified_persist_rate(self):
  r=self.make(True);self.assertFalse(r['identityCommitCoverageComplete']);self.assertIsNone(r['windowBridgePersistRate']);self.assertIsNone(r['fullBridgePersistRate'])

if __name__=='__main__':unittest.main()
