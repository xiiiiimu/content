import sys,pathlib,json,csv,collections,ctypes,time
sys.path.insert(0,'infra/jmeter');import run_v23_performance as v
from flash_acceptance import snapshot,redis_snapshot
b=pathlib.Path('target/v23-perf-current.txt').read_text().strip();p=pathlib.Path('docs/perf-evidence/commerce/jmeter')/(b+'-B');private=pathlib.Path('target')/b
hold=ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
try:
 with (p/'server-events.tsv').open('x',encoding='utf8') as out:
  for i,name in enumerate(['server-events-original.tsv','server-events-resumed.tsv']):
   with (p/name).open(encoding='utf8') as source:
    if i:next(source)
    for line in source:out.write(line)
 with (p/'results.jtl').open(encoding='utf-8-sig') as f:rr=list(csv.DictReader(f))
 rows=[r for r in rr if r['label']=='POST-purchase'];accepted=[r for r in rows if r['responseCode']=='200'];w=v.read(p/'window.json');config=v.read(p/'config.json');ledger=snapshot(106);cache=redis_snapshot(106,ledger['requests'])
 v.write(p/'reconciliation.json',ledger);v.write(p/'redis-reconciliation.json',cache)
 m=v.read(private/'fresh-B/formal-meta.json');buyers={r['userId']:r for r in csv.DictReader(open(m['buyersCsv'],encoding='utf-8-sig'))};replays=[]
 before=ledger['orders'][1]
 for r in accepted[:3]:
  token=v.replay_token(r['userId'],buyers[r['userId']]['token']);result=v.request('/api/sale-campaigns/106/purchase',token,{'clientKey':r['effClientKey']})
  replays.append({'clientKey':r['effClientKey'],'originalReservationId':r['reservationId'],'returnedReservationId':result.get('reservationId'),'returnedState':result.get('state')})
 after=snapshot(106)['orders'][1];replay_ok=before==after and all(x['originalReservationId']==x['returnedReservationId'] and x['returnedState']=='SUCCEEDED' for x in replays)
 v.write(p/'replays.json',{'beforePaid':before,'afterPaid':after,'requests':replays,'pass':replay_ok,'performedAfterRecovery':True})
 e=v.events_report(p/'server-events.tsv',rows,ledger,w);v.write(p/'commit-evidence.json',e)
 commits=e['uniqueOrderCommits'];known_w=[c for c in commits if w['startEpochMs']<=c['commitReturnedAtMs']<w['endEpochMs']]
 max_id=max(int(r[4]) for r in ledger['requests'] if r[5]=='PAID');max_commit=next((c for c in commits if int(c['orderId'])==max_id),None)
 first=min(int(r['timeStamp']) for r in rows);last=max(int(r['timeStamp'])+int(r['elapsed']) for r in rows);starts=sorted(int(r['timeStamp']) for r in rows);seconds=60
 groups={}
 for name,code in [('accepted','200'),('soldOut','409')]:
  rg=[r for r in rows if r['responseCode']==code];n=sum(w['startEpochMs']<=int(r['timeStamp'])+int(r['elapsed'])<w['endEpochMs'] for r in rg)
  groups[name]={'count':len(rg),'windowCompleted':n,'completionQps':n/60,**{k:v.distribution([int(r[k]) for r in rg]) for k in ['elapsed','Latency','Connect']}}
 durable={r[0]:r for r in ledger['requests']};bad=[]
 for r in accepted:
  d=durable.get(r['reservationId'])
  if not d or d[1]!=r['userId'] or d[2]!=r['effClientKey'] or d[3]!='SUCCEEDED' or d[5]!='PAID' or d[7]!='1' or d[8]!='SUCCEEDED':bad.append(r['reservationId'])
 checks={'sampleCount':0<len(rows)<=3600,'uniqueBuyers':len({r['userId'] for r in rows})==len(rows),'noSystemErrors':all(r['responseCode']=='200' for r in rows),'noReconciliationMismatch':not bad,'ledgerInvariant':len(accepted)==ledger['orders'][1]==ledger['sold']==ledger['entitlements'][0] and ledger['remaining']==ledger['stockCap']-ledger['sold'] and ledger['orders'][3]==ledger['entitlements'][1]==0,'drained':all(cache.get(k)==0 for k in ['processing','streamPending','streamLag','dispatchPending','cacheJobsPending','invalidRequests']),'redisInventory':cache['stock']==ledger['remaining'],'replays':replay_ok}
 polls=v.read(p/'client-result-polls.json');observed=[x['clientObservedSucceededMs'] for x in polls['results'] if x['clientObservedSucceededMs'] is not None]
 original_obs=[json.loads(l) for l in (p/'observations.ndjson').read_text().splitlines()];last_busy=original_obs[-1]['endEpochMs'];first_empty=int((private/'B-live-reconciliation-after-transport-fix.json').stat().st_mtime*1000)
 gap={'cause':'Windows WinError206: reservation argv exceeded command-line limit; fixed using redis-cli -x + stdin. No new purchase load.',
      'originalObserverLastMs':last_busy,'resumedJfrFile':'target/'+b+'/B-private/resume-server.jfr',
      'commitKnown':len(commits),'commitMissing':len(e['missingOrAmbiguous']),'confirmedWindowCommitOrders':len(known_w),'confirmedWindowCommitTpsLowerBound':len(known_w)/60,
      'canonicalWindowCommittedTps':None,'canonicalFullCommittedTps':None,'reasonForMissing':'Two JFR segments do not cover all per-order commits; no inferred timestamps from paid_at.',
      'maxPaidOrderId':max_id,'maxPaidOrderCommitEvidence':max_commit,
      'auxiliaryTotalPaidOverMaxOrderCommitReturnTps':len(accepted)/((max_commit['commitReturnedAtMs']-first)/1000) if max_commit else None,
      'auxiliaryMetricCaveat':'Uses recorded max-order-ID transaction return; not asserted to be the latest JDBC return of every earlier concurrent transaction. Canonical full TPS remains null.',
      'drainTimeSec':None,'drainObservedLowerBoundSec':max(0,(last_busy-last)/1000),'drainObservedUpperBoundSec':max(0,(first_empty-last)/1000),
      'drainCaveat':'Wide observation gap. Upper bound is confirmation, not measured backlog duration.',
      'finalLedgerAndRedisCheckedAtMs':v.now()}
 v.write(p/'measurement-recovery.json',gap)
 summary={'config':config,'window':w,'windowSeconds':60,'samples':len(rows),'warmSamples':len(rr)-len(rows),'requestStartSpanMs':starts[-1]-starts[0],
  'requestArrivalRate':(len(rows)-1)/((starts[-1]-starts[0])/1000),'windowStarts':sum(w['startEpochMs']<=s<w['endEpochMs'] for s in starts),'windowStartQps':sum(w['startEpochMs']<=s<w['endEpochMs'] for s in starts)/60,
  'outsideFixedWindowStarts':sum(not w['startEpochMs']<=s<w['endEpochMs'] for s in starts),'normalBusinessCompletionQps':sum(x['windowCompleted'] for x in groups.values())/60,'groups':groups,
  'httpStatuses':dict(collections.Counter(r['responseCode'] for r in rows)),'systemErrors':[],'paidOrders':ledger['orders'][1],'remainingStock':ledger['remaining'],'activeEntitlements':ledger['entitlements'][0],
  'processingLeft':0,'drainConfirmedAfterLastHttpSec':None,'windowCommittedTps':None,'fullCommittedTps':None,
  'confirmedWindowCommitOrders':len(known_w),'confirmedWindowCommitTpsLowerBound':len(known_w)/60,
  'clientObservedSucceededMs':v.distribution(observed),'clientObservedCoverage':len(observed)/len(accepted),'fullClientObservedSucceededP95Ms':None if len(observed)!=len(accepted) else v.distribution(observed)['p95'],
  'checks':checks,'correctnessPass':all(checks.values()),'measurementComplete':False,'reconciliationMismatches':bad,
  'measurementFailureReasons':['Post-run Windows command-line overflow interrupted observation; commit timing gap retained.'],
  'postprocessingRecoveredWithoutNewLoad':True}
 v.write(p/'summary.json',summary)
 first_by_thread={}
 for row in sorted(rows,key=lambda r:int(r['timeStamp'])):first_by_thread.setdefault(row['threadName'],row)
 warm=[r for r in rr if r['label']=='WARMUP-purchase']
 v.write(p/'thread-initialization.json',{'firstFormalHttpMs':v.distribution([int(r['elapsed']) for r in first_by_thread.values()]),'allFormalHttpMs':v.distribution([int(r['elapsed']) for r in rows]),'firstFormalPermitToSamplerMs':v.distribution([int(r['timeStamp'])-int(r['permitEpochMs']) for r in first_by_thread.values()]),'allFormalPermitToSamplerMs':v.distribution([int(r['timeStamp'])-int(r['permitEpochMs']) for r in rows]),'warmHttpMs':v.distribution([int(r['elapsed']) for r in warm]),'formalWorkersUsed':len(first_by_thread)})
 print(json.dumps({'samples':len(rows),'groups':groups,'paid':ledger['orders'][1],'checks':checks,'gap':gap,'clientObserved':len(observed)},indent=2),flush=True)
finally:
 if hold:ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)
