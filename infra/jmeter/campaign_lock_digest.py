"""Read-only digest of campaign-lock / dispatch tier directories (same extraction as the V26 report, campaign-lock-v26-20261120.md).

  python infra/jmeter/campaign_lock_digest.py label=<tier dir> [label=<tier dir> ...] > digest.json
"""
import json,sys,collections,csv
from pathlib import Path
def r(p):return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def q(v,p):v=sorted(v);return v[min(len(v)-1,int(p*len(v)))] if v else None
def digest(label,d):
    d=Path(d);s=r(d/'summary.json');e=r(d/'commit-evidence.json');m=r(d/'dispatch-batch-metrics.json');a=r(d/'supplemental-analysis.json')
    cpo=r(d/'commits-per-order.json') if (d/'commits-per-order.json').exists() else None
    acc=s['groups']['accepted'];rc=e.get('httpStartToServerCommitReturnedMs') or {}
    out={'label':label,'samples':s['samples'],'arrival':round(s['requestArrivalRate'],1),'httpP95':acc['elapsed']['p95'],'httpP99':acc['elapsed']['p99'],
     'windowTPS':round(s['windowCommittedTps'],2),'fullTPS':round(s['fullCommittedTps'],2),'paid':s['paidOrders'],
     'reqCommitP50s':round(rc.get('p50',0)/1000,1),'reqCommitP95s':round(rc.get('p95',0)/1000,1),'drainS':round(s['drainConfirmedAfterLastHttpSec'],1),
     'processingPeak':a.get('processingPeak'),'dispatchPendingPeak':a.get('dispatchPendingPeak'),'streamLagPeak':a.get('streamLagPeakGlobal'),
     'campaignLockWaits':m['lockWaits']['observedWaitsByTable'].get('material_sale_campaigns',0),
     'settleRootToCommitP50':a['settlementRootStartToMainCommitReturnedMs']['p50'],'settleRootToCommitP95':a['settlementRootStartToMainCommitReturnedMs']['p95'],
     'lockToCommitP50':a['campaignLockReturnToMainCommitReturnMs']['p50'],'lockToCommitP95':a['campaignLockReturnToMainCommitReturnMs']['p95'],
     'settleCommitP50':m['settle']['commitMs']['p50'],'settleCommitP95':m['settle']['commitMs']['p95'],
     'leaseP95':m['leaseUseMs']['p95'],'leaseMax':m['leaseUseMs']['max'],'outcomes':m['outcomes'],'claims':m['claimSizeDistribution'],
     'correct':s['correctnessPass'],'checks':{k:v for k,v in s['checks'].items() if not v}}
    if (d/'orphan-references.json').exists():out['orphans']=r(d/'orphan-references.json')
    if cpo:out['cpo']={'total':cpo['commitsPerOrderTotal'],**cpo['commitsPerOrder'],'bridgeCacheSync':round(cpo['cacheSyncFromBridge']/cpo['orders'],3),'commitsPerSec':cpo['commitsPerSecondOverSpan'],'rollbacks':cpo['rollbacks']}
    # dispatch throughput during window and after
    sends=[int(x['startMs']) for x in csv.DictReader((d/'server-events.tsv').open(encoding='utf8'),delimiter='	') if x['event']=='request' and x['kind']=='dispatch-send']
    out['dispatchPerSecWindow'],out['dispatchPerSecAfterWindow']=dispatch_rates(sends,s['window']['startEpochMs'],s['window']['endEpochMs'])
    return out
def dispatch_rates(sends,w0,w1,settle_gap_ms=30000):
    """Dispatch sends per second inside the window, and from window end + 30 s to the last send (the drain rate)."""
    inside=round(sum(w0<=t<w1 for t in sends)/((w1-w0)/1000),2)
    late=[t for t in sends if t>=w1+settle_gap_ms]
    return inside,(round(len(late)/((max(late)-min(late))/1000),2) if len(late)>1 else None)
if __name__=='__main__':
    res=[digest(*x.split('=',1)) for x in sys.argv[1:]]
    print(json.dumps(res,indent=1))
