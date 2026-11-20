"""Summarise env_sampler.py output over a measurement's HTTP window and drain (read-only; writes only --out).

  python infra/jmeter/env_sample_report.py --samples <env dir> --measurement <tier dir> --out <new json>
Per phase (window = HTTP formal window, drain = window end -> last sample):
  host: CPU %, effective MHz, % of max frequency (p5/p50/p95/max), process CPU in cores (% of one core / 100)
  docker: CPU % per container (100 % = one core inside the VM)
  redis: server-side script time per call (eval + evalsha) per second, script calls/s, ops/s
  slowlog: entries inside the phase by command, with max micros
"""
import argparse, collections, json, re
from pathlib import Path


def q(v, p):
    v = sorted(x for x in v if x is not None)
    return None if not v else v[min(len(v) - 1, int(p * (len(v) - 1) + 0.5))]


def dist(v):
    v = [x for x in v if x is not None]
    return {'n': len(v), 'p5': q(v, .05), 'p50': q(v, .5), 'p95': q(v, .95), 'max': max(v) if v else None,
            'mean': round(sum(v) / len(v), 2) if v else None}


def lines(p):
    return [json.loads(l) for l in Path(p).read_text(encoding='utf-8-sig').splitlines() if l.strip().startswith('{')]


def pct(s):
    try: return float(str(s).rstrip('%'))
    except ValueError: return None


def phase_of(t, w0, w1):
    return 'window' if w0 <= t < w1 else ('drain' if t >= w1 else 'before')


def main():
    a = argparse.ArgumentParser(); a.add_argument('--samples', required=True); a.add_argument('--measurement', required=True); a.add_argument('--out', required=True)
    args = a.parse_args(); out = Path(args.out)
    if out.exists(): raise SystemExit('refusing to overwrite ' + str(out))
    s = json.loads((Path(args.measurement) / 'summary.json').read_text(encoding='utf-8-sig'))
    w0, w1 = s['window']['startEpochMs'], s['window']['endEpochMs']
    env = Path(args.samples); res = {'windowStartMs': w0, 'windowEndMs': w1}
    host = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in lines(env / 'host.ndjson'):
        ph = phase_of(r['atMs'], w0, w1)
        for k in ('cpuTotalPct', 'effectiveMHz', 'pctOfMaxFrequency', 'processorPerformancePct'):
            host[ph][k].append(r.get(k))
        for role, v in (r.get('procCpuPctOfOneCore') or {}).items():
            host[ph]['cores.' + role].append(round(v / 100, 3))
    res['host'] = {ph: {k: dist(v) for k, v in d.items()} for ph, d in host.items()}
    dock = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in lines(env / 'docker-stats.ndjson'):
        dock[phase_of(r['atMs'], w0, w1)][r['Name']].append(pct(r.get('CPUPerc')))
    res['dockerCpuPct'] = {ph: {k: dist(v) for k, v in d.items()} for ph, d in dock.items()}
    red = collections.defaultdict(lambda: collections.defaultdict(list))
    for r in lines(env / 'redis.ndjson'):
        if 'scripts' not in r: continue
        ph = phase_of(r['atMs'], w0, w1)
        calls = sum(v['calls'] for v in r['scripts'].values()); usec = sum(v['usec'] for v in r['scripts'].values())
        red[ph]['scriptCallsPerSec'].append(calls); red[ph]['scriptUsecTotalPerSec'].append(usec)
        red[ph]['scriptUsecPerCall'].append(round(usec / calls, 1) if calls else None); red[ph]['opsPerSec'].append(r.get('opsPerSec'))
    res['redis'] = {ph: {k: dist(v) for k, v in d.items()} for ph, d in red.items()}
    slow = collections.defaultdict(lambda: collections.defaultdict(list))
    for e in lines(env / 'slowlog.ndjson'):
        head = e['cmd'][0].upper() + (' ' + re.sub(r'\s+', ' ', e['cmd'][1])[:32] if e['cmd'][0].upper() in ('EVAL', 'EVALSHA') and len(e['cmd']) > 1 else '')
        slow[phase_of(e['epochS'] * 1000, w0, w1)][head].append(e['micros'])
    res['slowlog'] = {ph: {k: {'count': len(v), 'maxMicros': max(v), 'p50Micros': q(v, .5)} for k, v in d.items()} for ph, d in slow.items()}
    out.write_text(json.dumps(res, indent=1, ensure_ascii=False), encoding='utf8')
    w = res['host'].get('window', {})
    print('window cpu', w.get('cpuTotalPct'), '\n effMHz', w.get('effectiveMHz'), '\n redis', res['redis'].get('window'))


if __name__ == '__main__':
    main()
