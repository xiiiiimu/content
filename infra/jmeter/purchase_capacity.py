"""Scoped purchase evidence. No response bodies, auth data or SQL text in artifacts."""
import argparse
import collections
import concurrent.futures
import csv
import json
import math
from pathlib import Path
import subprocess
import time
import ctypes
from urllib.request import ProxyHandler, build_opener


METRICS = (
    'sale.http.queue.depth',
    'tomcat.threads.current', 'tomcat.threads.busy', 'tomcat.threads.config.max',
    'tomcat.connections.current', 'tomcat.connections.config.max',
    'jvm.memory.used?tag=area:heap', 'jvm.memory.max?tag=area:heap',
    'jvm.gc.pause', 'process.cpu.usage', 'jvm.threads.live',
    'jvm.threads.states?tag=state:runnable', 'jvm.threads.states?tag=state:blocked',
    'jvm.threads.states?tag=state:waiting', 'jvm.threads.states?tag=state:timed-waiting',
    'hikaricp.connections.active', 'hikaricp.connections.pending',
    'hikaricp.connections', 'hikaricp.connections.max', 'hikaricp.connections.min', 'hikaricp.connections.idle',
    'hikaricp.connections.timeout', 'hikaricp.connections.acquire',
)


def epoch_ms():
    return time.time_ns() // 1_000_000


def windows_memory():
    try:
        class MemoryStatus(ctypes.Structure):
            _fields_=[('length',ctypes.c_uint32),('load',ctypes.c_uint32)]+[(n,ctypes.c_uint64) for n in ('totalPhys','availPhys','totalPage','availPage','totalVirtual','availVirtual','extended')]
        status=MemoryStatus();status.length=ctypes.sizeof(status)
        if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):raise OSError('GlobalMemoryStatusEx failed')
        return dict(observedAtMs=epoch_ms(),memoryLoadPercent=status.load,totalPhysicalBytes=status.totalPhys,availablePhysicalBytes=status.availPhys,commitLimitBytes=status.totalPage,availableCommitBytes=status.availPage)
    except (OSError,AttributeError) as error:
        return {'unavailable':type(error).__name__}


def mysql(query):
    result = subprocess.run(
        ['docker', 'exec', '-i', 'content-mysql-1', 'sh', '-c',
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB content'],
        input=query, capture_output=True, text=True, timeout=8, check=True)
    return [line.split('\t') for line in result.stdout.splitlines()]


def http_metric(path):
    opener = build_opener(ProxyHandler({}))
    try:
        with opener.open('http://127.0.0.1:28080' + path, timeout=2) as response:
            obj = json.load(response)
        return ({m['statistic']: m['value'] for m in obj['measurements']}
                if 'measurements' in obj else obj)
    except (OSError, ValueError) as error:
        return {'unavailable': type(error).__name__}


def windows_tcp():
    try:
        result = subprocess.run(['netstat.exe', '-ano', '-p', 'tcp'], capture_output=True,
                                text=True, errors='replace', timeout=5, check=True)
        states = collections.Counter()
        client_states = collections.Counter()
        for line in result.stdout.splitlines():
            parts = line.split()
            if len(parts) == 5 and parts[0] == 'TCP':
                if parts[1].endswith(':28080'): states[parts[3]] += 1
                if parts[2].endswith(':28080'): client_states[parts[3]] += 1
        return {'localPort': 28080, 'states': dict(states), 'clientStatesToPort': dict(client_states)}
    except (subprocess.SubprocessError, OSError) as error:
        return {'unavailable': type(error).__name__}


def database_sample(campaign):
    queries = {
          'campaign': f'''SELECT COUNT(*), COALESCE(SUM(r.state='PROCESSING'),0),
              COALESCE(SUM(r.state='SUCCEEDED'),0), COALESCE(SUM(CASE WHEN d.reservation_id IS NULL
              THEN r.mq_sent_at_ms IS NOT NULL ELSE d.state='SENT' END),0)
              FROM material_purchase_requests r LEFT JOIN material_sale_dispatch d USING(reservation_id)
              WHERE r.campaign_id={campaign}''',
          'cacheJobs': '''SELECT COUNT(*) FROM material_sale_cache_jobs WHERE revision>applied_revision''',
        'receipts': f'''SELECT COUNT(*) FROM material_sale_consume_receipts c
            JOIN material_purchase_requests r USING(reservation_id) WHERE r.campaign_id={campaign}''',
          'dispatch': f'''SELECT COUNT(*),COALESCE(SUM(d.state='PENDING'),0),
              COALESCE(SUM(d.state='SENDING'),0),COALESCE(SUM(d.state='SENT'),0),COALESCE(MAX(d.attempts),0)
              FROM material_sale_dispatch d JOIN material_purchase_requests r USING(reservation_id)
              WHERE r.campaign_id={campaign}''',
        'lockWaitsByTable': '''SELECT l.OBJECT_NAME, COUNT(*) FROM performance_schema.data_lock_waits w
            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
            WHERE l.OBJECT_SCHEMA='content' AND l.OBJECT_NAME LIKE 'material_%'
            GROUP BY l.OBJECT_NAME''',
        'campaignForUpdateDigest': '''SELECT COUNT(*),COALESCE(SUM(COUNT_STAR),0),
            COALESCE(SUM(SUM_TIMER_WAIT),0) FROM performance_schema.events_statements_summary_by_digest
            WHERE SCHEMA_NAME='content' AND DIGEST_TEXT LIKE '%material_sale_campaigns%FOR UPDATE%' ''',
        'appAccountTransactions': '''SELECT COALESCE(SUM(COUNT_STAR),0), COALESCE(SUM(SUM_TIMER_WAIT),0)
            FROM performance_schema.events_transactions_summary_by_account_by_event_name
            WHERE USER='content' ''',
    }
    # Bound observer overhead to one MySQL process per tick. A denied query is
    # reported as unavailable, separately from an empty lock-wait sample.
    sql = '\n'.join(f"SELECT '@{key}'; {query}; SELECT '@end';" for key, query in queries.items())
    try:
        raw = mysql(sql)
    except (subprocess.SubprocessError, OSError) as error:
        return {'unavailable': type(error).__name__}
    sample = {}
    current = None
    for row in raw:
        if row[0].startswith('@'):
            current = row[0][1:]
            if current != 'end':
                sample[current] = []
        elif current in sample:
            sample[current].append(row)
    return sample


def stream_sample(campaign):
    lua="""local control='sale:{entry-v2}:control';local stream='sale:{entry-v2}:stream'
if redis.call('TYPE',control).ok~='hash' then return cjson.encode({unavailable='v2-not-initialized'}) end
local pending=redis.call('XPENDING',stream,'sale-persist-v2')[1];local lag=nil
for _,g in ipairs(redis.call('XINFO','GROUPS',stream)) do local n=nil;local l=nil;for i=1,#g,2 do if g[i]=='name' then n=g[i+1] elseif g[i]=='lag' then l=g[i+1] end end;if n=='sale-persist-v2' then lag=l end end
return cjson.encode({campaignStock=tonumber(redis.call('HGET','sale:{entry-v2}:state','c:'..ARGV[1]..':stock')),ready=redis.call('HGET',control,'ready'),globalStreamPending=pending,globalStreamLag=lag,globalStreamLength=redis.call('XLEN',stream)})"""
    try:
        result=subprocess.run(['docker','exec','content-redis-1','redis-cli','--raw','EVAL',lua,'0',str(campaign)],capture_output=True,text=True,timeout=5,check=True)
        return json.loads(result.stdout)
    except (subprocess.SubprocessError,OSError,ValueError) as error:
        return {'unavailable':type(error).__name__}


def observe(directory, campaign, interval):
    stop = directory / 'observer.stop'
    with (directory / 'observations.ndjson').open('x', encoding='utf8') as output:
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as workers:
            while not stop.exists():
                tick = time.monotonic()
                sample = {'campaignId': campaign, 'startEpochMs': epoch_ms(),'hostMemory':windows_memory()}
                paths = ['/api/sale-debug/inflight'] + ['/actuator/metrics/' + name for name in METRICS]
                tcp = workers.submit(windows_tcp)
                db = workers.submit(database_sample, campaign)
                stream = workers.submit(stream_sample, campaign)
                sample['http'] = dict(zip(paths, workers.map(http_metric, paths)))
                sample['windows'] = tcp.result()
                sample['database'] = db.result()
                sample['redis'] = stream.result()
                sample['endEpochMs'] = epoch_ms()
                output.write(json.dumps(sample) + '\n')
                output.flush()
                until = tick + interval
                while time.monotonic() < until and not stop.exists():
                    time.sleep(0.15)


def percentile(values, p):
    return sorted(values)[math.ceil(len(values) * p) - 1] if values else None


def summarize(rows, threads, window, drained_at, campaign, durable, submitted_keys, sold, rate_limit=0):
    start, end = window['startEpochMs'], window['endEpochMs']
    if not start < end <= drained_at:
        raise ValueError('Invalid observation window')
    seconds = collections.defaultdict(collections.Counter)
    codes = collections.Counter()
    seen = collections.Counter()
    mismatches = collections.Counter()
    elapsed = []
    connections = 0
    for row in rows:
        ts, latency = int(row['timeStamp']), int(row['elapsed'])
        code = row['responseCode']
        rid = row['reservationId']
        elapsed.append(latency)
        codes[code] += 1
        if row['label'] != 'POST-purchase':
            mismatches['nonPurchaseSample'] += 1
        seconds[ts // 1000]['starts'] += 1
        if 'Connect' in code or 'Connection refused' in row['responseMessage']:
            connections += 1
            seconds[(ts + latency) // 1000]['connectFailures'] += 1
        if code.isdigit():
            seconds[(ts + latency) // 1000]['httpResponses'] += 1
        if not start <= ts <= ts + latency <= end:
            mismatches['sampleOutsideWindow'] += 1
        if row['effClientKey'] != submitted_keys.get(row['userId']):
            mismatches['unexpectedClientKey'] += 1
        if code == '200':
            seen[rid] += 1
            if not rid or rid not in durable:
                mismatches['http200WithoutScopedReservation'] += 1
            elif durable[rid][:2] != (row['userId'], row['effClientKey']):
                mismatches['reservationOwnershipMismatch'] += 1

    ledger = []
    paid_times, settlement_ms = [], []
    for rid, (user, key, state, order_id, payment, paid, created, entitlement, receipt) in durable.items():
        if seen[rid] != 1:
            mismatches['durableWithoutExactlyOneHttp200'] += 1
        if state != 'SUCCEEDED' or order_id == 'NULL' or payment != 'PAID' or entitlement != '1' or receipt != 'SUCCEEDED':
            mismatches['invalidSettlementChain'] += 1
        if payment == 'PAID' and paid != 'NULL':
            paid_times.append(int(paid))
            settlement_ms.append(int(paid) - int(created))
        ledger.append({'reservationId': rid, 'orderId': order_id, 'requestState': state,
                       'simulatedPayment': payment, 'activePurchaseEntitlements': int(entitlement),
                       'receipt': receipt, 'paidAtMs': None if paid == 'NULL' else int(paid)})
    if sold != len(paid_times):
        mismatches['soldCountMismatch'] += 1
    processing = sum(v[2] == 'PROCESSING' for v in durable.values())
    formal = (end - start) / 1000
    full = (drained_at - start) / 1000
    emitted = sorted(int(r['timeStamp']) for r in rows)
    left = rolling_peak = 0
    for right, ts in enumerate(emitted):
        while ts - emitted[left] >= 1000:
            left += 1
        rolling_peak = max(rolling_peak, right - left + 1)
    emit_span = (emitted[-1] - emitted[0]) / 1000 if emitted else 0
    sample_span = ((max(int(r['timeStamp']) + int(r['elapsed']) for r in rows) - emitted[0]) / 1000
                   if rows else 0)
    last_end = max((int(r['timeStamp']) + int(r['elapsed']) for r in rows), default=end)
    drain_after_emission = max(0, drained_at - last_end) / 1000
    # The first accounted purchase serializes cold HTTP setup. Use the remaining
    # interarrival intervals for steady-rate certification, while still enforcing
    # the rolling cap across all starts and counting all purchases in reconciliation.
    steady_span = (emitted[-1] - emitted[1]) / 1000 if len(emitted) > 2 else 0
    steady_rate = (len(emitted) - 2) / steady_span if steady_span > 0 else None
    unique_threads = len({r['threadName'] for r in rows})
    failures = []
    for condition, message in (
        (len(rows) != threads or unique_threads != threads, 'sample/thread count mismatch'),
        (connections > 0, 'connection failures'),
        (any(code.isdigit() and int(code) >= 500 for code in codes), 'HTTP 5xx'),
        (codes['200'] != threads, 'in-stock first-buy HTTP acceptance incomplete'),
        (bool(mismatches), 'reconciliation mismatch'),
        (processing > 0, 'settlement not drained'),
        (rate_limit > 0 and rolling_peak > rate_limit, 'measured starts exceeded the rolling one-second target'),
        (rate_limit > 0 and (steady_rate is None or steady_rate < .9 * rate_limit), 'steady arrival rate below 90 percent of target'),
        (drain_after_emission > 30, 'drain was not confirmed within 30 seconds of final HTTP completion'),
    ):
        if condition:
            failures.append(message)
    return dict(campaignId=campaign, samples=len(rows), uniqueSampleThreads=unique_threads,
                codes=dict(codes), connectFailures=connections, formalSec=formal, fullSec=full,
                emitSpanSec=emit_span, emitRate=len(rows) / emit_span if emit_span else None,
                rateLimit=rate_limit, maxStartsPerRollingSecond=rolling_peak,
                steadyEmitRate=steady_rate, drainAfterLastHttpSec=drain_after_emission,
                sampleSpanSec=sample_span,
                requestQps=len(rows) / sample_span if sample_span else None,
                http200Qps=codes['200'] / sample_span if sample_span else None,
                formalRequestQps=len(rows) / formal, formalHttp200Qps=codes['200'] / formal,
                paid=len(paid_times), paidInWindow=sum(start <= t < end for t in paid_times),
                settleWindowTps=sum(start <= t < end for t in paid_times) / formal,
                settleFullTps=len(paid_times) / full, processingLeft=processing,
                p95Ms=percentile(elapsed, .95), p99Ms=percentile(elapsed, .99),
                settlementP95Ms=percentile(settlement_ms, .95),
                perSecond={str(k): v for k, v in sorted(seconds.items())},
                mismatchCounts=dict(mismatches), reconciliation=ledger, failures=failures)


def reconcile(args):
    path = args.directory
    with (path / 'results.jtl').open(encoding='utf-8-sig', newline='') as source:
        rows = list(csv.DictReader(source))
    with args.buyers.open(encoding='utf-8-sig', newline='') as source:
        keys = {r['userId']: r['clientKey'] for r in csv.DictReader(source)}
    c = args.campaign
    result = mysql(f'''START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
        SELECT sold_count FROM material_sale_campaigns WHERE id={c};
        SELECT r.reservation_id,r.user_id,r.client_key,r.state,r.order_id,
          o.state,o.paid_at_ms,r.created_at_ms,
          (SELECT COUNT(*) FROM material_entitlements e WHERE e.source_kind='PURCHASE'
           AND e.source_id=o.id AND e.account_id=r.user_id AND e.post_id=r.post_id
           AND e.state='ACTIVE'), c.decision
        FROM material_purchase_requests r LEFT JOIN material_orders o
          ON o.id=r.order_id AND o.reservation_id=r.reservation_id AND o.campaign_id=r.campaign_id
          AND o.user_id=r.user_id AND o.post_id=r.post_id AND o.merchant_id=r.merchant_id
          AND o.price_cents=r.price_cents
        LEFT JOIN material_sale_consume_receipts c ON c.reservation_id=r.reservation_id
        WHERE r.campaign_id={c} ORDER BY r.reservation_id; COMMIT;''')
    window = json.loads((path / 'window.json').read_text(encoding='utf-8-sig'))
    report = summarize(rows, args.threads, window['formalWindow'], args.drained_at, c,
                       {r[0]: tuple(r[1:]) for r in result[1:]}, keys, int(result[0][0]),
                       window['jmeter'].get('purchaseStartsPerSecond', 0))
    with (path / 'capacity-report.json').open('x', encoding='utf8') as out:
        json.dump(report, out, indent=2)
    print(json.dumps({k: report[k] for k in ('samples', 'paid', 'requestQps', 'settleFullTps', 'failures')}))
    return int(bool(report['failures']))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('observe', 'reconcile'))
    parser.add_argument('directory', type=Path)
    parser.add_argument('campaign', type=int)
    parser.add_argument('--interval', type=float, default=5)
    parser.add_argument('--threads', type=int)
    parser.add_argument('--buyers', type=Path)
    parser.add_argument('--drained-at', type=int)
    args = parser.parse_args()
    if args.campaign <= 0 or args.interval < 2:
        parser.error('Positive campaign and observation interval >= 2 seconds required')
    if args.mode == 'observe':
        observe(args.directory, args.campaign, args.interval)
        return 0
    if not all((args.threads, args.buyers, args.drained_at)):
        parser.error('reconcile requires threads, buyers and drained-at')
    return reconcile(args)


if __name__ == '__main__':
    raise SystemExit(main())
