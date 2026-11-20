"""Limited-stock synchronous sale acceptance; no tokens or response bodies are archived."""
import argparse,collections,csv,json,time,subprocess
from pathlib import Path
from purchase_capacity import mysql,percentile

def redis_snapshot(campaign,requests):
    # One bounded call over this run's durable reservations; no KEYS/SCAN and no secrets.
    lua="""local ids={};for id in string.gmatch(ARGV[1],'[^,]+') do ids[#ids+1]=id end
local v=redis.call('TYPE','sale:{entry-v2}:control').ok
if v=='hash' then
 local key='sale:{entry-v2}:state'; local processing=0;local bad=0
 for i=1,#ids do
  local value=redis.call('HGET',key,'r:'..ids[i])
  if not value then bad=bad+1 else local r=cjson.decode(value);if r.state=='PROCESSING' then processing=processing+1 elseif r.state~='SUCCEEDED' then bad=bad+1 end end
 end
 local pending=redis.call('XPENDING','sale:{entry-v2}:stream','sale-persist-v2')[1]
 local groups=redis.call('XINFO','GROUPS','sale:{entry-v2}:stream');local lag=nil
 for _,g in ipairs(groups) do local name=nil;local l=nil;for i=1,#g,2 do if g[i]=='name' then name=g[i+1] elseif g[i]=='lag' then l=g[i+1] end end;if name=='sale-persist-v2' then lag=l end end
 return cjson.encode({version=2,stock=tonumber(redis.call('HGET',key,'c:'..KEYS[1]..':stock')),processing=processing,invalidRequests=bad,streamPending=pending,streamLag=lag,ready=redis.call('HGET','sale:{entry-v2}:control','ready')})
end
local p=0;for i=1,#ids do if redis.call('HGET','sale:{'..KEYS[1]..'}:r:'..ids[i],'state')=='PROCESSING' then p=p+1 end end
return cjson.encode({version=1,stock=tonumber(redis.call('GET','sale:{'..KEYS[1]..'}:stock')),processing=p})"""
    # redis-cli -x appends stdin as the last argument. Keep the command line
    # bounded on Windows; the Lua expands only this run's supplied reservations.
    raw=subprocess.run(['docker','exec','-i','content-redis-1','redis-cli','--raw','-x','EVAL',lua,'1',str(campaign)],
                       input=','.join(r[0] for r in requests),capture_output=True,text=True,timeout=10,check=True)
    cache=json.loads(raw.stdout)  # WRONGTYPE, missing group and malformed JSON fail, never zero.
    if cache.get('version')==2:
        rows=mysql(f"SELECT COUNT(*),COALESCE(SUM(d.state<>'SENT'),0) FROM material_sale_dispatch d JOIN material_purchase_requests r USING(reservation_id) WHERE r.campaign_id={int(campaign)};SELECT COUNT(*) FROM material_sale_cache_jobs WHERE revision>applied_revision;")
        cache['dispatchCount']=int(rows[0][0]);cache['dispatchPending']=int(rows[0][1])
        cache['cacheJobsPending']=int(rows[1][0])
    return cache

def snapshot(c):
    rows=mysql(f'''START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SELECT stock_cap,sold_count,stock_cap-sold_count FROM material_sale_campaigns WHERE id={c};
SELECT r.reservation_id,r.user_id,r.client_key,r.state,COALESCE(r.order_id,0),COALESCE(o.state,''),
COALESCE(o.paid_at_ms,0),
(SELECT COUNT(*) FROM material_entitlements e WHERE e.source_kind='PURCHASE' AND e.source_id=o.id AND e.account_id=r.user_id AND e.post_id=r.post_id AND e.state='ACTIVE'),COALESCE(cr.decision,'')
FROM material_purchase_requests r LEFT JOIN material_orders o ON o.id=r.order_id AND o.reservation_id=r.reservation_id AND o.campaign_id=r.campaign_id AND o.user_id=r.user_id AND o.post_id=r.post_id AND o.merchant_id=r.merchant_id AND o.price_cents=r.price_cents
LEFT JOIN material_sale_consume_receipts cr ON cr.reservation_id=r.reservation_id WHERE r.campaign_id={c};
SELECT COUNT(*),COUNT(*)-COUNT(DISTINCT account_id) FROM material_entitlements WHERE source_kind='PURCHASE' AND state='ACTIVE' AND post_id=(SELECT post_id FROM material_sale_campaigns WHERE id={c});
SELECT COUNT(*),COALESCE(SUM(state='PAID'),0),COALESCE(SUM(state='HELD'),0),COUNT(*)-COUNT(DISTINCT user_id) FROM material_orders WHERE campaign_id={c}; COMMIT;''')
    return {'stockCap':int(rows[0][0]),'sold':int(rows[0][1]),'remaining':int(rows[0][2]),'requests':rows[1:-2],'entitlements':list(map(int,rows[-2])),'orders':list(map(int,rows[-1]))}

def report(directory,meta,threads,drain_timeout=120,observations=None,runner_exit=0):
    d=Path(directory);m=json.loads(Path(meta).read_text(encoding='utf-8-sig'))
    with (d/'results.jtl').open(encoding='utf-8-sig') as f: rows=list(csv.DictReader(f))
    deadline=time.monotonic()+drain_timeout;history=[]
    while True:
        s=snapshot(int(m['campaignId']));pending=sum(r[3]=='PROCESSING' for r in s['requests'])+s['orders'][2]
        cache=redis_snapshot(int(m['campaignId']),s['requests'])
        bridge_pending=cache.get('processing',0)+cache.get('streamPending',0)+(cache.get('streamLag') or 0)+cache.get('dispatchPending',0)+cache.get('cacheJobsPending',0)
        history.append({'atMs':int(time.time()*1000),'processing':pending,'paid':s['orders'][1],'bridgePending':bridge_pending})
        if (pending==0 and bridge_pending==0) or time.monotonic()>=deadline:break
        time.sleep(1)
    (d/'drain-samples.json').write_text(json.dumps(history,indent=2))
    (d/'reconciliation.json').write_text(json.dumps(s,indent=2))
    (d/'redis-reconciliation.json').write_text(json.dumps(cache,indent=2))
    barrier=json.loads((d/'barrier-stats.json').read_text(encoding='utf-8-sig')) if (d/'barrier-stats.json').exists() else {}
    starts=[int(r['timeStamp']) for r in rows];ends=[int(r['timeStamp'])+int(r['elapsed']) for r in rows]
    start=min(starts);end=max(ends);span=max(starts)-start;elapsed=(end-start)/1000
    statuses=collections.Counter(r['responseCode'] for r in rows)
    traffic=collections.defaultdict(lambda:collections.Counter())
    for row,started,finished in zip(rows,starts,ends):
      traffic[started//1000]['requestStarts']+=1
      if row['responseCode'].isdigit():traffic[finished//1000]['httpResponses']+=1
      if 'ConnectException' in row['responseCode'] or 'HttpHostConnectException' in row['responseCode']:traffic[finished//1000]['connectFailures']+=1
      if 'Timeout' in row['responseCode'] or 'timed out' in row['responseMessage'].lower():traffic[finished//1000]['timeouts']+=1
    (d/'traffic-by-second.json').write_text(json.dumps([{'epochSecond':sec,**dict(values)} for sec,values in sorted(traffic.items())],indent=2))

    rejects=collections.Counter(r.get('businessCode','') or 'UNCLASSIFIED' for r in rows if r['responseCode'].isdigit() and not 200<=int(r['responseCode'])<300)
    business_rejects=collections.Counter(r.get('businessCode','') for r in rows if r['responseCode']=='409' and r.get('businessCode') in ('SOLD_OUT','BLACKLISTED','GIVEAWAY_WINNER','NOT_STARTED','ENDED','CAMPAIGN_CLOSED'))
    def connect_timeout(r):return 'ConnectTimeoutException' in r['responseCode'] or 'connect timed out' in r['responseMessage'].lower()
    connect_timeouts=sum(connect_timeout(r) for r in rows)
    connects=sum(connect_timeout(r) or any(t in r['responseCode'] for t in ('HttpHostConnectException','ConnectException','BindException','NoRouteToHostException','UnknownHostException')) or 'connection refused' in r['responseMessage'].lower() for r in rows)
    timeouts=sum('Timeout' in r['responseCode'] or 'timed out' in r['responseMessage'].lower() for r in rows)
    paid=s['orders'][1];ent=s.get('entitlements',[sum(int(r[7]) for r in s['requests']),0])[0];dupent=max(s.get('entitlements',[0,0])[1],sum(max(0,int(r[7])-1) for r in s['requests']));dupo=s['orders'][3]
    with open(m['buyersCsv'],encoding='utf-8-sig') as f: allowed={str(r['userId']):r['clientKey'] for r in csv.DictReader(f)}
    ids=[r.get('userId','') for r in rows];accepted=[r for r in rows if r['responseCode']=='200']
    durable={r[0]:r for r in s['requests']};bad=[]
    for r in accepted:
        v=durable.get(r.get('reservationId',''))
        if not v or v[1]!=r.get('userId') or v[2]!=r.get('effClientKey') or v[3]!='SUCCEEDED' or v[5]!='PAID' or v[7]!='1' or v[8]!='SUCCEEDED':bad.append(r.get('reservationId','MISSING'))
    failures=[]
    checks={
      'synchronized request start span exceeds 5000ms':span<=5000,
      'runner did not complete successfully':runner_exit==0,
      'barrier not fully ready':barrier.get('readyCount')==threads and barrier.get('configuredGroupSize')==threads and barrier.get('timedOut') is False,
      'sample or unique buyer count mismatch':len(rows)==threads and len(set(ids))==threads and all(u in allowed for u in ids),
      'client key mismatch':all(allowed.get(r.get('userId'))==r.get('effClientKey') for r in rows),
      'connection failure':connects==0,'request timeout':timeouts==0,
      'unexpected HTTP or business rejection':all(r['responseCode']=='200' or (r['responseCode']=='409' and r.get('businessCode')=='SOLD_OUT') for r in rows),
      'accepted request reconciliation mismatch':not bad and len(accepted)==len(durable)==paid,
      'inventory/order/entitlement invariant failed':paid==s['stockCap']==s['sold']==ent and s['remaining']==0 and s['orders'][0]==paid and not dupo and not dupent,
      'unsettled requests or held orders':pending==0,
      'Redis stock or processing mismatch':cache.get('stock')==s['remaining'] and cache.get('processing')==0,
    }
    if cache.get('version')==2:
      checks['Stream bridge or MQ dispatch not drained']=cache.get('streamPending')==0 and cache.get('streamLag')==0 and cache.get('dispatchPending')==0 and cache.get('dispatchCount')==len(durable) and cache.get('invalidRequests')==0 and cache.get('ready')=='1' and cache.get('cacheJobsPending')==0
    bootstrap=None
    window=json.loads((d/'window.json').read_text(encoding='utf-8-sig')) if (d/'window.json').exists() else {}
    if window.get('jmeter',{}).get('transportBootstrapEnabled',False):
      bootstrap=json.loads((d/'transport-bootstrap.json').read_text(encoding='utf8')) if (d/'transport-bootstrap.json').exists() else {}
      checks['transport bootstrap missing or invalid']=(bootstrap.get('passed') is True and bootstrap.get('actualStatus')=='401' and bootstrap.get('credentialsSent') is False and bootstrap.get('formalSample') is False and bootstrap.get('completedAtMs',end)>=0 and bootstrap.get('completedAtMs',end)<start)
    for reason,ok in checks.items():
        if not ok:failures.append(reason)
    paid_times=[int(r[6]) for r in s['requests'] if r[5]=='PAID'];lastpaid=max(paid_times,default=end)
    confirmed=history[-1]['atMs']
    confirmation_source='final committed reconciliation and Redis snapshot (includes report collection delay)'
    if observations and Path(observations).exists():
      for line in Path(observations).read_text(encoding='utf8').splitlines():
        try:
          tick=json.loads(line); db=tick['database']; camp=db.get('campaign',[]); receipts=db.get('receipts',[])
          complete=camp and receipts and int(camp[0][0])==paid and int(camp[0][1])==0 and int(camp[0][2])==paid and int(receipts[0][0])==paid
          if cache.get('version')==2:
            dispatch=db.get('dispatch',[]);jobs=db.get('cacheJobs',[]);redis=tick.get('redis',{})
            # In an isolated fixed-stock run, cap PAID + stock=0 cannot grow
            # again without a refund. Confirmed drain may precede late SOLD_OUT
            # responses. Every queue/cache source must explicitly confirm zero.
            complete=complete and tick.get('campaignId')==int(m['campaignId']) and paid==s['stockCap'] and tick['startEpochMs']>=start and dispatch and list(map(int,dispatch[0][:4]))==[paid,0,0,paid] and jobs and int(jobs[0][0])==0 and redis.get('campaignStock')==0 and redis.get('ready')=='1' and redis.get('globalStreamPending')==0 and redis.get('globalStreamLag')==0
          else:complete=complete and tick['startEpochMs']>=end
          if complete and tick['endEpochMs']<confirmed:
            confirmed=tick['endEpochMs'];confirmation_source='run-scoped observer confirming committed requests, receipts, dispatch, Stream and cache jobs drained'
        except (ValueError,KeyError,IndexError,TypeError):pass
    drain=max(0,(confirmed-end)/1000)
    # Retain the observation window, but never relax the 30-second acceptance gate.
    if drain>30:failures.append('drain exceeded 30 seconds after last HTTP completion')
    out={'configuredThreads':threads,'campaignId':m['campaignId'],'postId':m['postId'],'readyCount':barrier.get('readyCount'),'barrierTimedOut':barrier.get('timedOut'),'samples':len(rows),'uniqueUsers':len(set(ids)),
      'requestStartSpanMs':span,'syncStartSpanLimitMs':5000,'synchronyValid':span<=5000,'firstRequestStartMs':start,'lastHttpCompletionMs':end,'barrierReleaseEpochMs':barrier.get('releaseEpochMs'),'requestQps':len(rows)/(span/1000) if span else None,'httpCompletionQps':sum(v for k,v in statuses.items() if k.isdigit())/elapsed,'sampleCompletionQps':len(rows)/elapsed,'http200Qps':len(accepted)/elapsed,
      'qpsDefinitions':{'requestQps':'all JTL sample starts / (last start - first start); HTTP sampler start, not NIC wire timestamp','httpCompletionQps':'HTTP responses only / (last sample completion - first start); excludes connection errors','http200Qps':'HTTP 200 / (last completion - first start)','finalPaidTps':'unique PAID orders / (last recorded paid_at - first HTTP start); paid_at is transaction-record time, not commit timestamp','fullRunPaidTps':'unique PAID / (confirmed drain observation - first HTTP start)'},
      'httpStatusCounts':dict(statuses),'http401':statuses.get('401',0),'http5xx':sum(v for k,v in statuses.items() if k.isdigit() and 500<=int(k)<600),'businessRejects':dict(business_rejects),'httpErrorCodes':dict(rejects),'connectFailures':connects,'connectTimeouts':connect_timeouts,'nonTimeoutConnectFailures':connects-connect_timeouts,'timeouts':timeouts,
      'p50Ms':percentile([int(r['elapsed']) for r in rows],.50),'p95Ms':percentile([int(r['elapsed']) for r in rows],.95),'p99Ms':percentile([int(r['elapsed']) for r in rows],.99),
      'paidOrders':paid,'remainingStock':s['remaining'],'activeEntitlements':ent,'duplicateOrders':dupo,'duplicateEntitlements':dupent,'processingLeft':pending,'redisRemainingStock':cache.get('stock'),'redisProcessingLeft':cache.get('processing'),'drainAfterLastHttpSec':drain,
      'finalPaidTps':paid/max(.001,(lastpaid-start)/1000),'fullRunPaidTps':paid/max(.001,(confirmed-start)/1000),'observedLastPaidAfterHttpSec':max(0,(lastpaid-end)/1000),
      'drainConfirmedAtMs':confirmed,'drainMeasurement':confirmation_source,'finalSnapshotAtMs':history[-1]['atMs'],'reportCollectionAfterLastHttpSec':max(0,(history[-1]['atMs']-end)/1000),
      'pipeline':cache,'transportBootstrap':bootstrap,'pass':not failures,'failureReasons':failures}
    (d/'acceptance-report.json').write_text(json.dumps(out,indent=2),encoding='utf8');print(json.dumps(out));return 0 if out['pass'] else 1

def failure_report(directory,meta,threads,error):
    d=Path(directory);d.mkdir(parents=True,exist_ok=True)
    fields=['readyCount','barrierTimedOut','samples','uniqueUsers','requestStartSpanMs','requestQps','connectFailures','timeouts','p50Ms','p95Ms','p99Ms','paidOrders','remainingStock','activeEntitlements','duplicateOrders','duplicateEntitlements','processingLeft','drainAfterLastHttpSec']
    out=dict.fromkeys(fields);out.update(configuredThreads=threads,httpStatusCounts={},businessRejects={},pass_=False,failureReasons=['Incomplete evidence: '+type(error).__name__+'; inspect JMeter/runner logs, do not interpret unknown counts as zero'])
    out['pass']=out.pop('pass_')
    try:
      b=json.loads((d/'barrier-stats.json').read_text(encoding='utf-8-sig'));out['readyCount']=b.get('readyCount');out['barrierTimedOut']=b.get('timedOut')
    except (OSError,ValueError):pass
    try:
      m=json.loads(Path(meta).read_text(encoding='utf-8-sig'));state=snapshot(int(m['campaignId']))
      out.update(campaignId=m['campaignId'],paidOrders=state['orders'][1],remainingStock=state['remaining'],activeEntitlements=state['entitlements'][0],duplicateOrders=state['orders'][3],duplicateEntitlements=sum(max(0,int(r[7])-1) for r in state['requests']),processingLeft=sum(r[3]=='PROCESSING' for r in state['requests'])+state['orders'][2])
    except Exception:pass
    with (d/'acceptance-report.json').open('x',encoding='utf8') as f:json.dump(out,f,indent=2)
    print(json.dumps(out));return 1

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory');p.add_argument('meta');p.add_argument('--threads',type=int,required=True);p.add_argument('--observations');p.add_argument('--runner-exit',type=int,default=0);a=p.parse_args()
    try:code=report(a.directory,a.meta,a.threads,observations=a.observations,runner_exit=a.runner_exit)
    except Exception as error:code=failure_report(a.directory,a.meta,a.threads,error)
    raise SystemExit(code)
