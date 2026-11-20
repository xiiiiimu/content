import csv,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from flash_acceptance import report
class FlashReportTest(unittest.TestCase):
 def run_case(self,reject='SOLD_OUT',status='409',extra_entitlements=0,start_span_ms=1,cache=None,drain_sec=0,observed=None):
  with tempfile.TemporaryDirectory() as tmp:
   p=Path(tmp);buyers=p/'buyers.csv';buyers.write_text('userId,clientKey\n1,k1\n2,k2\n')
   meta=p/'meta.json';meta.write_text(json.dumps({'campaignId':1,'postId':1,'buyersCsv':str(buyers)}))
   (p/'barrier-stats.json').write_text(json.dumps({'readyCount':2,'configuredGroupSize':2,'timedOut':False}))
   rows=[dict(timeStamp='1000',elapsed='20',responseCode='200',responseMessage='OK',userId='1',effClientKey='k1',reservationId='r1',businessCode=''),dict(timeStamp=str(1000+start_span_ms),elapsed='40',responseCode=status,responseMessage='Conflict',userId='2',effClientKey='k2',reservationId='',businessCode=reject)]
   with (p/'results.jtl').open('w',newline='') as f:w=csv.DictWriter(f,fieldnames=rows[0].keys());w.writeheader();w.writerows(rows)
   state={'stockCap':1,'sold':1,'remaining':0,'requests':[['r1','1','k1','SUCCEEDED','1','PAID','1020','1','SUCCEEDED']],'orders':[1,1,0,0],'entitlements':[1+extra_entitlements,extra_entitlements]}
   obs=None
   if observed is not None:obs=p/'observations.ndjson';obs.write_text(json.dumps(observed)+'\n')
   with patch('flash_acceptance.snapshot',return_value=state),patch('flash_acceptance.redis_snapshot',return_value=cache or {'stock':0,'processing':0}),patch('flash_acceptance.time.time',return_value=(1040+start_span_ms)/1000+drain_sec),patch('builtins.print'):code=report(p,meta,2,drain_timeout=0,observations=obs)
   return code,json.loads((p/'acceptance-report.json').read_text())
 def test_limited_stock_reject_is_expected(self):
  code,r=self.run_case();self.assertEqual(0,code);self.assertEqual(1,r['paidOrders']);self.assertEqual(40,r['p99Ms']);self.assertEqual({'SOLD_OUT':1},r['businessRejects'])
 def test_401_never_counts_as_stock_reject(self):
  code,r=self.run_case('AUTHENTICATION_REQUIRED','401');self.assertEqual(1,code);self.assertIn('unexpected HTTP or business rejection',r['failureReasons'])
 def test_full_barrier_does_not_hide_spread_requests(self):
  code,r=self.run_case(start_span_ms=6000);self.assertEqual(1,code);self.assertFalse(r['synchronyValid']);self.assertIn('synchronized request start span exceeds 5000ms',r['failureReasons'])
 def test_orphan_active_entitlement_is_not_hidden_by_order_join(self):
  code,r=self.run_case(extra_entitlements=1);self.assertEqual(1,code);self.assertEqual(2,r['activeEntitlements']);self.assertIn('inventory/order/entitlement invariant failed',r['failureReasons'])
 def test_missing_evidence_stays_failed_with_unknown_counts(self):
  from flash_acceptance import failure_report
  with tempfile.TemporaryDirectory() as tmp:
   p=Path(tmp)
   with patch('builtins.print'):code=failure_report(p,p/'missing-meta',5000,FileNotFoundError())
   r=json.loads((p/'acceptance-report.json').read_text());self.assertEqual(1,code);self.assertFalse(r['pass']);self.assertIsNone(r['connectFailures']);self.assertIsNone(r['paidOrders'])
 def test_v2_unacknowledged_stream_does_not_pass_even_with_paid_orders(self):
  cache=dict(version=2,cacheJobsPending=0,stock=0,processing=0,streamPending=1,streamLag=0,dispatchPending=0,dispatchCount=1,invalidRequests=0,ready='1')
  code,r=self.run_case(cache=cache);self.assertEqual(1,code);self.assertIn('Stream bridge or MQ dispatch not drained',r['failureReasons'])
 def test_v2_fully_drained_pipeline_passes(self):
  cache=dict(version=2,cacheJobsPending=0,stock=0,processing=0,streamPending=0,streamLag=0,dispatchPending=0,dispatchCount=1,invalidRequests=0,ready='1')
  code,r=self.run_case(cache=cache);self.assertEqual(0,code)
 def test_slow_drain_cannot_be_hidden_by_longer_observation_window(self):
  code,r=self.run_case(drain_sec=31);self.assertEqual(1,code);self.assertIn('drain exceeded 30 seconds after last HTTP completion',r['failureReasons'])
 def test_connect_timeout_is_a_connection_failure_as_well_as_a_timeout(self):
  code,r=self.run_case('', 'Non HTTP response code: org.apache.http.conn.ConnectTimeoutException')
  self.assertEqual(1,r['connectFailures']);self.assertEqual(1,r['timeouts'])
 def test_observed_full_pipeline_drain_is_not_inflated_by_report_collection_delay(self):
  cache=dict(version=2,cacheJobsPending=0,stock=0,processing=0,streamPending=0,streamLag=0,dispatchPending=0,dispatchCount=1,invalidRequests=0,ready='1')
  tick=dict(campaignId=1,startEpochMs=1020,endEpochMs=1040,database=dict(campaign=[['1','0','1','1']],receipts=[['1']],dispatch=[['1','0','0','1','1']],cacheJobs=[['0']]),redis=dict(campaignStock=0,ready='1',globalStreamPending=0,globalStreamLag=0))
  code,r=self.run_case(cache=cache,drain_sec=31,observed=tick)
  self.assertEqual(0,code);self.assertEqual(0,r['drainAfterLastHttpSec'])
if __name__=='__main__':unittest.main()
