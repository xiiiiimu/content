"""Offline recompute of flash-sale QPS/TPS/latency with fixed definitions.

Reads only archived evidence (JTL + joined request/DB/JFR timelines); never touches
MySQL/Redis/MQ. Missing commit timestamps are reported as missing, never inferred.

Definitions (all times in ms, same Windows host clock for JMeter and backend JVM):
  window W         = [first sampler start, last sampler end] of POST-purchase samples
  completion QPS   = responses of a class whose end falls in W / W seconds
  normal business  = HTTP 200 accepted, 409 SOLD_OUT and other 4xx business codes, idempotent replay
  errors           = 5xx, timeouts (incl. ConnectTimeout), connect failures; reported separately
  order TPS        = unique orders whose settlement transaction commit returned inside W / W seconds
  full-run TPS     = orders / (last commit returned - first sampler start), includes confirmation wait
  e2e latency      = accepted: commit returned - sampler start; final reject: sampler end - start
Percentiles are nearest-rank, position ceil(N*p), no interpolation.
"""
import csv, json, math, sys, collections
from pathlib import Path


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return s[max(1, math.ceil(len(s) * p)) - 1]


def dist(values):
    return {'n': len(values), 'p50': pct(values, .50), 'p95': pct(values, .95), 'p99': pct(values, .99),
            'max': max(values) if values else None}


def classify(status, code, message=''):
    if status == '200':
        return 'accepted'
    if status == '401':
        return 'auth401'
    if status.isdigit() and status.startswith('5'):
        return 'http5xx'
    if 'Timeout' in status or 'Timeout' in message:
        return 'connectTimeout' if 'ConnectTimeout' in status + message else 'readTimeout'
    if not status.isdigit():
        return 'connectFailure'
    if code == 'SOLD_OUT':
        return 'soldOut'
    if code in ('IDEMPOTENT_REPLAY', 'REPLAY'):
        return 'replay'
    return 'otherBusinessReject'


NORMAL = ('accepted', 'soldOut', 'otherBusinessReject', 'replay')
ERRORS = ('http5xx', 'readTimeout', 'connectTimeout', 'connectFailure', 'auth401')


def jtl_check(path):
    rows = list(csv.DictReader(open(path, newline='', encoding='utf-8')))
    rows = [r for r in rows if r['label'] == 'POST-purchase']
    starts = [int(r['timeStamp']) for r in rows]
    ends = [int(r['timeStamp']) + int(r['elapsed']) for r in rows]
    return {'samples': len(rows), 'firstStartMs': min(starts), 'lastEndMs': max(ends),
            'statusCounts': dict(collections.Counter(r['responseCode'] for r in rows))}


def run(meta):
    t = json.load(open(meta['timelines'], encoding='utf-8'))
    jtl = jtl_check(meta['jtl'])
    t0 = min(x['startMs'] for x in t)
    t1 = max(x['endMs'] for x in t)
    assert jtl['samples'] == len(t) and jtl['firstStartMs'] == t0 and jtl['lastEndMs'] == t1, 'timeline/JTL mismatch'
    wsec = (t1 - t0) / 1000
    cls = collections.defaultdict(list)
    for x in t:
        cls[classify(x['status'], x['businessCode'])].append(x)
    counts = {k: len(cls.get(k, [])) for k in NORMAL + ERRORS}
    # per-second completion buckets aligned to t0
    buckets = collections.Counter()
    for k in NORMAL:
        for x in cls.get(k, []):
            buckets[(x['endMs'] - t0) // 1000] += 1
    starts = sorted(x['startMs'] for x in t)
    acc = cls.get('accepted', [])
    commits = [x.get('settlementCommitReturnedMs') for x in acc]
    has_commit = acc and all(c is not None for c in commits)
    paid = [x['database']['paidAtMs'] for x in acc if x.get('database')]
    out = {
        'run': meta['run'], 'campaign': meta['campaign'], 'build': meta['build'],
        'synchronyValid': meta['sync'], 'observationNote': meta['note'],
        'jtlCheck': jtl,
        'window': {'startMs': t0, 'endMs': t1, 'seconds': round(wsec, 3),
                   'samplerStartSpanMs': starts[-1] - starts[0]},
        'counts': counts,
        'completionQps': {
            'normalBusinessTotal': round(sum(counts[k] for k in NORMAL) / wsec, 3),
            **{k: round(counts[k] / wsec, 3) for k in NORMAL},
            'errorsTotal': round(sum(counts[k] for k in ERRORS) / wsec, 3),
            'peak1sNormalCompletions': max(buckets.values()) if buckets else 0,
        },
        'postLatencyMs': {k: dist([x['elapsed'] for x in cls[k]]) for k in cls},
        'postLatencyAllMs': dist([x['elapsed'] for x in t]),
    }
    if has_commit:
        inside = [c for c in commits if t0 <= c <= t1]
        first_c, last_c = min(commits), max(commits)
        e2e_acc = [c - x['startMs'] for c, x in zip(commits, acc)]
        e2e_rej = [x['endMs'] - x['startMs'] for k in ('soldOut', 'otherBusinessReject') for x in cls.get(k, [])]
        out['orderTps'] = {
            'source': 'JFR settlement transaction commit returned (order+stock+entitlement+request terminal+receipt in one tx)',
            'ordersTotal': len(acc), 'committedInsideWindow': len(inside),
            'windowTps': round(len(inside) / wsec, 3),
            'fullRunTpsInclConfirmationWait': round(len(acc) / ((last_c - t0) / 1000), 3),
            'settleSegment': {'firstCommitAfterT0Ms': round(first_c - t0), 'lastCommitAfterT0Ms': round(last_c - t0),
                              'lastCommitMinusWindowEndMs': round(last_c - t1),
                              'tpsBetweenFirstAndLastCommit': round((len(acc) - 1) / ((last_c - first_c) / 1000), 3) if last_c > first_c else None},
        }
        out['e2eLatencyMs'] = {
            'acceptedToCommitted': dist([round(v) for v in e2e_acc]),
            'finalReject': dist(e2e_rej),
            'allFinalOutcomes': dist([round(v) for v in e2e_acc] + e2e_rej),
            'excludedNoFinalOutcome': sum(counts[k] for k in ERRORS),
            'postEndToCommitMs': dist([round(c - x['endMs']) for c, x in zip(commits, acc)]),
            'note': 'accepted end = server-observed commit; JMeter purchase scenario does not poll, so this is not client-observed',
        }
    else:
        out['orderTps'] = {'source': 'MISSING: no per-order commit timestamp in this run',
                           'ordersTotal': len(acc), 'windowTps': None, 'fullRunTpsInclConfirmationWait': None,
                           'paidAtRecordPoint': {'note': 'paid_at is written before commit; record-point only',
                                                 'insideWindow': sum(1 for p in paid if t0 <= p <= t1),
                                                 'tpsToLastPaidAt': round(len(paid) / ((max(paid) - t0) / 1000), 3) if paid else None},
                           'drainConfirmedTps': round(len(acc) / ((meta['drainConfirmedAtMs'] - t0) / 1000), 3)}
        out['e2eLatencyMs'] = {'acceptedToCommitted': None, 'note': 'MISSING commit time; accepted e2e not computable',
                               'finalReject': dist([x['endMs'] - x['startMs'] for x in cls.get('soldOut', [])])}
    return out


if __name__ == '__main__':
    runs = json.load(open(sys.argv[1], encoding='utf-8'))
    result = [run(m) for m in runs]
    Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    for r in result:
        print(r['run'], r['window']['seconds'], r['counts'], r['completionQps']['normalBusinessTotal'],
              r['orderTps'].get('windowTps'), r['orderTps'].get('fullRunTpsInclConfirmationWait'))
