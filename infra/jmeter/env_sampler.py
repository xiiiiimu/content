"""Per-second environment sampler for load runs (read-only on the system under test).

Streams, each line stamped with epoch ms:
  docker-stats.ndjson  `docker stats` stream (CPU %, memory, net/block IO per container)
  host.ndjson          Windows _Total CPU %, % Processor Performance, nominal and effective MHz, % of max frequency,
                       and CPU (% of one core) of backend JVM, JMeter JVM(s), other java, vmmem, docker backend, this
                       sampler's pwsh + python; roles come from command lines, which are never written out
  redis.ndjson         INFO commandstats deltas for eval/evalsha/evalsha_ro/fcall, instantaneous ops, used_memory,
                       sale Stream length and consumer-group lag/pending, every second
  slowlog.ndjson       new SLOWLOG entries (deduplicated by id; arguments truncated, values are business keys only)
Runs until <out>/STOP exists or --max-seconds passes. Never resets SLOWLOG or any Redis state.

  python infra/jmeter/env_sampler.py --out <new dir> [--max-seconds 3600]
"""
import argparse, json, os, re, socket, subprocess, sys, threading, time
from pathlib import Path

REDIS = ('127.0.0.1', 26379)
STREAM = 'sale:{entry-v2}:stream'
GROUP = 'sale-persist-v2'
PWSH_LOOP = r'''
$ErrorActionPreference='SilentlyContinue'
$stop=$args[0];$self=$PID;$parent=[int]$args[1];$n=0;$roles=@{}
while(-not (Test-Path $stop)){
 $t0=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
 if($n % 5 -eq 0){
  $roles=@{}
  foreach($p in (Get-CimInstance Win32_Process -Filter "Name='java.exe'")){
   $c=[string]$p.CommandLine
   $r= if($c -match 'ApacheJMeter'){'jmeter'} elseif($c -match 'content-v\d+-[0-9a-f]+\.jar'){'backend'} else {'javaOther'}
   $roles[[int]$p.ProcessId]=$r
  }
 }
 $c=Get-CimInstance Win32_PerfFormattedData_Counters_ProcessorInformation -Filter "Name='_Total'"
 $agg=@{}
 foreach($p in (Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -Filter "Name like 'java%' or Name like 'vmmem%' or Name like 'com.docker.backend%' or Name like 'pwsh%' or Name like 'python%' or Name like 'WmiPrvSE%' or Name like 'k6%'")){
  $id=[int]$p.IDProcess
  $role= if($roles.ContainsKey($id)){$roles[$id]} elseif($id -eq $self){'samplerPwsh'} elseif($id -eq $parent){'samplerPython'} elseif($p.Name -like 'vmmem*'){'vmmem'} elseif($p.Name -like 'com.docker*'){'dockerBackend'} elseif($p.Name -like 'WmiPrvSE*'){'wmiPrvSE'} elseif($p.Name -like 'k6*'){'k6'} else {$null}
  if($role){$agg[$role]=[double]($agg[$role])+[double]$p.PercentProcessorTime}
 }
 $perf=[double]$c.PercentProcessorPerformance;$nom=[double]$c.ProcessorFrequency
 [pscustomobject]@{atMs=$t0;doneMs=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds();cpuTotalPct=[double]$c.PercentProcessorTime;processorPerformancePct=$perf;
  nominalMHz=$nom;effectiveMHz=[math]::Round($nom*$perf/100);pctOfMaxFrequency=[double]$c.PercentofMaximumFrequency;procCpuPctOfOneCore=$agg} | ConvertTo-Json -Compress
 $n++
 $left=1000-([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()-$t0); if($left -gt 0){Start-Sleep -Milliseconds $left}
}
'''


def now():
    return time.time_ns() // 1_000_000


class Resp:
    def __init__(self):
        self.s = socket.create_connection(REDIS, timeout=5); self.f = self.s.makefile('rb')

    def cmd(self, *args):
        out = b'*%d\r\n' % len(args) + b''.join(b'$%d\r\n%s\r\n' % (len(a), a) for a in (x.encode() if isinstance(x, str) else x for x in args))
        self.s.sendall(out); return self.read()

    def read(self):
        line = self.f.readline()
        t, rest = line[:1], line[1:-2]
        if t == b'+': return rest.decode()
        if t == b'-': raise RuntimeError(rest.decode())
        if t == b':': return int(rest)
        if t == b'$':
            n = int(rest)
            if n < 0: return None
            data = self.f.read(n + 2)[:-2]; return data.decode('utf8', 'replace')
        if t == b'*':
            n = int(rest); return None if n < 0 else [self.read() for _ in range(n)]
        raise RuntimeError('bad RESP ' + repr(line))


def commandstats(info):
    out = {}
    for m in re.finditer(r'cmdstat_(\w+):calls=(\d+),usec=(\d+)', info):
        if m.group(1) in ('eval', 'evalsha', 'evalsha_ro', 'eval_ro', 'fcall'):
            out[m.group(1)] = (int(m.group(2)), int(m.group(3)))
    return out


def info_field(info, name):
    m = re.search(r'^' + re.escape(name) + r':(\S+)', info, re.M)
    return None if not m else (int(m.group(1)) if m.group(1).isdigit() else m.group(1))


def group_stats(groups, name=GROUP):
    """XINFO GROUPS reply (list of flat key/value lists) -> {'lag','pending'} of the named group, or None."""
    for g in groups or []:
        d = dict(zip(g[0::2], g[1::2]))
        if d.get('name') == name:
            return {'lag': d.get('lag'), 'pending': d.get('pending')}
    return None


def redis_loop(out, stop):
    r = Resp(); seen = set(); prev = None; first = True
    with (out / 'redis.ndjson').open('a', encoding='utf8') as rf, (out / 'slowlog.ndjson').open('a', encoding='utf8') as sf:
        while not stop.is_set():
            t0 = now()
            try:
                cs = commandstats(r.cmd('INFO', 'commandstats')); ops = re.search(r'instantaneous_ops_per_sec:(\d+)', r.cmd('INFO', 'stats'))
                rec = {'atMs': t0, 'opsPerSec': int(ops.group(1)) if ops else None,
                       'usedMemory': info_field(r.cmd('INFO', 'memory'), 'used_memory'), 'streamLength': r.cmd('XLEN', STREAM),
                       'group': group_stats(r.cmd('XINFO', 'GROUPS', STREAM))}
                if prev is not None:
                    rec['scripts'] = {k: {'calls': v[0] - prev.get(k, (0, 0))[0], 'usec': v[1] - prev.get(k, (0, 0))[1]} for k, v in cs.items()}
                prev = cs
                for e in r.cmd('SLOWLOG', 'GET', '128') or []:
                    if e[0] in seen: continue
                    seen.add(e[0])
                    if first: continue  # entries older than the sampler: id recorded, not written
                    args = [str(a)[:60] for a in e[3][:4]]
                    sf.write(json.dumps({'id': e[0], 'epochS': e[1], 'micros': e[2], 'cmd': args}) + '\n')
                first = False
                rec['doneMs'] = now(); rf.write(json.dumps(rec) + '\n'); rf.flush(); sf.flush()
            except Exception as error:  # keep sampling; record the gap
                rf.write(json.dumps({'atMs': t0, 'error': type(error).__name__}) + '\n'); rf.flush()
                try: r = Resp()
                except Exception: pass
            stop.wait(max(0, 1 - (now() - t0) / 1000))


def docker_loop(out, stop):
    p = subprocess.Popen(['docker', 'stats', '--format', '{{json .}}'], stdout=subprocess.PIPE, text=True, encoding='utf8', errors='replace')
    with (out / 'docker-stats.ndjson').open('a', encoding='utf8') as f:
        for line in p.stdout:
            if stop.is_set(): break
            line = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', line).strip()
            if not line.startswith('{'): continue
            d = json.loads(line); d['atMs'] = now(); f.write(json.dumps(d) + '\n'); f.flush()
    p.kill()


def main():
    a = argparse.ArgumentParser(); a.add_argument('--out', required=True); a.add_argument('--max-seconds', type=int, default=3600)
    args = a.parse_args(); out = Path(args.out); out.mkdir(parents=True, exist_ok=False)
    stopfile = out / 'STOP'; stop = threading.Event()
    (out / 'sampler-meta.json').write_text(json.dumps({'startMs': now(), 'pid': os.getpid(), 'intervalMs': 1000, 'redis': '%s:%d' % REDIS}), encoding='utf8')
    script = out / 'host-loop.ps1'; script.write_text(PWSH_LOOP, encoding='utf8')
    host = open(out / 'host.ndjson', 'a', encoding='utf8')
    ps = subprocess.Popen(['pwsh', '-NoProfile', '-File', str(script), str(stopfile), str(os.getpid())], stdout=host, stderr=subprocess.DEVNULL)
    threads = [threading.Thread(target=f, args=(out, stop), daemon=True) for f in (redis_loop, docker_loop)]
    for t in threads: t.start()
    deadline = time.time() + args.max_seconds
    while not stopfile.exists() and time.time() < deadline:
        time.sleep(1)
    stop.set(); stopfile.touch(exist_ok=True)
    try: ps.wait(timeout=15)
    except subprocess.TimeoutExpired: ps.kill()
    meta = json.loads((out / 'sampler-meta.json').read_text()); meta['stopMs'] = now()
    (out / 'sampler-meta.json').write_text(json.dumps(meta), encoding='utf8')


if __name__ == '__main__':
    main()
