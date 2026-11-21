"""Average MySQL commits per settled order, split by pipeline component, from a measurement's server-events.tsv.

Each jdbc.commit / jdbc.rollback phase is attributed to the transaction whose SQL statements precede it in the same
trace (since the previous commit/rollback). Components:
  bridge         bridge-* traces and persist-batch (Redis Stream -> MySQL request rows), except cache sync
  dispatchClaim  dispatch-claim traces (claim transactions of the MQ dispatch table)
  dispatchConfirm dispatch-confirm traces (sent/failed outcome transactions)
  dispatchSerial dispatch traces of the serial profile A (claim + confirm in one trace)
  settle         settle-trace transactions that lock the purchase request (the order transaction, and the re-judge
                 transaction after a missed stock claim)
  cacheSync      settle-trace transactions that only apply REQUEST/OWNER cache jobs, plus bridge-cache-sync traces
Orders = settle-trace order transactions that committed (one per settled request in this workload: stock > requests).
Read-only on its inputs; writes only the --out file (must not exist).

  python infra/jmeter/commits_per_order.py <measurement-dir> --out <new json path>
"""
import argparse, collections, csv, json
from pathlib import Path

CACHE_MARKERS = ('SaleIngressMapper.job', 'SaleIngressMapper.applied')


def component(kind, statements):
    if kind == 'settle':
        if any(s.endswith('SaleMapper.lockRequest') for s in statements):
            return 'settle'
        if any(s.endswith(m) for s in statements for m in CACHE_MARKERS):
            return 'cacheSync'
        return 'settleOther'
    if kind == 'bridge-cache-sync':
        return 'cacheSync'
    if kind.startswith('bridge-') or kind == 'persist-batch':
        return 'bridge'
    if kind == 'dispatch-claim':
        return 'dispatchClaim'
    if kind == 'dispatch-confirm':
        return 'dispatchConfirm'
    if kind == 'dispatch':
        return 'dispatchSerial'  # profile A: one-row claim/send/confirm in one trace
    return 'other:' + kind


def analyse(directory):
    d = Path(directory)
    summary = json.loads((d / 'summary.json').read_text(encoding='utf-8-sig'))
    rows = list(csv.DictReader((d / 'server-events.tsv').open(encoding='utf8'), delimiter='\t'))
    kinds = {e['traceId']: e['kind'] for e in rows if e['event'] == 'request'}
    traces = collections.defaultdict(list)
    for e in rows:
        if e['event'] == 'phase':
            traces[e['traceId']].append(e)
    commits = collections.Counter(); rollbacks = collections.Counter(); bridge_cache = 0
    orders = 0; first = last = None
    for trace, phases in traces.items():
        kind = kinds.get(trace, phases[0]['kind'])
        statements = []
        for e in sorted(phases, key=lambda e: (int(e['startMs']), e['phase'])):
            p = e['phase']
            if p.startswith('sql.'):
                statements.append(p); continue
            if p not in ('jdbc.commit', 'jdbc.rollback'):
                continue
            c = component(kind, statements)
            t = int(e['startMs']); first = t if first is None else min(first, t); last = t if last is None else max(last, t)
            if p == 'jdbc.commit':
                commits[c] += 1
                if kind == 'bridge-cache-sync':
                    bridge_cache += 1
                if c == 'settle' and any(s.endswith('SaleMapper.insertOrder') for s in statements):
                    orders += 1
            else:
                rollbacks[c] += 1
            statements = []
    total = sum(commits.values())
    span = (last - first) / 1000 if first is not None and last > first else None
    per = {k: round(v / orders, 3) for k, v in sorted(commits.items())} if orders else {}
    return {'source': str(d), 'orders': orders, 'paidOrdersFormal': summary.get('paidOrders'),
            'commits': dict(sorted(commits.items())), 'rollbacks': dict(sorted(rollbacks.items())),
            'cacheSyncFromBridge': bridge_cache,
            'commitsPerOrder': per, 'commitsPerOrderTotal': round(total / orders, 3) if orders else None,
            'commitSpanSeconds': span, 'commitsPerSecondOverSpan': round(total / span, 1) if span else None,
            'note': 'orders = committed settle transactions containing insertOrder (formal + warm-up orders in the recording)'}


def main():
    a = argparse.ArgumentParser(); a.add_argument('dirs', nargs='+'); a.add_argument('--out', required=True)
    args = a.parse_args()
    out = Path(args.out)
    if out.exists():
        raise SystemExit('refusing to overwrite ' + str(out))
    result = [analyse(x) for x in args.dirs]
    out.write_text(json.dumps(result if len(result) > 1 else result[0], indent=2), encoding='utf8')
    for r in result:
        print(r['source'], 'orders', r['orders'], 'perOrder', r['commitsPerOrderTotal'], r['commitsPerOrder'], 'rollbacks', r['rollbacks'])


if __name__ == '__main__':
    main()
