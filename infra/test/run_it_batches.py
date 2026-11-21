"""Full integration test run in small batches, so one Maven run never holds more than one or two IT contexts.

Modelled on infra/jmeter/run_dispatch_regression.py:
  - batch 0 runs the unit tests (surefire) only; every later batch runs one or two IT classes (failsafe) with the
    forked test JVM at -Xmx1g;
  - before every batch: no Testcontainers container may be running and free physical memory must be >= --min-free-mib
    (default 3072). If memory is short the runner waits (up to --mem-wait-s) and then stops; the threshold is never
    lowered;
  - after every batch: Testcontainers containers must exit within 120 s;
  - any failed batch, memory gate or leftover container stops the sequence. --from-batch resumes from a batch name.

Run from the checkout to test (e.g. a fresh worktree); Maven is taken from <main checkout>/tools.
  python infra/test/run_it_batches.py --out target/it-batches-<ts>
  python infra/test/run_it_batches.py --out target/it-batches-<ts> --classes RateLimitIT,AuthBcryptPoolIT   # subset
Exit code 0 only when every batch passed.
"""
import argparse, json, os, re, subprocess, time
from pathlib import Path

ROOT = Path.cwd()
JAVA_HOME = os.environ.get('CONTENT_JAVA_HOME', 'C:/Users/西木/.jdks/ms-17.0.17')
ARGLINE = '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Xmx1g'
# Not part of this repository's test suite (another session's uncommitted work); excluded if present.
EXCLUDE = {'AssistantEvalE2EIT'}


def main_checkout():
    p = subprocess.run(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], capture_output=True, text=True, check=True)
    return Path(p.stdout.strip()).parent


def mvn():
    for base in (ROOT, main_checkout()):
        m = base / 'tools/apache-maven-3.9.6/bin/mvn.cmd'
        if m.exists():
            return str(m)
    raise SystemExit('maven not found under tools/')


def it_classes():
    names = sorted(p.stem for p in (ROOT / 'src/test/java').rglob('*IT.java'))
    return [n for n in names if n not in EXCLUDE]


def batches(per_batch, only=None):
    its = it_classes()
    if only:
        unknown = [c for c in only if c not in its]
        if unknown:
            raise SystemExit('unknown IT classes: ' + ','.join(unknown))
        its = [c for c in its if c in only]
    out = [('b00-unit', None)]
    for i in range(0, len(its), per_batch):
        group = its[i:i + per_batch]
        out.append(('b%02d-%s' % (i // per_batch + 1, group[0][:-2]), ','.join(group)))
    return out


def containers():
    p = subprocess.run(['docker', 'ps', '--filter', 'label=org.testcontainers=true', '--format', '{{.ID}} {{.Image}}'],
                       capture_output=True, text=True, timeout=60)
    return [x for x in p.stdout.splitlines() if x.strip()]


def free_mib():
    p = subprocess.run(['pwsh', '-NoProfile', '-Command', '(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory'],
                       capture_output=True, text=True, timeout=60)
    return round(int(p.stdout.strip()) / 1024)


SUMMARY = re.compile(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)(?:, Time elapsed: [^ ]+ s)?(?: <<< [A-Z]+!)? -- in (\S+)')


def class_results(log_text):
    res = {}
    for m in SUMMARY.finditer(log_text):
        res[m.group(5).rsplit('.', 1)[-1]] = {'run': int(m.group(1)), 'failures': int(m.group(2)), 'errors': int(m.group(3)), 'skipped': int(m.group(4))}
    return res


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--out', required=True)
    a.add_argument('--from-batch', default=None)
    a.add_argument('--per-batch', type=int, default=2, choices=(1, 2))
    a.add_argument('--min-free-mib', type=int, default=3072)
    a.add_argument('--mem-wait-s', type=int, default=600)
    a.add_argument('--classes', default=None, help='comma-separated IT classes to run (unit batch always runs); default all')
    args = a.parse_args()
    out = Path(args.out); out.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, JAVA_HOME=JAVA_HOME, MAVEN_OPTS='-Xmx512m')
    env['PATH'] = JAVA_HOME + '/bin' + os.pathsep + env['PATH']
    plan = batches(args.per_batch, args.classes.split(',') if args.classes else None)
    (out / 'plan.json').write_text(json.dumps(plan, indent=1), encoding='utf8')
    head = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], capture_output=True, text=True).stdout.strip()
    status = []
    started = args.from_batch is None
    m = mvn()

    def save():
        (out / 'status.json').write_text(json.dumps({'head': head, 'batches': status}, indent=1), encoding='utf8')

    for name, filt in plan:
        started = started or name == args.from_batch
        if not started:
            continue
        rec = {'batch': name, 'itClasses': filt}
        status.append(rec)
        left = containers()
        if left:
            rec['result'] = 'STOP_CONTAINERS_BEFORE'; rec['containersLeft'] = left; save(); break
        free, waited = free_mib(), 0
        while free < args.min_free_mib and waited < args.mem_wait_s:
            time.sleep(15); waited += 15; free = free_mib()
        rec['freeMiBStart'] = free; rec['memWaitS'] = waited
        if free < args.min_free_mib:
            rec['result'] = 'STOP_LOW_MEMORY'; save(); break
        if filt is None:
            cmd = [m, '-B', '-ntp', '-Pintegration', 'verify', '-Dit.test=NoSuchIT', '-Dfailsafe.failIfNoSpecifiedTests=false']
        else:
            cmd = [m, '-B', '-ntp', '-Pintegration', 'verify', '-Dtest=ContentApplicationTest', '-Dit.test=' + filt,
                   '-Dsurefire.failIfNoSpecifiedTests=false']
        cmd.append('-DargLine=' + ARGLINE)
        # A batch that runs no IT (b00-unit) does not rewrite failsafe-summary.xml, so `verify` would re-read a stale
        # result from an earlier run. Move it into the evidence directory (kept, not deleted) before every batch.
        stale = ROOT / 'target/failsafe-reports/failsafe-summary.xml'
        if stale.exists():
            stale.replace(out / ('stale-failsafe-summary-before-' + name + '.xml'))
            rec['staleFailsafeSummaryMoved'] = True
        rec['command'] = cmd[1:]; rec['startMs'] = int(time.time() * 1000)
        save()
        with (out / (name + '.log')).open('w', encoding='utf8') as log:
            p = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, env=env, timeout=3600)
        rec['mvnRc'] = p.returncode; rec['endMs'] = int(time.time() * 1000)
        rec['classes'] = class_results((out / (name + '.log')).read_text(encoding='utf8', errors='replace'))
        if filt is not None:
            missing = [c for c in filt.split(',') if c not in rec['classes']]
            if missing:
                rec['missingClasses'] = missing
        left, waited = containers(), 0
        while left and waited < 120:
            time.sleep(5); waited += 5; left = containers()
        rec['containersLeft'] = left; rec['containerWaitS'] = waited; rec['freeMiBEnd'] = free_mib()
        if p.returncode != 0:
            rec['result'] = 'STOP_MVN_FAILED'
        elif rec.get('missingClasses'):
            rec['result'] = 'STOP_CLASSES_NOT_RUN'
        elif left:
            rec['result'] = 'STOP_CONTAINERS_LEFT'
        else:
            rec['result'] = 'PASS'
        save()
        print(name, rec['result'], 'rc', p.returncode, 'free', rec['freeMiBStart'], '->', rec['freeMiBEnd'],
              'secs', (rec['endMs'] - rec['startMs']) // 1000, flush=True)
        if rec['result'] != 'PASS':
            break
    save()
    names = [n for n, _ in plan]
    first = names.index(args.from_batch) if args.from_batch else 0
    ok = [r['batch'] for r in status] == names[first:] and all(r.get('result') == 'PASS' for r in status)
    tot = {'run': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for r in status:
        for c in r.get('classes', {}).values():
            for k in tot:
                tot[k] += c[k]
    print('TOTAL', json.dumps(tot), flush=True)
    print('IT BATCHES', 'COMPLETE' if ok else 'STOPPED', flush=True)
    raise SystemExit(0 if ok else 1)


if __name__ == '__main__':
    main()
