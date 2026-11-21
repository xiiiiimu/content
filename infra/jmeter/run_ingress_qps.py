"""Purchase-entry POST QPS ceiling, open model (k6 constant-arrival-rate), one level at a time.

Path "soldout": a small-stock campaign is sold out first (the sellers' orders settle), then the same pool of load
buyers repeats POST /api/sale-campaigns/{id}/purchase with a new clientKey per request. The sold-out branch of the
reserve script is read-only (no reservation, no Stream entry, no SQL), so repeats are valid.

Path "accept": stock is ample (each campaign has stock = number of buyers). Every level gets its own fresh campaigns
(own material/post); iteration i goes to campaign i % n with buyer floor(i / n), so no (buyer, material) pair repeats.
After each level: drain (no PROCESSING request, no HELD order, Stream pending/lag 0, dispatch and cache jobs done),
then per-request reconciliation of every k6 record against MySQL and Redis; drain time is recorded separately and is
not part of the level's QPS. A level may run with settle held (--hold-stages, existing fault hook
/api/sale-debug/hold-settle): the bridge still persists and dispatches, settle waits; released after the level.

Per level: target rate, preAllocated = maxVUs = ceil(rate x expected RT x 3) (floor 10, cap --vu-cap). k6 runs pinned
to --k6-affinity, backend JVM pinned to --backend-affinity (Docker VM unpinned). No client JFR, no listeners.
Validity: k6 not CPU-saturated (p95 of k6 cores < 90 % of its pinned CPUs), else "发压机受限" (excluded).
Dropped iterations with an unsaturated injector and rising latency (P99 > 2x the lowest earlier P99) are attributed to
server/host saturation, not to the injector.
Knee: P99 > 200 ms, or error rate > 0.1 %, or actual rate < 95 %.
Sustainable entry QPS = highest target of a valid level with no knee condition, with every lower valid level clean.

  python infra/jmeter/run_ingress_qps.py --path soldout --jar <v26 jar> --private target/<new> --public docs/perf-evidence/commerce/jmeter/<new>
  python infra/jmeter/run_ingress_qps.py --path accept --levels 100,200,400 --seconds 20 --hold-stages 200 --jar ... --private ... --public ...
"""
import argparse, base64, concurrent.futures, csv, json, math, os, re, subprocess, time
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler
from urllib.error import HTTPError

ROOT = Path.cwd()
API = 'http://127.0.0.1:28080'
K6 = ROOT / 'tools/k6-v0.55.0/k6-v0.55.0-windows-amd64/k6.exe'
POOL = ROOT / 'target/v23-perf-20261118-234151/formal-reuse.csv'
AUTHOR_META = ROOT / 'target/flash-5000-meta.txt'
LOGICAL_CPUS = 16


# ---------- pure helpers (unit-tested) ----------
def vus_for(rate, expected_rt_ms=50, factor=3, floor=10, cap=600):
    return max(floor, min(cap, math.ceil(rate * expected_rt_ms / 1000 * factor)))


def mask_cpus(mask):
    return [i for i in range(64) if mask >> i & 1]


def token_expiry_ms(token):
    head = token.split('.')[0]
    return int(json.loads(base64.urlsafe_b64decode(head + '=' * (-len(head) % 4)))['expiresAt'])


def dist(values):
    v = sorted(x for x in values if x is not None)
    if not v: return {'n': 0}
    pick = lambda p: v[min(len(v) - 1, max(0, math.ceil(p * len(v)) - 1))]
    return {'n': len(v), 'p50': pick(.5), 'p95': pick(.95), 'p99': pick(.99), 'max': v[-1], 'mean': round(sum(v) / len(v), 3)}


def peak_in_flight(intervals):
    """Max number of [begin, end) intervals open at once (an end at t closes before a begin at t)."""
    events = sorted([(e, -1) for b, e in intervals] + [(b, 1) for b, e in intervals])
    cur = peak = 0
    for _, d in events:
        cur += d; peak = max(peak, cur)
    return peak


def parse_access(lines, start_ms, end_ms, uri_pattern=r'^/api/sale-campaigns/\d+/purchase$'):
    """Tomcat pattern %{begin:msec}t,%{end:msec}t,%D,%s,%U,... -> in-app ms (from %D in microseconds) for requests
    that began inside [start_ms, end_ms). Also returns the begin/end wall difference to cross-check the unit, and the
    server-side peak of requests in flight."""
    app, wall, statuses, spans = [], [], {}, []
    for line in lines:
        f = line.strip().split(',')
        if len(f) < 5 or not f[0].isdigit(): continue
        b, e = int(f[0]), int(f[1])
        if not (start_ms <= b < end_ms) or not re.match(uri_pattern, f[4]): continue
        app.append(int(f[2]) / 1000); wall.append(e - b); statuses[f[3]] = statuses.get(f[3], 0) + 1; spans.append((b, max(e, b + 1)))
    return {'appMs': dist(app), 'beginEndMs': dist(wall), 'statuses': statuses, 'peakInFlight': peak_in_flight(spans)}


def k6_level(summary, target, seconds):
    m = summary['metrics']
    count = lambda k: int(m[k]['values']['count']) if k in m else 0
    reqs = count('http_reqs'); dropped = count('dropped_iterations')
    d = m['http_req_duration']['values']
    cats = {'accepted': count('biz_accepted'), 'soldOut': count('biz_sold_out'),
            'otherReject': sum(count(k) for k in ('rej_401', 'rej_403', 'rej_404', 'rej_409_other', 'rej_4xx_other', 'unexpected_2xx')),
            'http503': count('err_503'), 'http5xxOther': count('err_5xx'), 'timeout': count('err_timeout'), 'connFail': count('err_conn')}
    detail = {k: count(k) for k in ('rej_401', 'rej_403', 'rej_404', 'rej_409_other', 'rej_4xx_other', 'unexpected_2xx') if count(k)}
    it = m.get('iteration_duration', {}).get('values', {})
    return {'targetRate': target, 'requests': reqs, 'droppedIterations': dropped, 'pairOverflow': count('pair_overflow'),
            'actualRate': round(reqs / seconds, 1), 'actualPct': round(100 * reqs / (target * seconds), 2),
            'completedQps': {k: round(v / seconds, 1) for k, v in cats.items()}, 'counts': cats, 'otherRejectDetail': detail,
            'httpMs': {'p50': d.get('med'), 'p95': d.get('p(95)'), 'p99': d.get('p(99)'), 'max': d.get('max'), 'avg': d.get('avg')},
            'iterationMaxMs': it.get('max'), 'k6VusGaugeMax': m.get('vus', {}).get('values', {}).get('max')}


def errors(counts, expected):
    """Everything except the path's expected business outcomes counts as an error."""
    return sum(v for k, v in counts.items() if k not in expected)


def judge(level, k6_cores_p95, k6_cpu_count, expected=('soldOut',), saturation=0.9):
    """valid = injector not CPU-saturated (p95 of k6 cores >= 90 % of its pinned CPUs). A short arrival rate with an
    unsaturated injector means VUs were held by slow responses: a server-side knee, not an injector limit."""
    total = level['requests']
    err = errors(level['counts'], expected); rate = err / total if total else 1.0
    saturated = k6_cores_p95 is None or k6_cores_p95 >= saturation * k6_cpu_count
    v = {'errorRate': round(rate, 6), 'errors': err, 'k6CoresP95': k6_cores_p95, 'injectorSaturated': saturated, 'valid': not saturated,
         'knee': {'p99Over200ms': (level['httpMs']['p99'] or 0) > 200, 'errorRateOver0.1pct': rate > 0.001, 'arrivalUnder95pct': level['actualPct'] < 95}}
    if saturated: v['label'] = '发压机受限' if k6_cores_p95 is not None else '发压机 CPU 缺证据'
    if level.get('pairOverflow'): v['valid'] = False; v['label'] = '买家-素材对不足'
    v['clean'] = v['valid'] and not any(v['knee'].values())
    return v


def attribute_drops(levels, rise=2.0):
    """In run order: a level's dropped iterations are 'injector' when k6 was CPU-saturated, 'serverOrHostSaturation'
    when k6 was not saturated and P99 rose above rise x the lowest P99 of the earlier stages, else 'unattributed'."""
    best = None
    for lv in levels:
        p99 = lv['httpMs']['p99']
        if lv['droppedIterations']:
            if lv['verdict']['injectorSaturated']: why = 'injector'
            elif best is not None and p99 is not None and p99 > rise * best: why = 'serverOrHostSaturation'
            else: why = 'unattributed'
            lv['verdict']['dropReason'] = why; lv['verdict']['baselineP99'] = best
        if p99 is not None: best = p99 if best is None else min(best, p99)
    return levels


def sustainable(levels):
    best = None
    for lv in sorted(levels, key=lambda x: x['targetRate']):
        if not lv['verdict']['valid']:
            continue  # excluded from conclusions, does not end the scan
        if not lv['verdict']['clean']:
            break
        best = lv['targetRate']
    return best


def campaigns_needed(rate, seconds, buyers, slack=1.05, extra=50):
    """Fresh campaigns for one accept level so that every (buyer, campaign) pair is used at most once."""
    return math.ceil((rate * seconds * slack + extra) / buyers)


RECORD = re.compile(r'R,(\d+),(\d+),(\d+),(\d+),(\d+),([A-Z_]*),([^,]*),([\d.]+)')


def parse_records(lines):
    out = []
    for line in lines:
        m = RECORD.search(line)
        if m:
            i, c, u, st, ec, code, rid, ms = m.groups()
            out.append({'i': int(i), 'campaign': int(c), 'userId': int(u), 'status': int(st), 'errorCode': int(ec), 'code': code,
                        'reservationId': rid, 'ms': float(ms)})
    return out


def drain_pending(ledger, cache):
    return {'processing': sum(r[3] == 'PROCESSING' for r in ledger['requests']), 'heldOrders': ledger['orders'][2],
            'redisProcessing': cache.get('processing', 0) or 0, 'streamPending': cache.get('streamPending', 0) or 0,
            'streamLag': cache.get('streamLag') or 0, 'dispatchPending': cache.get('dispatchPending', 0) or 0,
            'cacheJobsPending': cache.get('cacheJobsPending', 0) or 0}


def reconcile(records, ledgers, caches, tag, k6_requests):
    """Per request: every 200 has its reservation in MySQL (same buyer, campaign, clientKey), SUCCEEDED, PAID order,
    one ACTIVE entitlement. Rows without a 200 record are 'ambiguous' when the request got no HTTP answer (timeout /
    connection failure), otherwise 'phantom'. Per campaign: sold = SUCCEEDED rows = PAID orders = entitlements, no HELD
    order, no duplicates, Redis stock = cap - sold, no Redis reservation left PROCESSING or invalid."""
    mismatch = {}
    bad = lambda k, n=1: mismatch.__setitem__(k, mismatch.get(k, 0) + n)
    rows = {int(c): {r[0]: r for r in l['requests']} for c, l in ledgers.items()}
    by_key = {(r['campaign'], f"{tag}-{r['i']}"): r for r in records}
    accepted = [r for r in records if r['status'] == 200]
    if len(records) != k6_requests: bad('recordCountVsK6', abs(len(records) - k6_requests))
    if len({r['i'] for r in records}) != len(records): bad('duplicateIteration')
    if len({(r['campaign'], r['userId']) for r in records}) != len(records): bad('repeatedBuyerMaterialPair')
    matched = 0; seen = set()
    for r in accepted:
        row = rows.get(r['campaign'], {}).get(r['reservationId'])
        if row is None: bad('acceptedWithoutRow'); continue
        seen.add(r['reservationId'])
        if int(row[1]) != r['userId'] or row[2] != f"{tag}-{r['i']}": bad('identityMismatch'); continue
        if row[3] != 'SUCCEEDED': bad('notSucceeded:' + row[3]); continue
        if row[5] != 'PAID' or int(row[7]) != 1: bad('orderOrEntitlement'); continue
        matched += 1
    ambiguous = 0
    for c, rs in rows.items():
        for rid, row in rs.items():
            if rid in seen: continue
            rec = by_key.get((c, row[2]))
            if rec is not None and rec['status'] == 0: ambiguous += 1
            else: bad('phantomRow')
    per = {}
    for c, l in ledgers.items():
        c = int(c); succeeded = sum(r[3] == 'SUCCEEDED' for r in l['requests']); cache = caches[c]
        per[c] = {'requests': len(l['requests']), 'succeeded': succeeded, 'sold': l['sold'], 'stockCap': l['stockCap'],
                  'orders': l['orders'], 'entitlements': l['entitlements'], 'redisStock': cache.get('stock')}
        if not (l['sold'] == succeeded == l['orders'][0] == l['orders'][1] == l['entitlements'][0]): bad('campaignCounts')
        if l['orders'][2] or l['orders'][3] or l['entitlements'][1]: bad('heldOrDuplicate')
        if cache.get('stock') != l['stockCap'] - l['sold']: bad('redisStock')
        if cache.get('processing') or cache.get('invalidRequests'): bad('redisReservation')
    return {'pass': not mismatch, 'records': len(records), 'accepted': len(accepted), 'matched': matched,
            'ambiguousNoResponseButPersisted': ambiguous, 'mismatches': mismatch, 'campaigns': per}


# ---------- environment ----------
def http_json(method, path, token=None, body=None, timeout=15):
    req = Request(API + path, method=method, data=None if body is None else json.dumps(body).encode(),
                  headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})})
    try:
        with build_opener(ProxyHandler({})).open(req, timeout=timeout) as r: return r.status, json.load(r)
    except HTTPError as e:
        return e.code, json.loads(e.read() or b'{}')


def login_all(uids, workers=4):
    import purchase_capacity as pc
    names = dict(pc.mysql('SELECT id,username FROM users WHERE id IN (' + ','.join(map(str, uids)) + ');'))
    if len(names) != len(set(uids)) or not all(re.fullmatch(r'jmb[A-Za-z0-9_]+', n) for n in names.values()):
        raise ValueError('Only existing isolated JMeter buyers can log in')

    def one(uid):
        s, r = http_json('POST', '/api/auth/login', body={'username': names[str(uid)], 'password': 'password-b1'})
        if s != 200 or int(r['userId']) != int(uid): raise ValueError('login failed ' + str(uid))
        return str(uid), r['token']
    with concurrent.futures.ThreadPoolExecutor(workers) as p: return dict(p.map(one, uids))


def write_buyers(path, tokens):
    with path.open('x', encoding='utf8', newline='') as f:
        w = csv.writer(f); w.writerow(['userId', 'token']); w.writerows(tokens.items())


def set_affinity(pid, mask):
    import run_bridge_comparison as bridge
    return int(bridge.powershell(f"$p=Get-Process -Id {int(pid)}; $p.ProcessorAffinity={int(mask)}; [int64]$p.ProcessorAffinity"))


def run_level(private, name, campaigns, buyers_csv, rate, seconds, vus, tag, affinity, record=False):
    summary = (private / f'k6-{name}.json').resolve()  # k6 resolves relative paths against the script directory
    env = {'CAMPAIGNS': ','.join(map(str, campaigns)), 'BUYERS_FILE': str(Path(buyers_csv).resolve()), 'RATE': str(rate), 'VUS': str(vus),
           'DURATION': f'{seconds}s', 'RUN_TAG': tag, 'SUMMARY': str(summary), 'API': API, 'K6_NO_USAGE_REPORT': 'true',
           'UNIQUE_PAIRS': '1' if record else '0', 'RECORD': '1' if record else '0'}
    console = (private / f'k6-{name}.records.log').resolve()
    # cmd's START needs a quoted empty title; build the string ourselves (list quoting would escape the quotes).
    cmd = (f'cmd /c start "" /B /WAIT /AFFINITY {affinity:X} "{K6}" run --quiet --no-usage-report --no-thresholds '
           + (f'--console-output "{console}" ' if record else '') + f'"{ROOT / "infra/jmeter/ingress-qps.k6.js"}"')
    log = (private / f'k6-{name}.log').open('x', encoding='utf8')
    started = int(time.time() * 1000)
    p = subprocess.Popen(cmd, env={**os.environ, **env}, stdout=log, stderr=subprocess.STDOUT)
    time.sleep(3)
    import run_bridge_comparison as bridge
    try:
        seen = bridge.powershell("Get-Process k6 -ErrorAction SilentlyContinue | ForEach-Object { '{0}:{1}' -f $_.Id,[int64]$_.ProcessorAffinity }; exit 0")
    except subprocess.CalledProcessError:
        seen = None
    rc = p.wait(timeout=seconds + 120); log.close()
    if not summary.exists(): raise RuntimeError('k6 wrote no summary; see ' + log.name)
    out = {'k6StartedMs': started, 'k6ExitMs': int(time.time() * 1000), 'rc': rc, 'k6AffinityObserved': seen,
           'summary': json.loads(summary.read_text(encoding='utf8'))}
    if record: out['recordsFile'] = console
    return out


def window_env(env_dir, start, end):
    from env_sample_report import lines, pct
    host = [r for r in lines(env_dir / 'host.ndjson') if start <= r['atMs'] < end]
    roles = {}
    for r in host:
        for k, v in (r.get('procCpuPctOfOneCore') or {}).items(): roles.setdefault(k, []).append(v / 100)
    dock = {}
    for r in lines(env_dir / 'docker-stats.ndjson'):
        if start <= r['atMs'] < end: dock.setdefault(r['Name'], []).append(pct(r.get('CPUPerc')))
    red = [r for r in lines(env_dir / 'redis.ndjson') if start <= r['atMs'] < end and 'scripts' in r]
    calls = [sum(v['calls'] for v in r['scripts'].values()) for r in red]
    usec = [sum(v['usec'] for v in r['scripts'].values()) for r in red]
    slow = {}
    for e in lines(env_dir / 'slowlog.ndjson'):
        if start <= e['epochS'] * 1000 < end:
            head = e['cmd'][0].upper() + (' ' + re.sub(r'\s+', ' ', e['cmd'][1])[:28] if e['cmd'][0].upper() in ('EVAL', 'EVALSHA') and len(e['cmd']) > 1 else '')
            slow.setdefault(head, []).append(e['micros'])
    return {'samples': len(host), 'cpuTotalPct': dist([r.get('cpuTotalPct') for r in host]), 'effectiveMHz': dist([r.get('effectiveMHz') for r in host]),
            'pctOfMaxFrequency': dist([r.get('pctOfMaxFrequency') for r in host]),
            'processCores': {k: dist(v) for k, v in roles.items()}, 'dockerCpuPct': {k: dist(v) for k, v in dock.items()},
            'redis': {'scriptCallsPerSec': dist(calls), 'scriptUsecPerCall': dist([round(u / c, 1) for u, c in zip(usec, calls) if c]),
                      'opsPerSec': dist([r.get('opsPerSec') for r in red]), 'usedMemoryBytes': dist([r.get('usedMemory') for r in red]),
                      'streamLength': dist([r.get('streamLength') for r in red]),
                      'groupLag': dist([(r.get('group') or {}).get('lag') for r in red]), 'groupPending': dist([(r.get('group') or {}).get('pending') for r in red])},
            'slowlog': {k: {'count': len(v), 'maxMicros': max(v), 'p50Micros': dist(v)['p50']} for k, v in slow.items()}}


def write_json(path, value):
    path.write_text(json.dumps(value, indent=1, ensure_ascii=False), encoding='utf8')


def start_environment(args, plan, private, public):
    import run_dispatch_comparison as rdc
    write_json(public / 'plan.json', plan)
    rdc.gate(public / 'gate-start.json', 'ingress-start')
    pid = rdc.start_backend(Path(args.jar).resolve(), 'B', private / 'backend')
    plan['backendPid'] = pid; plan['backendAffinityObserved'] = set_affinity(pid, args.backend_affinity)


def ensure_tokens(tokens, load, need_ms, private, sets, results, quiet_sec):
    """Ordinary re-login (TTL unchanged) when any load token would expire before need_ms; returns (csv, sets)."""
    import purchase_capacity as pc
    if min(token_expiry_ms(tokens[u]) for u in load) >= need_ms: return None, sets
    t = time.time(); tokens.update(login_all(load)); sets += 1
    path = private / f'load-buyers-{sets}.csv'; write_buyers(path, {u: tokens[u] for u in load})
    results.append({'stage': 'token-refresh', 'sec': round(time.time() - t, 1), 'atMs': pc.epoch_ms(), 'buyers': len(load)})
    time.sleep(quiet_sec)
    return path, sets


def analyze(results, env_dir, private, plan, expected):
    access = sorted((private / 'backend' / 'access').glob('access_log*'))
    access_lines = [l for f in access for l in f.read_text(encoding='utf8', errors='replace').splitlines()]
    out = []
    for r in results:
        if 'summary' not in r: out.append(r); continue
        lv = k6_level(r['summary'], r['rate'], r['seconds'])
        envw = window_env(env_dir, r['windowStartMs'], r['windowEndMs'])
        k6c = envw['processCores'].get('k6', {}).get('p95')
        lv.update({k: r[k] for k in ('stage', 'vus', 'windowStartMs', 'windowEndMs', 'k6AffinityObserved') if k in r})
        lv.update({'k6Rc': r['rc'], 'access': parse_access(access_lines, r['windowStartMs'], r['windowEndMs']), 'env': envw})
        for k in ('campaigns', 'hold', 'drain', 'reconciliation'):
            if k in r: lv[k] = r[k]
        if 'drain' in r and r['drain'].get('doneMs'):
            lv['drainEnv'] = window_env(env_dir, r['windowEndMs'], r['drain']['doneMs'])
        lv['verdict'] = judge(lv, k6c, len(plan['k6Cpus']), expected)
        out.append(lv)
    attribute_drops([x for x in out if 'verdict' in x])
    return out


def soldout(args, plan, private, public):
    import purchase_capacity as pc, run_bridge_comparison as bridge
    meta = bridge.seed_one(private / 'seed', 'iq' + time.strftime('%H%M%S'), args.stock, POOL, Path(AUTHOR_META.read_text().strip()))
    campaign = int(meta['campaignId']); plan['campaignId'] = campaign; plan['postId'] = meta['postId']
    with POOL.open(encoding='utf-8-sig') as f: pool = [r['userId'] for r in csv.DictReader(f)]
    sellers, load = pool[:args.stock], pool[args.stock:args.stock + args.buyers]
    t = time.time(); tokens = login_all(sellers + load); plan['loginSec'] = round(time.time() - t, 1)
    for uid in sellers:
        s, r = http_json('POST', f'/api/sale-campaigns/{campaign}/purchase', tokens[uid], {'clientKey': f'iq-sell-{campaign}-{uid}'})
        if s != 200: raise RuntimeError('sell-out purchase failed: ' + str(s) + ' ' + str(r.get('code')))
    deadline = time.time() + 240
    while time.time() < deadline:
        row = pc.mysql(f"SELECT SUM(state='SUCCEEDED'),SUM(state='PROCESSING') FROM material_purchase_requests WHERE campaign_id={campaign};")[0]
        if row[0] == str(args.stock) and row[1] == '0': break
        time.sleep(2)
    else:
        raise RuntimeError('sell-out did not settle')
    s, r = http_json('POST', f'/api/sale-campaigns/{campaign}/purchase', tokens[load[0]], {'clientKey': f'iq-probe-{campaign}'})
    if s != 409 or r.get('code') != 'SOLD_OUT': raise RuntimeError('probe is not SOLD_OUT: ' + str(s))
    plan['soldOutAtMs'] = pc.epoch_ms()
    buyers_csv = private / 'load-buyers-1.csv'; write_buyers(buyers_csv, {u: tokens[u] for u in load}); sets = 1
    write_json(public / 'plan.json', plan)
    env_dir = private / 'env'
    sampler = subprocess.Popen(['python', 'infra/jmeter/env_sampler.py', '--out', str(env_dir), '--max-seconds', '3600'])
    time.sleep(5)
    results = []
    try:
        stages = [('warmup', args.warmup_rate, args.warmup_seconds)] + [(f'L{r}', r, args.seconds) for r in plan['levels']]
        for name, rate, seconds in stages:
            fresh, sets = ensure_tokens(tokens, load, pc.epoch_ms() + (seconds + args.rest + 120) * 1000, private, sets, results, args.rest)
            buyers_csv = fresh or buyers_csv
            vus = vus_for(rate, args.expected_rt_ms, cap=args.vu_cap)
            r = run_level(private, name, [campaign], buyers_csv, rate, seconds, vus, f'iq{campaign}-{name}', args.k6_affinity)
            start = int(r['summary']['setup']['setupEndMs']); r['windowStartMs'] = start; r['windowEndMs'] = start + seconds * 1000
            r.update({'stage': name, 'rate': rate, 'seconds': seconds, 'vus': vus}); results.append(r)
            write_json(private / 'levels-raw.json', results)
            time.sleep(args.rest)
        time.sleep(15)  # buffered access log flush
    finally:
        (env_dir / 'STOP').touch(); sampler.wait(timeout=60)
    return analyze(results, env_dir, private, plan, ('soldOut',)), sets


def drain(campaigns, deadline_sec, private, name):
    from flash_acceptance import snapshot, redis_snapshot
    import purchase_capacity as pc
    history = []; limit = time.monotonic() + deadline_sec
    while True:
        ledgers = {c: snapshot(c) for c in campaigns}
        caches = {c: redis_snapshot(c, ledgers[c]['requests']) for c in campaigns}
        parts = {}
        for c in campaigns:
            for k, v in drain_pending(ledgers[c], caches[c]).items():
                parts[k] = max(parts.get(k, 0), v) if k in ('streamPending', 'streamLag', 'cacheJobsPending') else parts.get(k, 0) + v
        at = pc.epoch_ms(); history.append({'atMs': at, **parts})
        if not any(parts.values()): return {'doneMs': at, 'polls': len(history), 'timedOut': False}, ledgers, caches, history
        if time.monotonic() >= limit: return {'doneMs': None, 'polls': len(history), 'timedOut': True}, ledgers, caches, history
        write_json(private / f'drain-{name}-progress.json', history[-1])
        time.sleep(2)


def accept(args, plan, private, public):
    import purchase_capacity as pc, run_bridge_comparison as bridge
    with POOL.open(encoding='utf-8-sig') as f: load = [r['userId'] for r in csv.DictReader(f)][:args.buyers]
    if len(load) != args.buyers: raise RuntimeError('Insufficient isolated buyer pool')
    holds = {int(x) for x in args.hold_stages.split(',') if x}
    stages = [('warmup', args.warmup_rate, args.warmup_seconds, False)]
    for r in plan['levels']:
        stages.append((f'L{r}', r, args.seconds, False))
        if r in holds: stages.append((f'L{r}-hold', r, args.seconds, True))
    author = Path(AUTHOR_META.read_text().strip()); stamp = time.strftime('%H%M%S'); seeds = {}
    t = time.time()
    for name, rate, seconds, _ in stages:
        n = campaigns_needed(rate, seconds, len(load)); seeds[name] = []
        for k in range(n):
            meta = bridge.seed_one(private / f'seed-{name}-{k}', f'ia{stamp}{len(seeds)}{k}', len(load), POOL, author)
            seeds[name].append({'campaignId': int(meta['campaignId']), 'postId': int(meta['postId'])})
    plan['seedSec'] = round(time.time() - t, 1); plan['campaigns'] = seeds; write_json(public / 'plan.json', plan)
    t = time.time(); tokens = login_all(load); plan['loginSec'] = round(time.time() - t, 1)
    buyers_csv = private / 'load-buyers-1.csv'; write_buyers(buyers_csv, tokens); sets = 1
    write_json(public / 'plan.json', plan)
    env_dir = private / 'env'
    sampler = subprocess.Popen(['python', 'infra/jmeter/env_sampler.py', '--out', str(env_dir), '--max-seconds', '7200'])
    time.sleep(args.rest)
    results = []; stop = None
    try:
        for name, rate, seconds, hold in stages:
            fresh, sets = ensure_tokens(tokens, load, pc.epoch_ms() + (seconds + 90) * 1000, private, sets, results, args.rest)
            buyers_csv = fresh or buyers_csv
            campaigns = [c['campaignId'] for c in seeds[name]]
            vus = vus_for(rate, args.expected_rt_ms, cap=args.vu_cap); tag = f'ia{campaigns[0]}-{name}'
            hold_info = None
            if hold:
                s, r = http_json('POST', '/api/sale-debug/hold-settle', tokens[load[0]], {'arm': True})
                if s != 200 or not r.get('holdArmed'): raise RuntimeError('hold-settle did not arm: ' + str(s))
                hold_info = {'armedMs': pc.epoch_ms()}
            try:
                r = run_level(private, name, campaigns, buyers_csv, rate, seconds, vus, tag, args.k6_affinity, record=True)
            finally:
                if hold:
                    ids = ','.join(map(str, campaigns))
                    hold_info['succeededBeforeRelease'] = int(pc.mysql(f"SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id IN ({ids}) AND state='SUCCEEDED';")[0][0])
                    hold_info['processingBeforeRelease'] = int(pc.mysql(f"SELECT COUNT(*) FROM material_purchase_requests WHERE campaign_id IN ({ids}) AND state='PROCESSING';")[0][0])
                    s, rr = http_json('POST', '/api/sale-debug/hold-settle', tokens[load[0]], {'arm': False})
                    hold_info['releasedMs'] = pc.epoch_ms(); hold_info['releasedOk'] = s == 200 and rr.get('holdArmed') is False
            start = int(r['summary']['setup']['setupEndMs'])
            r.update({'stage': name, 'rate': rate, 'seconds': seconds, 'vus': vus, 'campaigns': seeds[name], 'windowStartMs': start,
                      'windowEndMs': start + seconds * 1000, 'tag': tag})
            if hold_info: r['hold'] = hold_info
            d, ledgers, caches, history = drain(campaigns, args.drain_timeout, private, name)
            d['drainSecFromWindowEnd'] = round((d['doneMs'] - r['windowEndMs']) / 1000, 1) if d['doneMs'] else None
            d['drainSecFromK6Exit'] = round((d['doneMs'] - r['k6ExitMs']) / 1000, 1) if d['doneMs'] else None
            write_json(private / f'drain-{name}.json', history)
            records = parse_records(Path(r.pop('recordsFile')).read_text(encoding='utf8', errors='replace').splitlines())
            rec = reconcile(records, ledgers, caches, tag, int(r['summary']['metrics']['http_reqs']['values']['count']))
            rec['reconciledMs'] = pc.epoch_ms(); r['drain'] = d; r['reconciliation'] = rec
            write_json(private / f'reconcile-{name}.json', {'drain': d, 'reconciliation': rec})
            results.append(r); write_json(private / 'levels-raw.json', results)
            if d['timedOut'] or not rec['pass']:
                stop = f'{name}: drain timed out' if d['timedOut'] else f'{name}: reconciliation failed {rec["mismatches"]}'
                break
            time.sleep(args.rest)
        time.sleep(15)  # buffered access log flush
    finally:
        (env_dir / 'STOP').touch(); sampler.wait(timeout=60)
    plan['stoppedEarly'] = stop
    return analyze(results, env_dir, private, plan, ('accepted',)), sets


def main():
    a = argparse.ArgumentParser()
    a.add_argument('--path', choices=['soldout', 'accept'], required=True)
    a.add_argument('--jar', required=True); a.add_argument('--private', required=True); a.add_argument('--public', required=True)
    a.add_argument('--levels'); a.add_argument('--seconds', type=int); a.add_argument('--rest', type=int, default=15)
    a.add_argument('--warmup-rate', type=int); a.add_argument('--warmup-seconds', type=int)
    a.add_argument('--stock', type=int, default=4, help='soldout: stock sold out before the ladder')
    a.add_argument('--buyers', type=int, help='soldout: load buyers (default 1000); accept: buyers per campaign (default 4000)')
    a.add_argument('--expected-rt-ms', type=int, default=50); a.add_argument('--vu-cap', type=int, default=600)
    a.add_argument('--hold-stages', default='', help='accept: comma list of rates that also run once with settle held')
    a.add_argument('--drain-timeout', type=int, default=1800)
    a.add_argument('--k6-affinity', type=lambda x: int(x, 0), default=0xFC00, help='logical CPUs 10-15')
    a.add_argument('--backend-affinity', type=lambda x: int(x, 0), default=0x03FF, help='logical CPUs 0-9')
    args = a.parse_args()
    defaults = {'soldout': ('200,500,1000,1500,2000,3000', 30, 100, 15, 1000), 'accept': ('100,200,400', 20, 50, 10, 4000)}[args.path]
    for k, v in zip(('levels', 'seconds', 'warmup_rate', 'warmup_seconds', 'buyers'), defaults):
        if getattr(args, k) is None: setattr(args, k, v)
    import purchase_capacity as pc
    private = Path(args.private); public = Path(args.public)
    private.mkdir(parents=True, exist_ok=False); public.mkdir(parents=True, exist_ok=False)
    levels = [int(x) for x in args.levels.split(',')]
    plan = {'path': args.path, 'levels': levels, 'seconds': args.seconds, 'rest': args.rest, 'warmup': [args.warmup_rate, args.warmup_seconds],
            'expectedRtMs': args.expected_rt_ms, 'vuCap': args.vu_cap, 'vus': {r: vus_for(r, args.expected_rt_ms, cap=args.vu_cap) for r in levels},
            'buyers': args.buyers, 'k6Affinity': hex(args.k6_affinity), 'k6Cpus': mask_cpus(args.k6_affinity),
            'backendAffinity': hex(args.backend_affinity), 'backendCpus': mask_cpus(args.backend_affinity), 'dockerVm': 'not pinned',
            'k6': str(K6.relative_to(ROOT)), 'tool': 'k6 v0.55.0 constant-arrival-rate'}
    if args.path == 'soldout': plan['stock'] = args.stock
    else: plan['holdStages'] = args.hold_stages; plan['drainTimeoutSec'] = args.drain_timeout
    start_environment(args, plan, private, public)
    out, sets = (soldout if args.path == 'soldout' else accept)(args, plan, private, public)
    formal = [x for x in out if x.get('stage', '').startswith('L') and not x.get('hold') and 'verdict' in x]
    report = {'plan': plan, 'levels': out, 'sustainableEntryQps': sustainable(formal), 'tokenSets': sets,
              'tokenSecretsArchived': False, 'finishedMs': pc.epoch_ms()}
    write_json(public / 'ingress-qps.json', report)
    print(json.dumps({'sustainable': report['sustainableEntryQps'], 'stoppedEarly': plan.get('stoppedEarly'),
                      'levels': [(x['stage'], x.get('actualRate'), x.get('httpMs', {}).get('p99'), x['verdict'].get('clean'),
                                  x.get('reconciliation', {}).get('pass')) for x in out if 'verdict' in x]}))
    if plan.get('stoppedEarly'): raise SystemExit(2)


if __name__ == '__main__':
    main()
