"""Batched regression for the MQ dispatch change: one or two IT classes per Maven run, failsafe JVM -Xmx1g.

Before every batch: full resource gate (no Testcontainers, >=3072 MiB free, Broker/NameServer probes). After every
batch: wait for Testcontainers to exit (fail if they do not within 120 s) and record free memory. Any failed batch,
gate failure or leftover container stops the sequence; thresholds are never lowered. Logs go to a new directory.

  python infra/jmeter/run_dispatch_regression.py --out target/mq-dispatch-regression-<ts>
"""
import argparse, json, os, subprocess, time
from pathlib import Path

ROOT = Path.cwd()
MVN = str(ROOT / 'tools/apache-maven-3.9.6/bin/mvn.cmd')
JAVA_HOME = 'C:/Users/西木/.jdks/ms-17.0.17'
BATCHES = [
    ('b1-dispatch-batch', 'CommerceDispatchBatchIT#readyTasks*+successfulAcks*+batch*,MySqlMigrationIT'),
    ('b2-dispatch-real-mq', 'CommerceDispatchBatchRealMqIT#realBatch*'),
    ('b3-ingress-revision', 'CommerceIngressRevisionIT'),
    ('b4-real-mq', 'CommerceRealMqIT'),
    ('b5-purchase', 'MaterialCommercePurchaseIT'),
    ('b6-refund-blacklist', 'MaterialCommerceRefundBlacklistIT'),
    ('b7-campaign-lock', 'CommerceCampaignLockIT#lock*'),
    ('b8-visibility-offline', 'VisibilityAndOfflineIT'),
]


def containers():
    p = subprocess.run(['docker', 'ps', '--filter', 'label=org.testcontainers=true', '--format', '{{.ID}} {{.Image}}'],
                       capture_output=True, text=True, timeout=60)
    return [x for x in p.stdout.splitlines() if x.strip()]


def free_mib():
    p = subprocess.run(['pwsh', '-NoProfile', '-Command', '(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory'],
                       capture_output=True, text=True, timeout=60)
    return round(int(p.stdout.strip()) / 1024)


def main():
    a = argparse.ArgumentParser(); a.add_argument('--out', required=True); a.add_argument('--from-batch', default=None)
    args = a.parse_args()
    out = Path(args.out); out.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, JAVA_HOME=JAVA_HOME, CONTENT_REAL_MQ_IT='true', MAVEN_OPTS='-Xmx512m')
    env['PATH'] = JAVA_HOME + '/bin' + os.pathsep + env['PATH']
    status = []
    started = args.from_batch is None
    for name, filt in BATCHES:
        started = started or name == args.from_batch
        if not started:
            continue
        rec = {'batch': name, 'filter': filt}
        status.append(rec)
        g = subprocess.run(['python', 'infra/jmeter/mq_dispatch_gate.py', '--out', str(out / (name + '-gate.json')), '--label', name],
                           capture_output=True, text=True, timeout=300)
        rec['gateRc'] = g.returncode
        if g.returncode:
            rec['result'] = 'STOP_GATE_FAILED'; break
        cmd = [MVN, '-B', '-Pintegration', 'verify', '-Dtest=SaleLatencyTest','-Dit.test=' + filt, '-Dfailsafe.failIfNoSpecifiedTests=false',
               '-DargLine=--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Xmx1g']
        rec['command'] = cmd[1:]; rec['startMs'] = int(time.time() * 1000); rec['freeMiBStart'] = free_mib()
        (out / 'status.json').write_text(json.dumps(status, indent=1), encoding='utf8')
        with (out / (name + '.log')).open('w', encoding='utf8') as log:
            p = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, env=env, timeout=3600)
        rec['mvnRc'] = p.returncode; rec['endMs'] = int(time.time() * 1000); rec['freeMiBEnd'] = free_mib()
        reports = out / (name + '-failsafe-reports'); reports.mkdir()
        for f in (ROOT / 'target/failsafe-reports').glob('*.txt'):
            if f.stat().st_mtime * 1000 >= rec['startMs']:
                (reports / f.name).write_bytes(f.read_bytes())
        left = containers(); waited = 0
        while left and waited < 120:
            time.sleep(5); waited += 5; left = containers()
        rec['containersLeft'] = left; rec['containerWaitS'] = waited
        rec['result'] = 'PASS' if p.returncode == 0 and not left else ('STOP_CONTAINERS_LEFT' if p.returncode == 0 else 'STOP_MVN_FAILED')
        (out / 'status.json').write_text(json.dumps(status, indent=1), encoding='utf8')
        print(name, rec['result'], 'rc', p.returncode, 'free', rec['freeMiBStart'], '->', rec['freeMiBEnd'], flush=True)
        if rec['result'] != 'PASS':
            break
    (out / 'status.json').write_text(json.dumps(status, indent=1), encoding='utf8')
    ok = len(status) == len([b for b in BATCHES]) and all(r.get('result') == 'PASS' for r in status) or (
        args.from_batch and all(r.get('result') == 'PASS' for r in status))
    print('REGRESSION', 'COMPLETE' if ok else 'STOPPED', flush=True)
    raise SystemExit(0 if ok else 1)


if __name__ == '__main__':
    main()
