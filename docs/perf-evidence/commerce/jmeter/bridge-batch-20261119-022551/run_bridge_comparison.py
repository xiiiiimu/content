"""Sequential local bridge comparison; fresh activities, immutable JAR, no evidence deletion."""
import argparse,csv,hashlib,json,os,re,subprocess,time,ctypes
from pathlib import Path
import purchase_capacity as pc
from run_v23_performance import read,write

ROOT=Path.cwd();JAVA=Path('C:/Users/西木/.jdks/ms-17.0.17')
def command(args,log,timeout=None):
    env=os.environ.copy();env['JAVA_HOME']=str(JAVA);env['PATH']=str(JAVA/'bin')+os.pathsep+env['PATH']
    with Path(log).open('x',encoding='utf8') as f:
        p=subprocess.run(list(map(str,args)),stdout=f,stderr=subprocess.STDOUT,env=env,timeout=timeout)
    if p.returncode:raise RuntimeError('Command failed; see '+str(log))
def powershell(script):
    return subprocess.check_output(['pwsh','-NoProfile','-Command','[Console]::OutputEncoding=[Text.UTF8Encoding]::new();'+script],encoding='utf8').strip()
def clear_backlog():
    rows=pc.mysql("SELECT COUNT(*) FROM material_purchase_requests WHERE state='PROCESSING';SELECT COUNT(*) FROM material_sale_dispatch WHERE state<>'SENT';SELECT COUNT(*) FROM material_orders WHERE state='HELD';SELECT COUNT(*) FROM material_sale_cache_jobs WHERE revision>applied_revision;")
    redis=pc.stream_sample(0)
    exists=int(pc.mysql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='content' AND table_name='material_sale_stream_quarantine';")[0][0])
    quarantine=int(pc.mysql("SELECT COUNT(*) FROM material_sale_stream_quarantine WHERE disposition IN ('HOLD','RELEASE_PENDING');")[0][0]) if exists else None
    if any(int(r[0]) for r in rows) or quarantine not in (None,0) or redis.get('globalStreamPending')!=0 or redis.get('globalStreamLag')!=0 or redis.get('ready')!='1':raise RuntimeError('Previous workload not fully drained; no restart or next load')
    return {'atMs':pc.epoch_ms(),'database':rows,'redis':redis,'unresolvedQuarantine':quarantine}
def start_backend(jar,size,directory):
    before=clear_backlog()
    pid=powershell("(Get-NetTCPConnection -LocalPort 28080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess")
    if pid:
        # Never print CommandLine: existing S3 flags can contain local credentials.
        allowed=re.escape(jar.name)+'|content-v23-376b7ae3cf20\\.jar'
        script=f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}'; if($p.CommandLine -notmatch '{allowed}'){{throw 'Unexpected API process; preserved'}}; Stop-Process -Id {int(pid)}; Start-Sleep -Seconds 2"
        powershell(script)
    command(['pwsh','-NoProfile','-File','infra/jmeter/run-java-commerce.ps1','-JarPath',jar,'-DiagnosticsDir',directory,'-SegmentTimings','-BridgeBatchSize',size],str(directory)+'.startup.log',240)
    pid=int((Path(directory)/'java.pid').read_text(encoding='utf-8-sig').strip().split('=')[1])
    script=f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; $m=[regex]::Match($p.CommandLine,'--content.sale.bridge-batch-size=(\\d+)(?:\\s|$)'); $safe=@{{}};foreach($x in [regex]::Matches($p.CommandLine,'--([\\w.-]+)=([^\\s]+)')){{$k=$x.Groups[1].Value;if($k -match '^(server\\.|spring\\.datasource\\.hikari\\.|content\\.sale\\.|spring\\.profiles\\.active$)' -and $k -notmatch '(?i)password|token|secret|key'){{$safe[$k]=$x.Groups[2].Value}}}}; [pscustomobject]@{{pid=$p.ProcessId;java=$p.ExecutablePath;jarMatches=$p.CommandLine.Contains('{jar.name}');batchSize=$m.Groups[1].Value;options=$safe;listener=(Get-NetTCPConnection -LocalPort 28080 -State Listen | Select-Object -First 1).OwningProcess}} | ConvertTo-Json -Depth 4 -Compress"
    actual=json.loads(powershell(script))
    if not actual['jarMatches'] or int(actual['batchSize'])!=size or actual['listener']!=pid:raise RuntimeError('Backend runtime fingerprint/config mismatch')
    if pc.mysql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;')[0][0]!='24':raise RuntimeError('Expected V24 schema before preparing load')
    write(Path(directory)/'safe-runtime.json',{'beforeRestart':before,**actual,'jarSha256':hashlib.file_digest(jar.open('rb'),'sha256').hexdigest()})
    return pid
def seed_one(directory,prefix,stock,pool,author):
    command(['pwsh','-NoProfile','-File','infra/jmeter/seed-commerce-data.ps1','-Buyers','1','-Stock',stock,'-Prefix',prefix,'-OutDir',directory,'-ReuseCsvGlob',pool,'-ReuseAuthorMeta',author,'-ReturnMetaPath'],str(directory)+'.log',180)
    # Seed prints the exact run meta path last. No directory mtime discovery.
    lines=Path(str(directory)+'.log').read_text(encoding='utf8').splitlines()
    candidates=[Path(line.strip()) for line in lines if re.match(r'^[A-Za-z]:[\\/].*meta-.*\.json$',line.strip())]
    if len(candidates)!=1:raise RuntimeError('Seed did not identify one exact meta path')
    return read(candidates[0])
def dataset(private,name,count,stock,workers):
    path=private/name;path.mkdir(exist_ok=False)
    old=ROOT/'target/v23-perf-20261118-234151'
    pool=old/'formal-reuse.csv';warm_pool=old/'warm-reuse.csv'
    author=Path((ROOT/'target/flash-5000-meta.txt').read_text().strip())
    formal=seed_one(path/'seed-formal','bb'+name+'f',stock,pool,author)
    warm=seed_one(path/'seed-warm','bb'+name+'w',workers,warm_pool,author)
    for label,m,source,number in [('formal',formal,pool,count),('warm',warm,warm_pool,workers)]:
        with source.open(encoding='utf-8-sig') as f:rows=list(csv.DictReader(f))[:number]
        if len(rows)!=number:raise RuntimeError('Insufficient isolated buyer pool')
        csvpath=path/(label+'-buyers.csv')
        with csvpath.open('x',encoding='utf8',newline='') as f:
            w=csv.DictWriter(f,fieldnames=['userId','token','clientKey']);w.writeheader();w.writerows({**r,'clientKey':f"jm-c{m['campaignId']}-u{r['userId']}"} for r in rows)
        m['buyers']=number;m['buyersCsv']=str(csvpath.resolve());write(path/(label+'-meta.json'),m)
    command(['python','infra/jmeter/refresh_v23_data.py','--meta',path/'formal-meta.json','--warm',path/'warm-meta.json','--out',path/'fresh'],path/'refresh.log',900)
    return path/'fresh/formal-meta.json',path/'fresh/warm-meta.json'
def measurement(private,public,name,jar,pid,size,smoke=False):
    count,stock,workers,rate,window=(16,16,4,2,0) if smoke else (4000,4000,64,60,60)
    meta,warm=dataset(private,name,count,stock,workers)
    out=public/name;measurement_private=private/name/'measure'
    args=['python','infra/jmeter/run_v23_performance.py','--meta',meta,'--warm',warm,'--count',16 if smoke else 3600,'--rate',rate,'--workers',workers,'--window',window,'--out',out,'--private',measurement_private,'--backend-pid',pid,'--jar-path',jar,'--bridge-batch-size',size]
    write(private/name/'command.json',list(map(str,args)));command(args,private/name/'measurement.log',5400)
    for tool in ['analyze_v23_performance.py','bridge_batch_metrics.py']:command(['python','infra/jmeter/'+tool,out],out/(tool+'.log'),180)
    s=read(out/'summary.json');e=read(out/'commit-evidence.json');b=read(out/'bridge-batch-metrics.json')
    verified={'atMs':pc.epoch_ms(),'businessCorrectness':s['correctnessPass'],'orderCommitCoverage':e['commitEvidenceComplete'],
              'httpTimingCoverage':e['matchedHttpCount']==s['samples'],'bridgeCommitCoverage':b['identityCommitCoverageComplete'],
              'batchReadBound':max(map(int,b['actualReadSizeDistribution']),default=0)<=size,
              'noPostSql':e['httpSqlCounts']=={'0':s['samples']},'queuesAfter':clear_backlog()}
    write(out/'measurement-verification.json',verified)
    if not all(verified[k] for k in ['businessCorrectness','orderCommitCoverage','httpTimingCoverage','bridgeCommitCoverage','batchReadBound','noPostSql']):raise RuntimeError('Evidence or correctness incomplete; stop before next profile: '+str(out))
    # Preserve a scoped service log snapshot privately, no credentials in public reports.
    print('VERIFIED',name,'samples',s['samples'],'qps',s['windowStartQps'],'windowTPS',s['windowCommittedTps'],'fullTPS',s['fullCommittedTps'],'postP95',s['groups']['accepted']['elapsed']['p95'],flush=True)
    return s
def main():
    p=argparse.ArgumentParser();p.add_argument('--jar',required=True);p.add_argument('--private',required=True);p.add_argument('--public',required=True);p.add_argument('--modes',nargs='*',type=int,default=[1,20,57]);p.add_argument('--skip-smoke',action='store_true');a=p.parse_args()
    jar=Path(a.jar).resolve();private=Path(a.private);public=Path(a.public)
    if not jar.is_relative_to(ROOT):raise ValueError('JAR must be inside E:/content workspace')
    private.mkdir(parents=True,exist_ok=False);public.mkdir(parents=True,exist_ok=False)
    guard=ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
    try:
        write(private/'jar.json',{'path':str(jar),'sha256':hashlib.file_digest(jar.open('rb'),'sha256').hexdigest()})
        if not a.skip_smoke:
            pid=start_backend(jar,20,private/'backend-smoke');measurement(private,public,'smoke',jar,pid,20,True)
        for size in a.modes:
            if size not in (1,20,57):raise ValueError('Only authorized batch profiles 1/20/57')
            pid=start_backend(jar,size,private/('backend-b'+str(size)))
            measurement(private,public,'b'+str(size),jar,pid,size)
        write(public/'sequence-complete.json',{'atMs':pc.epoch_ms(),'modes':a.modes,'sameJar':str(jar),'sha256':hashlib.file_digest(jar.open('rb'),'sha256').hexdigest()})
    except Exception as error:
        write(public/'sequence-failure.json',{'atMs':pc.epoch_ms(),'error':type(error).__name__,'detail':str(error),'nextProfileStarted':False});raise
    finally:
        if guard:ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)
if __name__=='__main__':main()
