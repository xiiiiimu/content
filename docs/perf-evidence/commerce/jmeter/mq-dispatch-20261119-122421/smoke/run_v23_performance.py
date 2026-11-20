"""Local V23 A/B evidence runner. Credentials remain in target, never in evidence."""
import argparse,base64,collections,concurrent.futures,csv,hashlib,json,math,os,re,shutil,subprocess,threading,time
from pathlib import Path
from urllib.request import Request,build_opener,ProxyHandler
import purchase_capacity as pc
from flash_acceptance import snapshot,redis_snapshot
from v23_plan import generate

JAVA=Path('C:/Users/西木/.jdks/ms-17.0.17/bin')
API='http://127.0.0.1:28080'
BACKEND_PID=24820
def now():return time.time_ns()//1_000_000
def write(path,value):path.write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf8')
def read(path):return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def request(path,token,body=None):
    req=Request(API+path,data=json.dumps(body).encode() if body is not None else None,
                headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'})
    with build_opener(ProxyHandler({})).open(req,timeout=5) as r:return json.load(r)
def replay_token(uid,token):
    expiry=json.loads(base64.urlsafe_b64decode(token.split('.')[0]+'==='))['expiresAt']
    if expiry>now()+30_000:return token
    name=pc.mysql('SELECT username FROM users WHERE id='+str(int(uid))+';')[0][0]
    if not re.fullmatch('jmb[A-Za-z0-9_]+',name):raise ValueError('Not an isolated JMeter buyer')
    req=Request(API+'/api/auth/login',data=json.dumps({'username':name,'password':'password-b1'}).encode(),headers={'Content-Type':'application/json'})
    with build_opener(ProxyHandler({})).open(req,timeout=15) as r:result=json.load(r)
    if str(result['userId'])!=str(uid):raise ValueError('Replay identity mismatch')
    return result['token']
def distribution(values):
    s=sorted(values)
    return {'count':len(s),**{k:(s[math.ceil(len(s)*p)-1] if s else None) for k,p in [('p50',.5),('p95',.95),('p99',.99),('max',1)]}}
def jcmd(*args):
    p=subprocess.run([str(JAVA/'jcmd.exe'),str(BACKEND_PID),*args],capture_output=True,text=True,timeout=15)
    if p.returncode:raise RuntimeError('jcmd failed: '+p.stderr)
    return p.stdout
def backlog(campaign):
    return {'startEpochMs':now(),'database':pc.database_sample(campaign),'redis':pc.stream_sample(campaign),'endEpochMs':now()}
def known_buyer_processing(post,campaign,users):
    # Bounded to this run's supplied IDs; never scan the whole shared hash.
    lua="""local n=0;local bad=0;local stale=0;local checked=0
for user in string.gmatch(ARGV[3],'[^,]+') do
 checked=checked+1;local rid=redis.call('HGET',KEYS[1],'u:'..user..':'..ARGV[1])
 if rid then local raw=redis.call('HGET',KEYS[1],'r:'..rid)
  if not raw then bad=bad+1 else local r=cjson.decode(raw)
   if tostring(r.campaignId)~=ARGV[2] then bad=bad+1
   elseif r.state=='PROCESSING' then n=n+1 else stale=stale+1 end
  end
 end
end
local t=redis.call('TIME');return cjson.encode({processing=n,invalid=bad,staleGuards=stale,checkedUsers=checked,redisEpochMs=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)})"""
    r=subprocess.run(['docker','exec','content-redis-1','redis-cli','--raw','EVAL',lua,'1','sale:{entry-v2}:state',str(post),str(campaign),','.join(users)],capture_output=True,text=True,timeout=5,check=True)
    return json.loads(r.stdout)

def events_report(tsv,formal,ledger,window,require_success=False):
    by_rid={r[0]:r for r in ledger['requests']}
    posts={r['reservationId']:r for r in formal if r['responseCode']=='200'}
    by_key={r['effClientKey']:r for r in formal}
    # Long drains contain many empty scans/producer polls. Keep only relevant
    # roots, then their phases, instead of materializing the whole recording.
    phases=collections.defaultdict(list);roots=[];loss=0;gc=[]
    with tsv.open(encoding='utf8') as f:
        for e in csv.DictReader(f,delimiter='\t'):
            if e['event']=='request' and ((e['kind']=='http' and e['clientKey'] in by_key) or (e['kind']=='settle' and e['reservationId'] in posts)):roots.append(e)
            elif e['event']=='DATA_LOSS':loss+=1
            elif e['event']=='gc' and window['startEpochMs']<=int(e['startMs'])<window['endEpochMs']:gc.append(e)
    wanted={(r['traceId'],r.get('threadId','')) for r in roots}
    with tsv.open(encoding='utf8') as f:
        for e in csv.DictReader(f,delimiter='\t'):
            key=(e['traceId'],e.get('threadId',''))
            if e['event']=='phase' and key in wanted:phases[key].append(e)
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
            if require_success and root['status']!='200':continue
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
        db=by_rid.get(rid)
        if db and db[5]=='PAID' and counts[rid]!=1:missing.append({'reservationId':rid,'commitEvidenceCount':counts[rid]})
    valid=[c for c in commits if counts[c['reservationId']]==1]
    seconds=(window['endEpochMs']-window['startEpochMs'])/1000
    count=sum(window['startEpochMs']<=c['commitReturnedAtMs']<window['endEpochMs'] for c in valid)
    complete=not missing and not loss and len(valid)==ledger['orders'][1]
    return {'commitEvidenceComplete':complete,'dataLossEvents':loss,'missingOrAmbiguous':missing,'uniqueOrderCommits':valid,
            'windowCommittedOrders':count if complete else None,'windowCommittedTps':count/seconds if complete else None,
            'fullCommittedTps':len(valid)/((max(c['commitReturnedAtMs'] for c in valid)-min(int(r['timeStamp']) for r in formal))/1000) if complete and valid and len(valid)==len(posts) else None,
            'httpStartToServerCommitReturnedMs':distribution([c['httpStartToCommitReturnedMs'] for c in valid]),
            'httpApplicationMs':distribution([r['durationMs'] for r in http]),'httpSqlCounts':dict(collections.Counter(r['sqlCount'] for r in http)),
            'matchedHttpCount':len(http),'serverGcPauseMs':distribution([int(e['endMs'])-int(e['startMs']) for e in gc]),'settlementPhasesMs':{k:distribution(v) for k,v in phase_times.items()},
            'http':http,'note':'JDBC commit RETURN boundary, not physical disk instant. paidAtRecordMs is separate. Post-transaction cache commits excluded.'}

def run(args):
    global BACKEND_PID
    BACKEND_PID=getattr(args,'backend_pid',24820)
    root=Path.cwd();meta=read(args.meta);warm=read(args.warm)
    out=Path(args.out);out.mkdir(parents=True,exist_ok=False)
    private=Path(args.private);private.mkdir(parents=True,exist_ok=False)
    generate(out/'plan.jmx')
    for f in ['v23-prepare.groovy','v23-response.groovy','v23_plan.py','run_v23_performance.py','V23Events.java','V23Pacer.java']:
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
    def queue_snapshot():
        value=backlog(meta['campaignId'])
        if args.window:
            try:value['redisKnownFormalBuyers']=known_buyer_processing(meta['postId'],meta['campaignId'],[r['userId'] for r in buyers[:args.count]])
            except (OSError,subprocess.SubprocessError,ValueError) as error:value['redisKnownFormalBuyers']={'unavailable':type(error).__name__}
            value['endEpochMs']=now()
        return value
    classes=private/'classes';classes.mkdir()
    subprocess.run([str(JAVA/'javac.exe'),'-d',str(classes),'infra/jmeter/V23Pacer.java'],check=True,capture_output=True)
    config={'runId':out.name,'campaignId':meta['campaignId'],'postId':meta['postId'],'warmCampaignId':warm['campaignId'],
            'count':args.count,'stock':meta['stock'],'rate':args.rate,'workers':args.workers,'windowSec':args.window,
            'timeoutMs':30000,'loopsPerBuyer':1,'sameJvmWarmup':True,'heap':'128m/384m','clientJfr':False,'clientThreadDump':False,
            'temporarySystemSleepPrevention':getattr(args,'sleep_guard',False),'hostInterruptionInvalidatesRunMs':30000,
            'sourceRevision':'V24-bridge-batch' if getattr(args,'jar_path',None) else 'V23',
            'backendPid':BACKEND_PID,'bridgeBatchSize':getattr(args,'bridge_batch_size',None),'dispatchProfile':getattr(args,'dispatch_profile',None),
            'brokerLagSampler':{'intervalMs':getattr(args,'lag_interval_ms',0),'group':getattr(args,'consumer_group',None)} if getattr(args,'lag_classpath',None) else None,'requireSuccessfulSettlementRoot':bool(getattr(args,'jar_path',None)),
            'jarSha256':hashlib.file_digest(open(args.jar_path,'rb'),'sha256').hexdigest() if getattr(args,'jar_path',None) else '376b7ae3cf209dec1f36993c81d27685ab6de3014a687fdeee642d521c54efa5',
            'warmupSettlementPoll':'Each worker, 1 second interval, 60 second bound, outside formal; same HTTP POST sampler/thread',
            'finalResultPolling':'After formal HTTP only; fair round-robin, concurrency16, max100 calls/batch, >=1s between batches, global120s deadline. Per-request interval grows with outstanding set. No per-request starvation; actual attempt timestamps archived.',
            'drainObservationLimitSec':3600,
            'observerIntervalSec':2,'pacer':'One serialized coordinator; Windows park leaves 16ms headroom then spins; at400/s may use one CPU core, captured in host load, never subtracted',
            'serverJfr':'Only custom sale events + GC pause + DataLoss, no stacks/environment/JVM properties',
            'hostMemoryBefore':pc.windows_memory(),'diskFreeBytesBefore':shutil.disk_usage(root).free}
    write(out/'config.json',config)
    if config['hostMemoryBefore'].get('availableCommitBytes',0)<800_000_000:raise RuntimeError('Insufficient host commit headroom for bounded JMeter JVM')
    jfc=private/'scoped.jfc'
    jfc.write_text('<configuration version="2.0" label="Purchase scoped">'+''.join('<event name="'+n+'"><setting name="enabled">true</setting><setting name="stackTrace">false</setting><setting name="threshold">0 ms</setting></event>' for n in ['content.SaleRequest','content.SalePhase','content.SaleBridgeBatch','content.SaleDispatchBatch','jdk.DataLoss','jdk.GCPhasePause'])+'</configuration>',encoding='utf8')
    shutil.copy2(jfc,out/'server-scoped.jfc')
    write(out/'backlog-before-warmup.json',queue_snapshot())
    watch=threading.Thread(target=pc.observe,args=(out,meta['campaignId'],2),daemon=True);watch.start()
    lag=None
    if getattr(args,'lag_classpath',None):
        # Broker consumer-group backlog every lag_interval_ms by a long-lived admin client (equivalent of mqadmin consumerProgress).
        lag=subprocess.Popen([str(JAVA/'java.exe'),'-Xmx64m','-cp',Path(args.lag_classpath).read_text(encoding='utf8').strip(),'infra/jmeter/ConsumerLagSampler.java',
            '127.0.0.1:29876',args.topic,args.consumer_group,str(args.lag_interval_ms),str((out/'broker-lag.ndjson').resolve()),str((out/'observer.stop').resolve())],
            stdout=(out/'broker-lag.log').open('x',encoding='utf8'),stderr=subprocess.STDOUT)
    name='v23'+str(now());jfr=private/'server.jfr'
    (out/'jfr-start.log').write_text(jcmd('JFR.start','name='+name,'settings='+str(jfc.resolve()),'disk=true','maxsize=1g'),encoding='utf8')
    command=[str(JAVA/'java.exe'),'-Xms128m','-Xmx384m','-XX:MaxMetaspaceSize=256m',
             '-Xlog:gc*:file='+str((out/'injector-gc.log').resolve().relative_to(root)).replace('\\','/')+':time,uptime,level,tags',
             '-jar',str(Path('tools/apache-jmeter-5.6.3/bin/ApacheJMeter.jar').resolve()),'-n','-t',str((out/'plan.jmx').resolve()),'-l',str((out/'results.jtl').resolve()),'-j',str((out/'jmeter.log').resolve())]
    properties={'workers':args.workers,'rate':args.rate,'count':args.count,'windowSec':args.window,
        'warmCsv':str(Path(warm['buyersCsv']).resolve()),'formalCsv':str(Path(meta['buyersCsv']).resolve()),
        'warmCampaign':warm['campaignId'],'formalCampaign':meta['campaignId'],'windowPath':str((out/'window.json').resolve()),
        'user.classpath':str(classes.resolve()),
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
            edge_start=False;edge_end=False;previous_wall=now();previous_mono=time.monotonic()
            while process.poll() is None:
                current_wall=now();current_mono=time.monotonic()
                if current_wall-previous_wall>30000 or current_mono-previous_mono>30:
                    write(out/'host-interruption.json',{'previousWallMs':previous_wall,'currentWallMs':current_wall,
                        'wallGapMs':current_wall-previous_wall,'monotonicGapSec':current_mono-previous_mono,'invalidRun':True})
                    process.terminate();process.wait(timeout=10)
                    raise RuntimeError('Host interruption invalidated run; client terminated, backend preserved')
                previous_wall=current_wall;previous_mono=current_mono
                if (out/'window.json').exists():
                    w=read(out/'window.json')
                    if not edge_start and now()>=w['startEpochMs']:
                        write(out/'backlog-window-start.json',queue_snapshot());edge_start=True
                    if w['endEpochMs'] and not edge_end and now()>=w['endEpochMs']:
                        write(out/'backlog-window-end.json',queue_snapshot());edge_end=True
                time.sleep(.05)
        write(out/'runner-exit.json',{'exitCode':process.returncode,'atMs':now()})
        rows=list(csv.DictReader((out/'results.jtl').open(encoding='utf-8-sig')))
        formal=[r for r in rows if r['label']=='POST-purchase'];warmrows=[r for r in rows if r['label']=='WARMUP-purchase']
        if process.returncode or not formal or (not args.window and len(formal)!=args.count):raise RuntimeError('Incomplete JMeter formal run; inspect logs, no automatic retry')
        window=read(out/'window.json')
        if not window['endEpochMs']:window['endEpochMs']=max(int(r['timeStamp'])+int(r['elapsed']) for r in formal)+1
        write(out/'window.json',window)
        if not edge_end:write(out/'backlog-window-end.json',queue_snapshot())
        # Bounded polling after load. Tokens used only in memory, never persisted here.
        by_user={r['userId']:r for r in buyers};accepted=[r for r in formal if r['responseCode']=='200']
        poll_start=now();deadline=time.monotonic()+120
        poll_results=[{'reservationId':r['reservationId'],'clientKey':r['effClientKey'],'calls':0,'attemptTimesMs':[],
            'firstPollAtMs':None,'observedAtMs':None,'state':None,'error':None,'clientObservedSucceededMs':None} for r in accepted]
        todo=collections.deque(range(len(accepted)))
        def poll_one(index):
            row=accepted[index];item=poll_results[index];at=now();item['calls']+=1;item['attemptTimesMs'].append(at)
            if item['firstPollAtMs'] is None:item['firstPollAtMs']=at
            try:
                result=request('/api/purchase-requests/'+row['reservationId'],by_user[row['userId']]['token'])
                item['state']=result.get('state');item['observedAtMs']=now()
                if item['state']=='SUCCEEDED':item['clientObservedSucceededMs']=item['observedAtMs']-int(row['timeStamp'])
            except Exception as exc:item['error']=type(exc).__name__
            return index
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            while todo and time.monotonic()<deadline:
                tick=time.monotonic();batch=[todo.popleft() for _ in range(min(100,len(todo)))]
                for index in pool.map(poll_one,batch):
                    if poll_results[index]['state'] not in ('SUCCEEDED','REJECTED'):todo.append(index)
                time.sleep(max(0,tick+1-time.monotonic()))
        write(out/'client-result-polls.json',{'startEpochMs':poll_start,'endEpochMs':now(),'calls':sum(r['calls'] for r in poll_results),'results':poll_results})
        history=[];drain_deadline=time.monotonic()+3600
        while True:
            if shutil.disk_usage(root).free<2_000_000_000:raise RuntimeError('Disk headroom below 2GB; preserve evidence and stop new work')
            try:
                ledger=snapshot(meta['campaignId']);cache=redis_snapshot(meta['campaignId'],ledger['requests'])
            except (OSError,subprocess.SubprocessError,ValueError) as error:
                with (out/'reconciliation-errors.ndjson').open('a',encoding='utf8') as log:log.write(json.dumps({'atMs':now(),'error':type(error).__name__})+'\n')
                if time.monotonic()>=drain_deadline:raise
                time.sleep(1);continue
            pending=sum(r[3]=='PROCESSING' for r in ledger['requests'])+ledger['orders'][2]+sum(cache.get(k,0) or 0 for k in ['processing','streamPending','streamLag','dispatchPending','cacheJobsPending'])
            history.append({'atMs':now(),'pendingAggregate':pending,'paid':ledger['orders'][1]})
            if pending==0 or time.monotonic()>=drain_deadline:break
            write(out/'drain-progress.json',history[-1])
            time.sleep(2)
        write(out/'drain-samples.json',history);write(out/'reconciliation.json',ledger);write(out/'redis-reconciliation.json',cache)
        write(out/'backlog-after-drain.json',queue_snapshot())
        # A few known successful keys; outside load window, snapshot order counts both sides.
        replays=[];before=ledger['orders'][1]
        for row in accepted[:3]:
            token=replay_token(row['userId'],by_user[row['userId']]['token'])
            result=request('/api/sale-campaigns/'+str(meta['campaignId'])+'/purchase',token,{'clientKey':row['effClientKey']})
            replays.append({'clientKey':row['effClientKey'],'originalReservationId':row['reservationId'],'returnedReservationId':result.get('reservationId'),'returnedState':result.get('state')})
        after=snapshot(meta['campaignId'])['orders'][1]
        write(out/'replays.json',{'beforePaid':before,'afterPaid':after,'requests':replays,'pass':before==after and all(r['originalReservationId']==r['returnedReservationId'] for r in replays)})
    finally:
        (out/'jfr-stop.log').write_text(jcmd('JFR.stop','name='+name,'filename='+str(jfr.resolve())),encoding='utf8')
        (out/'observer.stop').write_text('stop');watch.join(timeout=20)
        if lag is not None:
            try:lag.wait(timeout=20)
            except subprocess.TimeoutExpired:lag.terminate()
    export=subprocess.run([str(JAVA/'java.exe'),'infra/jmeter/V23Events.java',str(jfr),str(out/'server-events.tsv')],capture_output=True,text=True,timeout=90)
    (out/'jfr-export.log').write_text(export.stdout+export.stderr,encoding='utf8')
    if export.returncode:raise RuntimeError('JFR whitelist export failed')
    report_events=events_report(out/'server-events.tsv',formal,ledger,window,require_success=bool(getattr(args,'jar_path',None)));write(out/'commit-evidence.json',report_events)
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
    p.add_argument('--backend-pid',type=int,default=24820);p.add_argument('--jar-path');p.add_argument('--bridge-batch-size',type=int,choices=range(1,201))
    p.add_argument('--dispatch-profile');p.add_argument('--lag-classpath');p.add_argument('--lag-interval-ms',type=int,default=5000)
    p.add_argument('--topic',default='content-sale');p.add_argument('--consumer-group',default='content-sale-v1')
    for k in ['out','private']:p.add_argument('--'+k,required=True)
    for k in ['count','rate','workers','window']:p.add_argument('--'+k,type=int,required=True)
    args=p.parse_args()
    import ctypes
    args.sleep_guard=bool(ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)) if os.name=='nt' else False
    try:run(args)
    finally:
        if args.sleep_guard:ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)
