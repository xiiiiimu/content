"""Local V23 A/B evidence runner. Credentials remain in target, never in evidence."""
import argparse,collections,concurrent.futures,csv,hashlib,json,math,os,shutil,subprocess,threading,time
from pathlib import Path
from urllib.request import Request,build_opener,ProxyHandler
import purchase_capacity as pc
from flash_acceptance import snapshot,redis_snapshot
from v23_plan import generate

JAVA=Path('C:/Users/西木/.jdks/ms-17.0.17/bin')
API='http://127.0.0.1:28080'
def now():return time.time_ns()//1_000_000
def write(path,value):path.write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf8')
def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def request(path,token,body=None):
    req=Request(API+path,data=json.dumps(body).encode() if body is not None else None,
                headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    with build_opener(ProxyHandler({})).open(req,timeout=5) as r:return json.load(r)
def distribution(values):
    s=sorted(values)
    return {'count':len(s),**{k:(s[math.ceil(len(s)*p)-1] if s else None) for k,p in [('p50',.5),('p95',.95),('p99',.99),('max',1)]}}
def jcmd(*args):
    p=subprocess.run([str(JAVA/'jcmd.exe'),'24820',*args],capture_output=True,text=True,timeout=15)
    if p.returncode:raise RuntimeError('jcmd failed: '+p.stderr)
    return p.stdout
def backlog(campaign):
    return {'startEpochMs':now(),'database':pc.database_sample(campaign),'redis':pc.stream_sample(campaign),'endEpochMs':now()}

def events_report(tsv,formal,ledger,window):
    with tsv.open(encoding='utf8') as f:events=list(csv.DictReader(f,delimiter='\t'))
    phases=collections.defaultdict(list);roots=[]
    for e in events:
        if e['event']=='phase':phases[(e['traceId'],e.get('threadId',''))].append(e)
        elif e['event']=='request':roots.append(e)
    by_rid={r[0]:r for r in ledger['requests']}
    posts={r['reservationId']:r for r in formal if r['responseCode']=='200'}
    by_key={r['effClientKey']:r for r in formal}
    commits=[];phase_times=collections.defaultdict(list);http=[];missing=[]
    for root in roots:
        a,b=int(root['startMs']),int(root['endMs'])
        pp=[p for p in phases[(root['traceId'],root.get('threadId',''))] if a<=int(p['startMs'])<=int(p['endMs'])<=b]
        post=by_key.get(root['clientKey'])
        if root['kind']=='http' and post and int(post['timeStamp'])-5<=a<=int(post['timeStamp'])+int(post['elapsed'])+5:
            http.append({'clientKey':root['clientKey'],'reservationId':root['reservationId'],'startMs':a,'endMs':b,'durationMs':b-a,
                         'sqlCount':sum(p['phase'].startswith('sql.') for p in pp)})
        if root['kind']=='settle' and root['reservationId'] in posts:
            for p in pp:phase_times[p['phase']].append(int(p['endMs'])-int(p['startMs']))
            # A duplicate/retry attempt is a separate root interval. Require the order
            # insert AND terminal request update, no rollback, then first commit.
            if not any(p['phase'].endswith('.insertOrder') for p in pp):continue
            finish=[p for p in pp if p['phase'].endswith('.finishRequest')]
            if not finish or any(p['phase']=='jdbc.rollback' for p in pp):continue
            end_update=max(int(p['endMs']) for p in finish)
            cc=[p for p in pp if p['phase']=='jdbc.commit' and int(p['startMs'])>=end_update]
            if not cc:continue
            commit=min(cc,key=lambda p:int(p['startMs']));rid=root['reservationId'];db=by_rid.get(rid)
            if not db or db[3]!='SUCCEEDED' or db[5]!='PAID':continue
            post=posts[rid];at=int(commit['endMs'])
            commits.append({'reservationId':rid,'clientKey':post['effClientKey'],'orderId':db[4],
                            'commitReturnedAtMs':at,'paidAtRecordMs':int(db[6]),
                            'httpStartToCommitReturnedMs':at-int(post['timeStamp']),
                            'settleStartMs':a,'settleEndMs':b})
    counts=collections.Counter(c['reservationId'] for c in commits)
    for rid in posts:
        if counts[rid]!=1:missing.append({'reservationId':rid,'commitEvidenceCount':counts[rid]})
    valid=[c for c in commits if counts[c['reservationId']]==1]
    seconds=(window['endEpochMs']-window['startEpochMs'])/1000
    count=sum(window['startEpochMs']<=c['commitReturnedAtMs']<window['endEpochMs'] for c in valid)
    loss=sum(e['event']=='DATA_LOSS' for e in events)
    complete=not missing and not loss and len(valid)==ledger['orders'][1]
    gc=[e for e in events if e['event']=='gc' and window['startEpochMs']<=int(e['startMs'])<window['endEpochMs']]
    return {'commitEvidenceComplete':complete,'dataLossEvents':loss,'missingOrAmbiguous':missing,'uniqueOrderCommits':valid,
            'windowCommittedOrders':count if complete else None,'windowCommittedTps':count/seconds if complete else None,
            'fullCommittedTps':len(valid)/((max(c['commitReturnedAtMs'] for c in valid)-min(int(r['timeStamp']) for r in formal))/1000) if complete and valid else None,
            'httpStartToServerCommitReturnedMs':distribution([c['httpStartToCommitReturnedMs'] for c in valid]),
            'httpApplicationMs':distribution([r['durationMs'] for r in http]),'httpSqlCounts':dict(collections.Counter(r['sqlCount'] for r in http)),
            'matchedHttpCount':len(http),'serverGcPauseMs':distribution([int(e['endMs'])-int(e['startMs']) for e in gc]),'settlementPhasesMs':{k:distribution(v) for k,v in phase_times.items()},
            'http':http,'note':'JDBC commit RETURN boundary, not physical disk instant. paidAtRecordMs is separate. Post-transaction cache commits excluded.'}

def run(args):
    root=Path.cwd();meta=read(args.meta);warm=read(args.warm)
    out=Path(args.out);out.mkdir(parents=True,exist_ok=False)
    private=Path(args.private);private.mkdir(parents=True,exist_ok=False)
    generate(out/'plan.jmx')
    for f in ['v23-prepare.groovy','v23-response.groovy','v23_plan.py','run_v23_performance.py','V23Events.java']:
        shutil.copy2(Path('infra/jmeter')/f,out/f)
    for label,m in [('formal',meta),('warm',warm)]:
        p=subprocess.run(['pwsh','-NoProfile','-File','infra/jmeter/preflight-commerce.ps1','-MetaJson',str(Path(args.meta if label=='formal' else args.warm).resolve()),'-MinBuyers',str(args.count if label=='formal' else args.workers)],capture_output=True,text=True)
        (out/(label+'-preflight.log')).write_text(p.stdout+'\n'+p.stderr,encoding='utf8')
        if p.returncode:raise RuntimeError(label+' preflight failed; no JMeter launched')
    buyers=list(csv.DictReader(open(meta['buyersCsv'],encoding='utf-8-sig')))
    warmbuyers=list(csv.DictReader(open(warm['buyersCsv'],encoding='utf-8-sig')))
    assert len(buyers)>=args.count and len(warmbuyers)>=args.workers
    assert len({r['userId'] for r in buyers[:args.count]})==args.count
    assert not {r['userId'] for r in buyers[:args.count]} & {r['userId'] for r in warmbuyers[:args.workers]}
    assert meta['campaignId']!=warm['campaignId']
    config={'runId':out.name,'campaignId':meta['campaignId'],'postId':meta['postId'],'warmCampaignId':warm['campaignId'],
            'count':args.count,'stock':meta['stock'],'rate':args.rate,'workers':args.workers,'windowSec':args.window,
            'timeoutMs':30000,'loopsPerBuyer':1,'sameJvmWarmup':True,'heap':'128m/384m','clientJfr':False,'clientThreadDump':False,
            'sourceRevision':'V23','jarSha256':'376b7ae3cf209dec1f36993c81d27685ab6de3014a687fdeee642d521c54efa5',
            'warmupSettlementPoll':'Each worker, 1 second interval, 60 second bound, outside formal; same HTTP POST sampler/thread',
            'finalResultPolling':'After formal HTTP only; concurrency16, 1s retry per request, global120s deadline; observation latency includes delayed polling start',
            'observerIntervalSec':2,'serverJfr':'Only custom sale events + GC pause + DataLoss, no stacks/environment/JVM properties',
            'hostMemoryBefore':pc.windows_memory()}
    write(out/'config.json',config)
    if config['hostMemoryBefore'].get('availableCommitBytes',0)<800_000_000:raise RuntimeError('Insufficient host commit headroom for bounded JMeter JVM')
    jfc=private/'scoped.jfc'
    jfc.write_text('<configuration version="2.0" label="V23 scoped">'+''.join('<event name="'+n+'"><setting name="enabled">true</setting><setting name="stackTrace">false</setting><setting name="threshold">0 ms</setting></event>' for n in ['content.SaleRequest','content.SalePhase','jdk.DataLoss','jdk.GCPhasePause'])+'</configuration>',encoding='utf8')
    shutil.copy2(jfc,out/'server-scoped.jfc')
    write(out/'backlog-before-warmup.json',backlog(meta['campaignId']))
    watch=threading.Thread(target=pc.observe,args=(out,meta['campaignId'],2),daemon=True);watch.start()
    name='v23'+str(now());jfr=private/'server.jfr'
    (out/'jfr-start.log').write_text(jcmd('JFR.start','name='+name,'settings='+str(jfc.resolve()),'disk=true','maxsize=256m'),encoding='utf8')
    command=[str(JAVA/'java.exe'),'-Xms128m','-Xmx384m','-XX:MaxMetaspaceSize=256m',
             '-Xlog:gc*:file='+str((out/'injector-gc.log').resolve().relative_to(root)).replace('\\','/')+':time,uptime,level,tags',
             '-jar',str(Path('tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar').resolve()),'-n','-t',str((out/'plan.jmx').resolve()),'-l',str((out/'results.jtl').resolve()),'-j',str((out/'jmeter.log').resolve())]
    properties={'workers':args.workers,'rate':args.rate,'count':args.count,'windowSec':args.window,
        'warmCsv':str(Path(warm['buyersCsv']).resolve()),'formalCsv':str(Path(meta['buyersCsv']).resolve()),
        'warmCampaign':warm['campaignId'],'formalCampaign':meta['campaignId'],'windowPath':str((out/'window.json').resolve()),
        'sample_variables':'userId,effClientKey,reservationId,businessCode,permitEpochMs,warmPollCalls',
        'jmeter.save.saveservice.output_format':'csv','jmeter.save.saveservice.print_field_names':'true',
        'jmeter.save.saveservice.response_data':'false','jmeter.save.saveservice.samplerData':'false',
        'jmeter.save.saveservice.requestHeaders':'false','jmeter.save.saveservice.responseHeaders':'false',
        'jmeter.save.saveservice.connect_time':'true','jmeter.save.saveservice.timestamp_format':'ms',
        'jmeter.save.saveservice.response_message':'true','summariser.name':'summary'}
    command+=['-J'+k+'='+str(v) for k,v in properties.items()]
    write(out/'command.json',command) # Paths and public IDs only, no tokens.
    try:
        with (out/'console.log').open('x',encoding='utf8') as console:
            process=subprocess.Popen(command,stdout=console,stderr=subprocess.STDOUT)
            print('START',out,process.pid,flush=True)
            # Main-window edge snapshots are timestamped brackets, not claimed instantaneous.
            edge_start=False;edge_end=False
            while process.poll() is None:
                if (out/'window.json').exists():
                    w=read(out/'window.json')
                    if not edge_start and now()>=w['startEpochMs']:
                        write(out/'backlog-window-start.json',backlog(meta['campaignId']));edge_start=True
                    if w['endEpochMs'] and not edge_end and now()>=w['endEpochMs']:
                        write(out/'backlog-window-end.json',backlog(meta['campaignId']));edge_end=True
                time.sleep(.05)
        write(out/'runner-exit.json',{'exitCode':process.returncode,'atMs':now()})
        rows=list(csv.DictReader((out/'results.jtl').open(encoding='utf-8-sig')))
        formal=[r for r in rows if r['label']=='POST-purchase'];warmrows=[r for r in rows if r['label']=='WARMUP-purchase']
        if process.returncode or not formal or (not args.window and len(formal)!=args.count):raise RuntimeError('Incomplete JMeter formal run; inspect logs, no automatic retry')
        window=read(out/'window.json')
        if not window['endEpochMs']:window['endEpochMs']=max(int(r['timeStamp'])+int(r['elapsed']) for r in formal)+1
        write(out/'window.json',window)
        if not edge_end:write(out/'backlog-window-end.json',backlog(meta['campaignId']))
        # Bounded polling after load. Tokens used only in memory, never persisted here.
        by_user={r['userId']:r for r in buyers};accepted=[r for r in formal if r['responseCode']=='200']
        poll_start=now();deadline=time.monotonic()+120;poll_results=[];poll_errors=collections.Counter()
        def poll(row):
            calls=0;last=None;error=None;started=now()
            while time.monotonic()<deadline:
                try:
                    calls+=1;last=request('/api/purchase-requests/'+row['reservationId'],by_user[row['userId']]['token'])
                    if last.get('state') in ('SUCCEEDED','REJECTED'):break
                except Exception as exc:error=type(exc).__name__
                time.sleep(1)
            end=now()
            return {'reservationId':row['reservationId'],'clientKey':row['effClientKey'],'calls':calls,'firstPollAtMs':started,
                    'observedAtMs':end,'state':last.get('state') if last else None,'error':error,
                    'clientObservedSucceededMs':end-int(row['timeStamp']) if last and last.get('state')=='SUCCEEDED' else None}
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:poll_results=list(pool.map(poll,accepted))
        write(out/'client-result-polls.json',{'startEpochMs':poll_start,'endEpochMs':now(),'calls':sum(r['calls'] for r in poll_results),'results':poll_results})
        history=[]
        while True:
            ledger=snapshot(meta['campaignId']);cache=redis_snapshot(meta['campaignId'],ledger['requests'])
            pending=sum(r[3]=='PROCESSING' for r in ledger['requests'])+ledger['orders'][2]+sum(cache.get(k,0) or 0 for k in ['processing','streamPending','streamLag','dispatchPending','cacheJobsPending'])
            history.append({'atMs':now(),'pendingAggregate':pending,'paid':ledger['orders'][1]})
            if pending==0 or time.monotonic()>=deadline:break
            time.sleep(1)
        write(out/'drain-samples.json',history);write(out/'reconciliation.json',ledger);write(out/'redis-reconciliation.json',cache)
        write(out/'backlog-after-drain.json',backlog(meta['campaignId']))
        # A few known successful keys; outside load window, snapshot order counts both sides.
        replays=[];before=ledger['orders'][1]
        for row in accepted[:3]:
            result=request('/api/sale-campaigns/'+str(meta['campaignId'])+'/purchase',by_user[row['userId']]['token'],{'clientKey':row['effClientKey']})
            replays.append({'clientKey':row['effClientKey'],'originalReservationId':row['reservationId'],'returnedReservationId':result.get('reservationId'),'returnedState':result.get('state')})
        after=snapshot(meta['campaignId'])['orders'][1]
        write(out/'replays.json',{'beforePaid':before,'afterPaid':after,'requests':replays,'pass':before==after and all(r['originalReservationId']==r['returnedReservationId'] for r in replays)})
    finally:
        (out/'jfr-stop.log').write_text(jcmd('JFR.stop','name='+name,'filename='+str(jfr.resolve())),encoding='utf8')
        (out/'observer.stop').write_text('stop');watch.join(timeout=20)
    export=subprocess.run([str(JAVA/'java.exe'),'infra/jmeter/V23Events.java',str(jfr),str(out/'server-events.tsv')],capture_output=True,text=True,timeout=90)
    (out/'jfr-export.log').write_text(export.stdout+export.stderr,encoding='utf8')
    if export.returncode:raise RuntimeError('JFR whitelist export failed')
    report_events=events_report(out/'server-events.tsv',formal,ledger,window);write(out/'commit-evidence.json',report_events)
    starts=[int(r['timeStamp']) for r in formal];ends=[int(r['timeStamp'])+int(r['elapsed']) for r in formal]
    seconds=(window['endEpochMs']-window['startEpochMs'])/1000
    groups={}
    for group,predicate in [('accepted',lambda r:r['responseCode']=='200'),('soldOut',lambda r:r['responseCode']=='409' and r['businessCode']=='SOLD_OUT')]:
        rr=[r for r in formal if predicate(r)]
        groups[group]={'count':len(rr),'windowCompleted':sum(window['startEpochMs']<=int(r['timeStamp'])+int(r['elapsed'])<window['endEpochMs'] for r in rr),
                       **{k:distribution([int(r[k]) for r in rr]) for k in ['elapsed','Latency','Connect']}}
        groups[group]['completionQps']=groups[group]['windowCompleted']/seconds
    systems=[r for r in formal if r['responseCode']!='200' and not(r['responseCode']=='409' and r['businessCode']=='SOLD_OUT')]
    durable={r[0]:r for r in ledger['requests']}
    mismatches=[]
    for r in accepted:
        d=durable.get(r['reservationId'])
        if not d or d[1]!=r['userId'] or d[2]!=r['effClientKey'] or d[3]!='SUCCEEDED' or d[5]!='PAID' or d[7]!='1' or d[8]!='SUCCEEDED':mismatches.append(r['reservationId'])
    checks={'sampleCount':0<len(formal)<=args.count if args.window else len(formal)==args.count,'uniqueBuyers':len({r['userId'] for r in formal})==len(formal),
            'noSystemErrors':not systems,'noReconciliationMismatch':not mismatches,
            'ledgerInvariant':ledger['orders'][1]==len(accepted)==ledger['sold']==ledger['entitlements'][0] and ledger['remaining']==ledger['stockCap']-ledger['sold'] and ledger['orders'][3]==ledger['entitlements'][1]==0,
            'drained':pending==0,'redisInventory':cache.get('stock')==ledger['remaining'],
            'replays':before==after and all(r['originalReservationId']==r['returnedReservationId'] for r in replays)}
    first={}
    for row in sorted(formal,key=lambda r:int(r['timeStamp'])):first.setdefault(row['threadName'],row)
    write(out/'thread-initialization.json',{'firstFormalHttpMs':distribution([int(r['elapsed']) for r in first.values()]),
          'allFormalHttpMs':distribution([int(r['elapsed']) for r in formal]),
          'firstFormalPermitToSamplerMs':distribution([int(r['timeStamp'])-int(r['permitEpochMs']) for r in first.values()]),
          'allFormalPermitToSamplerMs':distribution([int(r['timeStamp'])-int(r['permitEpochMs']) for r in formal]),
          'warmHttpMs':distribution([int(r['elapsed']) for r in warmrows]),'formalWorkersUsed':len(first),
          'interpretation':'Timing is preserved, not subtracted. Permit-to-sampler includes scheduling and sampler setup; not an isolated initialization measurement.'})
    write(out/'summary.json',{'config':config,'window':window,'windowSeconds':seconds,'samples':len(formal),'warmSamples':len(warmrows),
        'requestStartSpanMs':max(starts)-min(starts),'requestArrivalRate':(len(formal)-1)/((max(starts)-min(starts))/1000),
        'windowStarts':sum(window['startEpochMs']<=t<window['endEpochMs'] for t in starts),
        'windowStartQps':sum(window['startEpochMs']<=t<window['endEpochMs'] for t in starts)/seconds,
        'outsideFixedWindowStarts':sum(not window['startEpochMs']<=t<window['endEpochMs'] for t in starts),
        'normalBusinessCompletionQps':sum(v['windowCompleted'] for v in groups.values())/seconds,'groups':groups,
        'httpStatuses':dict(collections.Counter(r['responseCode'] for r in formal)),
        'systemErrors':[{'code':r['responseCode'],'businessCode':r.get('businessCode'),'message':r['responseMessage'],'clientKey':r['effClientKey']} for r in systems],
        'paidOrders':ledger['orders'][1],'remainingStock':ledger['remaining'],'activeEntitlements':ledger['entitlements'][0],
        'processingLeft':sum(r[3]=='PROCESSING' for r in ledger['requests']),
        'drainConfirmedAfterLastHttpSec':max(0,(history[-1]['atMs']-max(ends))/1000),
        'drainObservationNote':'Confirmation upper bound includes post-load polling and reconciliation, not exact queue empty instant; use observations for tighter bracket.',
        'windowCommittedTps':report_events['windowCommittedTps'],'fullCommittedTps':report_events['fullCommittedTps'],
        'clientObservedSucceededMs':distribution([r['clientObservedSucceededMs'] for r in poll_results if r['clientObservedSucceededMs'] is not None]),
        'checks':checks,'correctnessPass':all(checks.values()),'reconciliationMismatches':mismatches})
    print('COMPLETE',out,'paid',ledger['orders'][1],'checks',checks,flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--meta',required=True);p.add_argument('--warm',required=True)
    for k in ['out','private']:p.add_argument('--'+k,required=True)
    for k in ['count','rate','workers','window']:p.add_argument('--'+k,type=int,required=True)
    run(p.parse_args())
