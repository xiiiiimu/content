"""Offline supplemental V23 timing/queue/resource audit; never connects to services."""
import collections,csv,json,math,sys
from pathlib import Path

def dist(values):
    s=sorted(values)
    return {'count':len(s),**{k:(s[math.ceil(len(s)*p)-1] if s else None) for k,p in [('p50',.5),('p95',.95),('p99',.99),('max',1)]}}
def analyze(directory):
    p=Path(directory);summary=json.loads((p/'summary.json').read_text());w=summary['window']
    with (p/'results.jtl').open(encoding='utf-8-sig') as f:rows=[r for r in csv.DictReader(f) if r['label']=='POST-purchase']
    starts=sorted(int(r['timeStamp']) for r in rows);last_http=max(int(r['timeStamp'])+int(r['elapsed']) for r in rows)
    rolling=0;left=0
    for right,t in enumerate(starts):
        while starts[left]<=t-1000:left+=1
        rolling=max(rolling,right-left+1)
    seconds=collections.defaultdict(collections.Counter)
    for r in rows:
        seconds[int(r['timeStamp'])//1000]['starts']+=1
        seconds[(int(r['timeStamp'])+int(r['elapsed']))//1000]['completed'+r['responseCode']]+=1
    commit=json.loads((p/'commit-evidence.json').read_text());by_rid={c['reservationId']:c for c in commit['uniqueOrderCommits']}
    by_key={r['effClientKey']:r for r in rows};dispatch=collections.defaultdict(list);lock_to_commit=[];main=[];statements=set()
    events=[];grouped=collections.defaultdict(list)
    with (p/'server-events.tsv').open(encoding='utf8') as f:
        for e in csv.DictReader(f,delimiter='\t'):
            if e['event']=='request' and (e['reservationId'] in by_rid or (e['kind']=='http' and e['clientKey'] in by_key)):events.append(e)
    wanted={(e['traceId'],e.get('threadId','')) for e in events}
    with (p/'server-events.tsv').open(encoding='utf8') as f:
        for e in csv.DictReader(f,delimiter='\t'):
            key=(e['traceId'],e.get('threadId',''))
            if e['event']=='phase' and key in wanted:grouped[key].append(e)
    http_segments=collections.defaultdict(lambda:collections.defaultdict(list));http_phases=collections.defaultdict(list)
    for e in events:
        if e['event']!='request':continue
        a,b=int(e['startMs']),int(e['endMs']);pp=[t for t in grouped[(e['traceId'],e.get('threadId',''))] if a<=int(t['startMs'])<=int(t['endMs'])<=b]
        row=by_key.get(e['clientKey'])
        if e['kind']=='http' and row and int(row['timeStamp'])-5<=a<=int(row['timeStamp'])+int(row['elapsed'])+5:
            first=int(row['timeStamp']);last=first+int(row['elapsed']);group=row['responseCode']
            http_segments[group]['samplerStartToAppEnterMs'].append(a-first)
            http_segments[group]['appEnterToAppExitMs'].append(b-a)
            http_segments[group]['appExitToHttpSamplerEndMs'].append(last-b)
            for t in pp:http_phases[t['phase']].append(int(t['endMs'])-int(t['startMs']))
        if e['reservationId'] not in by_rid:continue
        if e['kind']=='dispatch':
            for t in pp:
                dispatch[t['phase']].append(int(t['endMs'])-int(t['startMs']))
                if t['phase'].endswith('.sent'):statements.add(t['phase'])
        if e['kind']=='settle':
            at=by_rid[e['reservationId']]['commitReturnedAtMs']
            if not a<=at<=b:continue
            main.append(at-a)
            # Campaign X lock starts at lockCampaign (<=V25 settle) or at the final conditional stock UPDATE (V26 settle).
            locks=sorted([t for t in pp if t['phase'].endswith(('.lockCampaign','.tryIncrementSold')) and int(t['endMs'])<=at],key=lambda t:int(t['startMs']))
            if locks:lock_to_commit.append(at-int(locks[0]['endMs']))
    obs=[json.loads(l) for l in (p/'observations.ndjson').read_text().splitlines()]
    chosen=[s for s in obs if s['endEpochMs']>=w['startEpochMs']]
    peak={};unavailable=collections.Counter();lock_samples=collections.Counter();first_empty=None;processing_peak=0;stream_lag_peak=0;stream_pending_peak=0;dispatch_pending_peak=0
    for s in chosen:
        for name,metric in s['http'].items():
            if 'unavailable' in metric:unavailable[name]+=1;continue
            if 'VALUE' in metric:peak[name]=max(peak.get(name,0),metric['VALUE'])
        db=s['database'];rr=s['redis']
        if 'unavailable' not in db and db.get('campaign'):
            c=list(map(int,db['campaign'][0]));processing_peak=max(processing_peak,c[1])
            d=list(map(int,db['dispatch'][0]));dispatch_pending_peak=max(dispatch_pending_peak,d[1]+d[2])
            for table,n in db.get('lockWaitsByTable',[]):lock_samples[table]+=int(n)
            drained=(c[0]==summary['groups']['accepted']['count'] and c[1]==0 and c[2]==summary['paidOrders'] and d[1]+d[2]==0
                     and not int(db['cacheJobs'][0][0]) and rr.get('ready')=='1' and rr.get('globalStreamPending')==0 and rr.get('globalStreamLag')==0)
            if drained and s['startEpochMs']>=last_http and first_empty is None:first_empty={'startMs':s['startEpochMs'],'endMs':s['endEpochMs']}
        if isinstance(rr.get('globalStreamLag'),int):stream_lag_peak=max(stream_lag_peak,rr['globalStreamLag'])
        if isinstance(rr.get('globalStreamPending'),int):stream_pending_peak=max(stream_pending_peak,rr['globalStreamPending'])
    polls=json.loads((p/'client-result-polls.json').read_text());intervals=[]
    for r in polls['results']:intervals.extend(b-a for a,b in zip(r.get('attemptTimesMs',[]),r.get('attemptTimesMs',[])[1:]))
    final=(p/'reconciliation.json');ledger=json.loads(final.read_text())
    edges={}
    for name in ['start','end']:
        f=p/('backlog-window-'+name+'.json')
        if f.exists():
            edge=json.loads(f.read_text());boundary=w['startEpochMs' if name=='start' else 'endEpochMs']
            edges[name]={**edge,'sampleStartOffsetFromBoundaryMs':edge['startEpochMs']-boundary,'sampleEndOffsetFromBoundaryMs':edge['endEpochMs']-boundary}
    result={'rollingOneSecondMaxStarts':rolling,'perSecond':[{'epochSecond':s,**dict(c)} for s,c in sorted(seconds.items())],
      'httpSegmentsByCodeMs':{k:{n:dist(v) for n,v in values.items()} for k,values in http_segments.items()},
      'httpPhaseMs':{k:dist(v) for k,v in http_phases.items()},
      'observedHttpWindowMs':last_http-min(starts),'plannedWindowFirstSamplerOffsetMs':min(starts)-w['startEpochMs'],
      'dispatchPhasesMs':{k:dist(v) for k,v in dispatch.items()},'ackConfirmationSqlStatements':sorted(statements),
      'settlementRootStartToMainCommitReturnedMs':dist(main),'campaignLockReturnToMainCommitReturnMs':dist(lock_to_commit),
      'lockDurationCaveat':'Return-to-commit is a lower bound on lock holding; measured from the first campaign-row locking statement (lockCampaign before V26, the final tryIncrementSold from V26), whose own duration includes query/RTT and possible lock wait.',
      'resourcePeaks':peak,'unavailableMetricSampleCounts':dict(unavailable),'sampledLockWaitTableObservations':dict(lock_samples),
      'processingPeak':processing_peak,'streamLagPeakGlobal':stream_lag_peak,'streamPendingPeakGlobal':stream_pending_peak,'dispatchPendingPeak':dispatch_pending_peak,
      'firstObservedFullyDrainedAfterLastHttp':first_empty,
      'drainAfterLastHttpSecUpperBound':(first_empty['endMs']-last_http)/1000 if first_empty else summary['drainConfirmedAfterLastHttpSec'],
      'windowEdges':edges,'windowPaidAtRecordOrders':sum(w['startEpochMs']<=int(r[6])<w['endEpochMs'] for r in ledger['requests'] if r[5]=='PAID'),
      'clientPollCalls':polls['calls'],'clientPollSeconds':(polls['endEpochMs']-polls['startEpochMs'])/1000,
      'clientPollAverageQps':polls['calls']/((polls['endEpochMs']-polls['startEpochMs'])/1000),
      'clientPollObservedSucceeded':sum(r['state']=='SUCCEEDED' for r in polls['results']),'clientPollAcceptedTotal':len(polls['results']),
      'clientPollIntervalsMs':dist(intervals),'clientReservationsWithPollError':sum(bool(r['error']) for r in polls['results']),
      'hostMinimumAvailableCommitBytes':min(s['hostMemory'].get('availableCommitBytes',float('inf')) for s in obs),
      'maxObserverStartGapMs':max(b['startEpochMs']-a['startEpochMs'] for a,b in zip(obs,obs[1:]))}
    (p/'supplemental-analysis.json').write_text(json.dumps(result,indent=2),encoding='utf8')
    print(json.dumps({k:result[k] for k in ['rollingOneSecondMaxStarts','processingPeak','dispatchPendingPeak','drainAfterLastHttpSecUpperBound','clientPollCalls','maxObserverStartGapMs']},indent=2))
    return result
if __name__=='__main__':analyze(sys.argv[1])
