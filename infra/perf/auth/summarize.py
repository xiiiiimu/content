"""Summarize one login phase directory: k6 latency, status split and CPU per run -> <phase_dir>/summary.md."""
import json, os, sys, statistics

phase_dir = sys.argv[1]
rows = []
for name in ['l50', 'l100', 'l200']:
    p = os.path.join(phase_dir, name + '.json')
    if not os.path.exists(p):
        continue
    m = json.load(open(p))['metrics']
    c = lambda k: int(m.get(k, {}).get('count', 0))
    d = m['http_req_duration']
    reqs = m['http_reqs']
    cpu = []
    sysc = []
    cp = os.path.join(phase_dir, 'cpu-' + name + '.tsv')
    if os.path.exists(cp):
        for line in open(cp):
            parts = line.rstrip('\n').split('\t')
            try:
                cpu.append(float(parts[1])); sysc.append(float(parts[2]))
            except (ValueError, IndexError):
                pass
    fmt = lambda xs, f: ('%.1f%%' % (f(xs) * 100)) if xs else '缺证据'
    rows.append([name, '%.1f' % reqs['rate'], c('login_200'), c('login_429'), c('login_4xx_other'), c('login_5xx'),
                 c('dropped_iterations'), '%.1f' % d['med'], '%.1f' % d['p(95)'], '%.1f' % d['p(99)'],
                 fmt(cpu, statistics.mean), fmt(cpu, max), fmt(sysc, statistics.mean)])
hdr = ['run', 'achieved rps', '200', '429', 'other 4xx', '5xx', 'dropped', 'p50 ms', 'p95 ms', 'p99 ms',
       'process CPU avg', 'process CPU max', 'system CPU avg']
out = ['| ' + ' | '.join(hdr) + ' |', '|' + '---|' * len(hdr)]
out += ['| ' + ' | '.join(str(x) for x in r) + ' |' for r in rows]
open(os.path.join(phase_dir, 'summary.md'), 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('\n'.join(out))
