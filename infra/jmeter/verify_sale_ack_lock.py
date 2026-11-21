"""One isolated 8-buyer verification; SQL row lock + one controlled MQ replay.
No credentials, request headers or token fields are exported.
"""
import concurrent.futures,csv,json,re,subprocess,sys,time
from pathlib import Path
from urllib.request import Request,ProxyHandler,build_opener
sys.path.insert(0,str(Path('infra/jmeter').resolve()))
from purchase_capacity import mysql
from flash_acceptance import snapshot,redis_snapshot

meta=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
out=Path(sys.argv[2]);campaign=int(meta['campaignId'])
assert not out.exists(),'Choose a new evidence file; never overwrite a prior run'
with open(meta['buyersCsv'],encoding='utf-8-sig') as f:buyers=list(csv.DictReader(f))
assert len(buyers)==meta['buyers']==meta['stock']==8 and meta['paidSeeded']==0
opener=build_opener(ProxyHandler({}))
def call(path,body,token):
    request=Request(meta['apiBase']+path,data=json.dumps(body).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+token},method='POST')
    started=time.monotonic()
    with opener.open(request,timeout=30) as response:result=json.load(response)
    return result,round((time.monotonic()-started)*1000,3)
def state():
    result=snapshot(campaign);result['cache']=redis_snapshot(campaign,result['requests']);return result
def wait(predicate,seconds):
    deadline=time.monotonic()+seconds
    while True:
        result=state()
        if predicate(result):return result
        if time.monotonic()>deadline:raise TimeoutError('Small verification barrier not reached')
        time.sleep(.25)
evidence=dict(campaignId=campaign,postId=int(meta['postId']),buyers=8,stock=8,controlledMqReplays=1,pass_=False,
    lockInjection='Separate test SQL session holds the same request-row X lock as a consumer; all MQ sends/ACKs use the actual application and Broker.')
locker=None;reader=None;connection=None;released=False
try:
    pristine=state()
    assert pristine['orders']==[0,0,0,0] and not pristine['requests'] and pristine['sold']==0 and pristine['remaining']==8 and pristine['cache']['stock']==8,'Use a fresh isolated stock-8 campaign'
    assert call('/api/sale-debug/hold-settle',{'arm':True},meta['authorToken'])[0]['holdArmed']
    responses=[]
    for b in buyers:
        r,elapsed=call(f'/api/sale-campaigns/{campaign}/purchase',{'clientKey':b['clientKey']},b['token'])
        assert r['state']=='PROCESSING'
        responses.append(dict(userId=int(b['userId']),clientKey=b['clientKey'],reservationId=r['reservationId'],httpElapsedMs=elapsed))
    evidence['accepted']=responses
    before=wait(lambda s:len(s['requests'])==8 and s['cache']['dispatchCount']==8 and s['cache']['dispatchPending']==0,30)
    assert before['orders'][0]==0 and all(r[3]=='PROCESSING' for r in before['requests'])
    rid=responses[0]['reservationId'];assert re.fullmatch('[a-f0-9]{32}',rid)
    evidence['beforeReplay']=before
    locker=subprocess.Popen(['docker','exec','-i','content-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB --unbuffered content'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
    reader=concurrent.futures.ThreadPoolExecutor(max_workers=1)
    locker.stdin.write(f"START TRANSACTION;SELECT CONNECTION_ID(),'LOCK_HELD' FROM material_purchase_requests WHERE reservation_id='{rid}' FOR UPDATE;\n");locker.stdin.flush()
    line=reader.submit(locker.stdout.readline).result(timeout=10).strip().split('\t')
    assert len(line)==2 and line[1]=='LOCK_HELD';connection=int(line[0])
    # Only this newly created test reservation is requeued. The old token is
    # invalidated; event identity, request fields and MQ payload stay unchanged.
    rows=mysql(f"SELECT UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000;UPDATE material_sale_dispatch SET state='PENDING',available_at_ms=0,lease_until_ms=0,token=token+1,sent_at_ms=NULL WHERE reservation_id='{rid}' AND state='SENT';SELECT ROW_COUNT();")
    requeued=int(float(rows[0][0]));assert rows[1][0]=='1'
    deadline=time.monotonic()+15
    while True:
        d=mysql(f"SELECT state,attempts,token,COALESCE(sent_at_ms,0) FROM material_sale_dispatch WHERE reservation_id='{rid}'")[0]
        if d[0]=='SENT' and int(d[1])>=2 and int(d[3])>=requeued:break
        if time.monotonic()>deadline:raise TimeoutError('Broker confirmation waited for the held request row')
        time.sleep(.25)
    locks=mysql(f"SELECT COUNT(*) FROM performance_schema.data_locks l JOIN information_schema.innodb_trx t ON l.ENGINE_TRANSACTION_ID=t.trx_id WHERE t.trx_mysql_thread_id={connection} AND l.OBJECT_SCHEMA='content' AND l.OBJECT_NAME='material_purchase_requests' AND l.LOCK_TYPE='RECORD' AND l.LOCK_MODE LIKE 'X%' AND l.LOCK_STATUS='GRANTED'")[0][0]
    assert int(locks)>0 and locker.poll() is None
    assert mysql(f"SELECT COUNT(*) FROM material_orders WHERE campaign_id={campaign}")[0][0]=='0'
    evidence['ackWhileRequestLocked']=dict(reservationId=rid,sqlLockConnectionId=connection,grantedRequestRowLocks=int(locks),dispatchState=d[0],attempts=int(d[1]),token=int(d[2]),requeuedAtDbMs=requeued,ackRecordedAtDbMs=int(d[3]),ackRecordAfterRequeueMs=int(d[3])-requeued,requestLockReleased=False)
    locker.stdin.write("COMMIT;SELECT 'LOCK_RELEASED';\n");locker.stdin.flush()
    assert reader.submit(locker.stdout.readline).result(timeout=10).strip()=='LOCK_RELEASED'
    released=True
    locker.stdin.write("QUIT;\n");locker.stdin.flush();locker.wait(timeout=5)
    release_started=time.monotonic();call('/api/sale-debug/hold-settle',{'arm':False},meta['authorToken'])
    final=wait(lambda s:s['orders'][1]==8 and s['cache']['processing']==s['cache']['streamPending']==s['cache']['streamLag']==s['cache']['dispatchPending']==s['cache']['cacheJobsPending']==0,30)
    expected={r['reservationId']:(str(r['userId']),r['clientKey']) for r in responses}
    assert len(final['requests'])==8 and final['orders']==[8,8,0,0] and final['entitlements']==[8,0] and final['remaining']==0
    for r in final['requests']:
        assert (r[1],r[2])==expected[r[0]] and r[3]=='SUCCEEDED' and r[5]=='PAID' and r[7]=='1' and r[8]=='SUCCEEDED'
    evidence['final']=final;evidence['drainAfterReleaseSec']=round(time.monotonic()-release_started,3);evidence['pass_']=True
except BaseException as error:
    evidence['failureType']=type(error).__name__;raise
finally:
    if locker and locker.poll() is None:
        try:
            locker.stdin.write("ROLLBACK;QUIT;\n");locker.stdin.flush();locker.wait(timeout=5)
        except BaseException as cleanup_error:
            evidence['lockCleanupErrorType']=type(cleanup_error).__name__
            try:
                if connection:mysql(f"KILL CONNECTION {connection}")
            except BaseException as kill_error:evidence['lockCleanupUnconfirmed']=type(kill_error).__name__
            locker.terminate()
    if reader:reader.shutdown(wait=False,cancel_futures=True)
    try:call('/api/sale-debug/hold-settle',{'arm':False},meta['authorToken'])
    except BaseException:evidence['holdCleanupUnconfirmed']=True
    evidence['pass']=evidence.pop('pass_')
    with out.open('x',encoding='utf8') as f:json.dump(evidence,f,indent=2)
print(json.dumps(dict(campaignId=campaign,buyers=8,pass_=evidence['pass'],ackWhileRequestLocked=evidence.get('ackWhileRequestLocked'),paidOrders=evidence['final']['orders'][1],activeEntitlements=evidence['final']['entitlements'][0])))
