"""Offline MQ dispatch metrics for one measured tier (A serial / B,C batch). Never infers missing evidence.

Inputs in the tier directory: summary.json, config.json, server-events.tsv (+ .dispatch.tsv), observations.ndjson,
broker-lag.ndjson. Output: dispatch-batch-metrics.json.

Scopes:
- dispatch SQL/commit counts: SalePhase events under dispatch kinds (serial "dispatch"; batch "dispatch-claim",
  "dispatch-send", "dispatch-confirm"). Window = statement/commit start in [window start, end); full = whole recording.
- claim batch distribution: batch = SaleDispatchBatch.claimed (claims >0) plus empty claims (claim traces without a
  batch event); serial = one claim per "dispatch" trace, size 1 when it sent, 0 when the claim came back empty.
- consumer settle transaction: "settle" SaleRequest roots (duration) and their jdbc.commit phases.
- lock waits: discrete 2s samples of performance_schema.data_lock_waits by table; not a conflict count.
- backlog: Stream lag/pending (Redis), MQ pending = dispatch PENDING+SENDING for the formal campaign, PROCESSING,
  Broker consumer backlog = sum(brokerOffset-consumerOffset) of the consumer group (all campaigns on the topic).
"""
import collections, csv, json, sys
from pathlib import Path

BATCH_KINDS = {'dispatch-claim', 'dispatch-send', 'dispatch-confirm'}
ALL_KINDS = {'dispatch'} | BATCH_KINDS


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    import math
    return s[max(0, math.ceil(len(s) * p) - 1)]


def dist(values):
    return {'count': len(values), 'p50': pct(values, .5), 'p95': pct(values, .95), 'p99': pct(values, .99),
            'max': max(values) if values else None}


def ndjson(path):
    if not path.exists():
        return []
    out = []
    for line in path.read_text(encoding='utf8').splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    return out


def num(rows, i=0, j=0):
    try:
        return int(float(rows[i][j]))
    except (TypeError, ValueError, IndexError, KeyError):
        return None


def analyze(directory):
    p = Path(directory)
    summary = json.loads((p / 'summary.json').read_text(encoding='utf8'))
    config = json.loads((p / 'config.json').read_text(encoding='utf8'))
    w0, w1 = summary['window']['startEpochMs'], summary['window']['endEpochMs']
    profile = config.get('dispatchProfile')
    batch = bool(profile) and not str(profile).startswith('A')

    traces = collections.defaultdict(list)
    roots = {}
    loss = 0
    with (p / 'server-events.tsv').open(encoding='utf8') as f:
        for e in csv.DictReader(f, delimiter='\t'):
            if e['event'] == 'DATA_LOSS':
                loss += 1
                continue
            if e['kind'] not in ALL_KINDS and e['kind'] != 'settle':
                continue
            if e['event'] == 'request':
                roots[e['traceId']] = e
            elif e['event'] == 'phase':
                traces[e['traceId']].append(e)

    def scope_counts(window):
        sql = collections.Counter(); commits = collections.Counter(); rollbacks = collections.Counter()
        phase_ms = collections.defaultdict(list)
        for tid, phases in traces.items():
            root = roots.get(tid)
            kind = (root or phases[0])['kind']
            if kind not in ALL_KINDS:
                continue
            for e in phases:
                start, end = int(e['startMs']), int(e['endMs'])
                if window and not (w0 <= start < w1):
                    continue
                ph = e['phase']
                if ph.startswith('sql.'):
                    sql[kind + ' ' + ph] += 1
                elif ph == 'jdbc.commit':
                    commits[kind] += 1
                elif ph == 'jdbc.rollback':
                    rollbacks[kind] += 1
                if ph in ('dispatch.claim', 'dispatch.confirm', 'dispatch.retry', 'mq.send', 'jdbc.commit'):
                    phase_ms[kind + ' ' + ph].append(end - start)
        return {'sqlTotal': sum(sql.values()), 'sqlByStatement': dict(sorted(sql.items())), 'commitsByKind': dict(commits),
                'commitsTotal': sum(commits.values()), 'rollbacksByKind': dict(rollbacks),
                'phaseMs': {k: dist(v) for k, v in sorted(phase_ms.items())}}

    # Claim batch distribution and lease use.
    sizes = collections.Counter(); lease_use = []; outcome = collections.Counter(); batches = []
    if batch:
        path = Path(str(p / 'server-events.tsv') + '.dispatch.tsv')
        rows = list(csv.DictReader(path.open(encoding='utf8'), delimiter='\t')) if path.exists() else []
        for r in rows:
            claimed = int(r['claimed']); sizes[claimed] += 1
            total = int(r['claimMillis']) + int(r['sendMillis']) + int(r['outcomeMillis'])
            lease_use.append(total)
            for k in ('sent', 'failed', 'budgetSkipped', 'confirmMissed', 'retryMissed'):
                outcome[k] += int(r[k])
            outcome['outcomeNotPersisted'] += r.get('outcomePersisted') == 'false'
            batches.append({'atMs': int(r['endMs']), 'requested': int(r['requested']), 'idleSenders': int(r['idleSenders']),
                            'claimed': claimed, 'leaseUseMs': total})
        claim_traces = [t for t, ph in traces.items() if (roots.get(t) or ph[0])['kind'] == 'dispatch-claim']
        sizes[0] += max(0, len(claim_traces) - len(rows))
        evidence = 'SaleDispatchBatch events' if path.exists() else 'missing dispatch.tsv'
    else:
        for tid, phases in traces.items():
            if (roots.get(tid) or phases[0])['kind'] != 'dispatch':
                continue
            sent = any(e['phase'] == 'mq.send' for e in phases)
            sizes[1 if sent else 0] += 1
            if sent:
                root = roots.get(tid)
                if root:
                    lease_use.append(int(root['endMs']) - int(root['startMs']))
                outcome['sent' if any(e['phase'] == 'dispatch.confirm' for e in phases) else 'failed'] += 1
        evidence = 'serial dispatch traces'

    # Consumer settle transactions.
    settle_ms = []; settle_window = 0; settle_commit_ms = []
    for tid, root in roots.items():
        if root['kind'] != 'settle':
            continue
        start, end = int(root['startMs']), int(root['endMs'])
        settle_ms.append(end - start)
        settle_window += w0 <= end < w1
        settle_commit_ms += [int(e['endMs']) - int(e['startMs']) for e in traces.get(tid, []) if e['phase'] == 'jdbc.commit']

    # Lock waits and backlog series.
    obs = ndjson(p / 'observations.ndjson')
    locks = collections.Counter(); lock_samples = 0; lock_peak = collections.Counter()
    series = []
    for o in obs:
        db = o.get('database') or {}
        waits = db.get('lockWaitsByTable') or []
        if isinstance(waits, list) and waits:
            lock_samples += 1
            for name, n in waits:
                locks[name] += int(n); lock_peak[name] = max(lock_peak[name], int(n))
        disp = db.get('dispatch'); camp = db.get('campaign'); redis = o.get('redis') or {}
        pending, sending = num(disp, 0, 1), num(disp, 0, 2)
        series.append({'atMs': o['startEpochMs'], 'streamLag': redis.get('globalStreamLag'), 'streamPending': redis.get('globalStreamPending'),
                       'mqPending': None if pending is None or sending is None else pending + sending, 'sending': sending,
                       'processing': num(camp, 0, 1), 'receipts': num(db.get('receipts'), 0, 0),
                       'freeMiB': round((o.get('hostMemory') or {}).get('availablePhysicalBytes', 0) / 1048576) or None})
    lag = [x for x in ndjson(p / 'broker-lag.ndjson')]
    lag_ok = [x for x in lag if x.get('ok')]

    def peak(key, rows):
        vals = [(r[key], r['atMs']) for r in rows if r.get(key) is not None]
        return {'value': max(vals)[0], 'atMs': max(vals)[1]} if vals else None

    def near(rows, at, key):
        best = min((r for r in rows if r.get(key) is not None), key=lambda r: abs(r['atMs'] - at), default=None)
        return None if best is None else {'value': best[key], 'offsetMs': best['atMs'] - at}

    lag_rows = [{'atMs': x['startEpochMs'], 'brokerBacklog': x['diffTotal'], 'pulledNotCommitted': x.get('pulledNotCommitted')} for x in lag_ok]
    edges = {}
    for name, at in (('windowStart', w0), ('windowEnd', w1)):
        edges[name] = {k: near(series, at, k) for k in ('streamLag', 'mqPending', 'processing')}
        edges[name]['brokerBacklog'] = near(lag_rows, at, 'brokerBacklog')
    drained = [r['atMs'] for r in series if r['mqPending'] == 0 and r['processing'] == 0 and r['atMs'] > w1]
    result = {
        'profile': profile, 'batchMode': batch, 'claimEvidence': evidence, 'jfrDataLoss': loss,
        'claimSizeDistribution': {str(k): v for k, v in sorted(sizes.items())},
        'claimsWithRows': sum(v for k, v in sizes.items() if k > 0), 'emptyClaims': sizes.get(0, 0),
        'outcomes': dict(outcome), 'leaseUseMs': dist(lease_use),
        'leaseViolations': {'budgetSkipped': outcome.get('budgetSkipped', 0), 'confirmMissed': outcome.get('confirmMissed', 0),
                            'retryMissed': outcome.get('retryMissed', 0), 'outcomeNotPersisted': outcome.get('outcomeNotPersisted', 0),
                            'leaseUseOver25000Ms': sum(x > 25000 for x in lease_use)},
        'window': scope_counts(True), 'full': scope_counts(False),
        'settle': {'transactionsMs': dist(settle_ms), 'commitMs': dist(settle_commit_ms), 'committedInWindow': settle_window},
        'lockWaits': {'samplesWithWaits': lock_samples, 'samples': len(obs), 'observedWaitsByTable': dict(locks), 'peakPerSampleByTable': dict(lock_peak),
                      'note': 'Discrete samples; repeated observations of one wait are counted again. Absence is not proof of no waits.'},
        'backlog': {'peaks': {k: peak(k, series) for k in ('streamLag', 'mqPending', 'processing')} | {'brokerBacklog': peak('brokerBacklog', lag_rows)},
                    'edges': edges, 'lagSamples': len(lag), 'lagSamplesOk': len(lag_ok),
                    'lagIntervalMs': (config.get('brokerLagSampler') or {}).get('intervalMs'),
                    'firstObservedDrainAfterWindowMs': (min(drained) - w1) if drained else None,
                    'brokerBacklogScope': 'Whole consumer group on the topic (all campaigns); only this workload runs during the tier.'},
        'series': series, 'brokerLagSeries': lag_rows, 'batches': batches,
    }
    (p / 'dispatch-batch-metrics.json').write_text(json.dumps(result, indent=1), encoding='utf8')
    brief = {k: result[k] for k in ('profile', 'claimSizeDistribution', 'outcomes', 'leaseViolations', 'leaseUseMs')}
    brief['peaks'] = result['backlog']['peaks']; brief['windowSql'] = result['window']['sqlTotal']; brief['windowCommits'] = result['window']['commitsTotal']
    print(json.dumps(brief, indent=1))
    return result


if __name__ == '__main__':
    analyze(sys.argv[1])
