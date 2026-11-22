"""Sequential MQ dispatch A/B/C comparison on one immutable JAR (config switch only); no evidence deletion.

A = serial (original one-row claim/send/confirm), B = batch cap 20 / concurrency 1, B2 = batch cap 20 / concurrency 2,
C = batch cap 20 / concurrency 4.
Bridge batch 20, 20 consumer threads, HTTP timeout and all other settings unchanged. Each tier: resource gate, fresh
campaign/clientKeys/buyers, 60/s x 60s, drain + per-request reconciliation, then the next tier. A tier with lease
violations, outcome-transaction failures, system errors, dispatch-table lock waits or incomplete evidence stops the
sequence before more load is added.

  python infra/jmeter/run_dispatch_comparison.py --jar <jar> --private target/<run>/perf-private --public docs/perf-evidence/commerce/jmeter/<run> --classpath target/<run>/runtime-classpath.txt
"""
import argparse, ctypes, hashlib, json, re, subprocess
from pathlib import Path
import purchase_capacity as pc
import run_bridge_comparison as bridge
from run_v23_performance import read, write

ROOT = Path.cwd()
PROFILES = {'A': ('serial', 20, 1), 'B': ('batch', 20, 1), 'B2': ('batch', 20, 2), 'C': ('batch', 20, 4)}
SCHEMA = '26'  # V26: campaign hot-row FKs dropped (docs/commerce-campaign-lock-analysis.md)
BRIDGE = 20


def gate(path, label, memory_only=False):
    args = ['python', 'infra/jmeter/mq_dispatch_gate.py', '--out', str(path), '--label', label] + (['--memory-only'] if memory_only else [])
    p = subprocess.run(args, capture_output=True, text=True, timeout=300)
    record = read(path)
    if p.returncode and not memory_only:
        raise RuntimeError('Resource gate failed (' + label + '): ' + ','.join(record.get('failures', [])) + '; thresholds not lowered')
    return record


def start_backend(jar, profile, directory):
    mode, cap, concurrency = PROFILES[profile]
    before = bridge.clear_backlog()
    pid = bridge.powershell("(Get-NetTCPConnection -LocalPort 28080 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1).OwningProcess")
    stopped = None
    if pid:
        # Only a backend started from this JAR may be replaced; its log stays in its own diagnostics directory.
        script = (f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={int(pid)}'; if($p.CommandLine -notmatch '{re.escape(jar.name)}')"
                  f"{{throw 'Unexpected API process; preserved'}}; Stop-Process -Id {int(pid)}; Start-Sleep -Seconds 2")
        bridge.powershell(script); stopped = int(pid)
    bridge.command(['pwsh', '-NoProfile', '-File', 'infra/jmeter/run-java-commerce.ps1', '-JarPath', jar, '-DiagnosticsDir', directory, '-SegmentTimings',
                    '-BridgeBatchSize', BRIDGE, '-DispatchMode', mode, '-DispatchBatchMax', cap, '-DispatchConcurrency', concurrency],
                   str(directory) + '.startup.log', 240)
    pid = int((Path(directory) / 'java.pid').read_text(encoding='utf-8-sig').strip().split('=')[1])
    # Never print the command line: S3 flags can contain local credentials. Only whitelisted keys are kept.
    script = (f"$p=Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; $safe=@{{}};"
              "foreach($x in [regex]::Matches($p.CommandLine,'--([\\w.-]+)=([^\\s]+)')){$k=$x.Groups[1].Value;"
              "if($k -match '^(server\\.|spring\\.datasource\\.hikari\\.|content\\.sale\\.|spring\\.profiles\\.active$)' -and $k -notmatch '(?i)password|token|secret|key'){$safe[$k]=$x.Groups[2].Value}};"
              f"[pscustomobject]@{{pid=$p.ProcessId;jarMatches=$p.CommandLine.Contains('{jar.name}');options=$safe;"
              "listener=(Get-NetTCPConnection -LocalPort 28080 -State Listen | Select-Object -First 1).OwningProcess} | ConvertTo-Json -Depth 4 -Compress")
    actual = json.loads(bridge.powershell(script))
    o = actual['options']
    expected = {'content.sale.dispatch-mode': mode, 'content.sale.dispatch-batch-max': str(cap), 'content.sale.dispatch-concurrency': str(concurrency),
                'content.sale.bridge-batch-size': str(BRIDGE), 'content.sale.consume-threads': '20'}
    if not actual['jarMatches'] or actual['listener'] != pid or any(o.get(k) != v for k, v in expected.items()):
        raise RuntimeError('Backend runtime fingerprint/config mismatch: ' + json.dumps({k: o.get(k) for k in expected}))
    if pc.mysql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;')[0][0] != SCHEMA:
        raise RuntimeError('Expected V' + SCHEMA + ' schema before preparing load')
    write(Path(directory) / 'safe-runtime.json', {'beforeRestart': before, 'replacedPid': stopped, **actual,
                                                  'jarSha256': hashlib.file_digest(jar.open('rb'), 'sha256').hexdigest()})
    return pid


def dataset(private, name, count, stock, workers):
    path = private / name; path.mkdir(exist_ok=False)
    old = ROOT / 'target/v23-perf-20261118-234151'
    pool = old / 'formal-reuse.csv'; warm_pool = old / 'warm-reuse.csv'
    author = Path((ROOT / 'target/flash-5000-meta.txt').read_text().strip())
    formal = bridge.seed_one(path / 'seed-formal', 'md' + name + 'f', stock, pool, author)
    warm = bridge.seed_one(path / 'seed-warm', 'md' + name + 'w', workers, warm_pool, author)
    import csv
    for label, m, source, number in [('formal', formal, pool, count), ('warm', warm, warm_pool, workers)]:
        with source.open(encoding='utf-8-sig') as f:
            rows = list(csv.DictReader(f))[:number]
        if len(rows) != number:
            raise RuntimeError('Insufficient isolated buyer pool')
        csvpath = path / (label + '-buyers.csv')
        with csvpath.open('x', encoding='utf8', newline='') as f:
            w = csv.DictWriter(f, fieldnames=['userId', 'token', 'clientKey']); w.writeheader()
            w.writerows({**r, 'clientKey': f"jm-c{m['campaignId']}-u{r['userId']}"} for r in rows)
        m['buyers'] = number; m['buyersCsv'] = str(csvpath.resolve()); write(path / (label + '-meta.json'), m)
    bridge.command(['python', 'infra/jmeter/refresh_v23_data.py', '--meta', path / 'formal-meta.json', '--warm', path / 'warm-meta.json', '--out', path / 'fresh'], path / 'refresh.log', 900)
    return path / 'fresh/formal-meta.json', path / 'fresh/warm-meta.json'


def measurement(private, public, name, profile, jar, pid, classpath, smoke=False):
    count, stock, workers, rate, window = (16, 16, 4, 2, 0) if smoke else (4000, 4000, 64, 60, 60)
    meta, warm = dataset(private, name, count, stock, workers)
    out = public / name; measurement_private = private / name / 'measure'
    args = ['python', 'infra/jmeter/run_v23_performance.py', '--meta', meta, '--warm', warm, '--count', 16 if smoke else 3600, '--rate', rate,
            '--workers', workers, '--window', window, '--out', out, '--private', measurement_private, '--backend-pid', pid, '--jar-path', jar,
            '--bridge-batch-size', BRIDGE, '--dispatch-profile', profile + ':' + ','.join(map(str, PROFILES[profile])),
            '--lag-classpath', classpath, '--lag-interval-ms', 5000]
    write(private / name / 'command.json', list(map(str, args))); bridge.command(args, private / name / 'measurement.log', 5400)
    for tool in ['analyze_v23_performance.py', 'bridge_batch_metrics.py', 'dispatch_batch_metrics.py']:
        bridge.command(['python', 'infra/jmeter/' + tool, out], out / (tool + '.log'), 300)
    bridge.command(['python', 'infra/jmeter/commits_per_order.py', out, '--out', out / 'commits-per-order.json'], out / 'commits_per_order.py.log', 300)
    orphans = read(out / 'orphan-references.json')
    s = read(out / 'summary.json'); e = read(out / 'commit-evidence.json'); b = read(out / 'bridge-batch-metrics.json'); d = read(out / 'dispatch-batch-metrics.json')
    v = d['leaseViolations']
    dispatch_locks = d['lockWaits']['observedWaitsByTable'].get('material_sale_dispatch', 0)
    verified = {'atMs': pc.epoch_ms(), 'businessCorrectness': s['correctnessPass'], 'orderCommitCoverage': e['commitEvidenceComplete'],
                'httpTimingCoverage': e['matchedHttpCount'] == s['samples'], 'bridgeCommitCoverage': b['identityCommitCoverageComplete'],
                'noPostSql': e['httpSqlCounts'] == {'0': s['samples']}, 'jfrNoDataLoss': d['jfrDataLoss'] == 0,
                'noLeaseViolation': not any(v.values()), 'noSystemErrors': not s.get('systemErrors'),
                'noDispatchTableLockWaits': dispatch_locks == 0,
                'brokerLagSampled': d['backlog']['lagSamplesOk'] > 0, 'noOrphanReferences': not any(orphans.values()),
                'orphanReferences': orphans, 'queuesAfter': bridge.clear_backlog()}
    write(out / 'measurement-verification.json', verified)
    required = ['businessCorrectness', 'orderCommitCoverage', 'httpTimingCoverage', 'bridgeCommitCoverage', 'noPostSql', 'jfrNoDataLoss',
                'noLeaseViolation', 'noSystemErrors', 'noDispatchTableLockWaits', 'brokerLagSampled', 'noOrphanReferences']
    failed = [k for k in required if not verified[k]]
    if failed:
        raise RuntimeError('Stop before adding load: ' + ','.join(failed) + ' in ' + str(out))
    print('VERIFIED', name, 'samples', s['samples'], 'qps', s['windowStartQps'], 'windowTPS', s['windowCommittedTps'], 'fullTPS', s['fullCommittedTps'],
          'claims', d['claimSizeDistribution'], flush=True)
    return s


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--jar', required=True); p.add_argument('--private', required=True); p.add_argument('--public', required=True)
    p.add_argument('--classpath', required=True, help='runtime classpath file for the Broker lag sampler')
    p.add_argument('--backend-affinity', type=lambda x: int(x, 0), default=None, help='optional ProcessorAffinity mask for the backend JVM (e.g. 0x3FF)')
    p.add_argument('--profiles', nargs='*', default=['A', 'B', 'C']); p.add_argument('--smoke-profile', default='C'); p.add_argument('--skip-smoke', action='store_true')
    a = p.parse_args()
    jar = Path(a.jar).resolve(); private = Path(a.private); public = Path(a.public)
    if not jar.is_relative_to(ROOT):
        raise ValueError('JAR must be inside E:/content workspace')
    private.mkdir(parents=True, exist_ok=False); public.mkdir(parents=True, exist_ok=False)
    guard = ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
    pids = {}
    try:
        write(private / 'jar.json', {'path': str(jar), 'sha256': hashlib.file_digest(jar.open('rb'), 'sha256').hexdigest()})
        tiers = ([('smoke', a.smoke_profile, True)] if not a.skip_smoke else []) + [(x, x, False) for x in a.profiles]
        for name, profile, smoke in tiers:
            if profile not in PROFILES:
                raise ValueError('Only authorized dispatch profiles ' + '/'.join(PROFILES))
            gate(public / (name + '-gate-start.json'), name + '-start')
            pid = start_backend(jar, profile, private / ('backend-' + name)); pids[name] = pid
            if a.backend_affinity is not None:
                observed = int(bridge.powershell(f"$p=Get-Process -Id {pid}; $p.ProcessorAffinity={a.backend_affinity}; [int64]$p.ProcessorAffinity"))
                write(public / (name + '-backend-affinity.json'), {'atMs': pc.epoch_ms(), 'pid': pid, 'requested': hex(a.backend_affinity), 'observed': hex(observed),
                                                                  'cpus': [i for i in range(64) if observed >> i & 1], 'dockerVm': 'not pinned'})
                if observed != a.backend_affinity: raise RuntimeError('Backend affinity not applied')
            measurement(private, public, name, profile, jar, pid, a.classpath, smoke)
            gate(public / (name + '-memory-end.json'), name + '-end', memory_only=True)
        write(public / 'sequence-complete.json', {'atMs': pc.epoch_ms(), 'tiers': [t[0] for t in tiers], 'backendPids': pids, 'sameJar': str(jar),
                                                  'sha256': hashlib.file_digest(jar.open('rb'), 'sha256').hexdigest()})
    except Exception as error:
        write(public / 'sequence-failure.json', {'atMs': pc.epoch_ms(), 'error': type(error).__name__, 'detail': str(error), 'backendPids': pids, 'nextProfileStarted': False})
        raise
    finally:
        if guard:
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000)


if __name__ == '__main__':
    main()
