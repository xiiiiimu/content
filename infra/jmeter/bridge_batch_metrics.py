"""Offline bridge metrics. Counts successful persisted identities at JDBC commit return."""
import csv,json,collections,sys
from pathlib import Path
from analyze_v23_performance import dist

def analyze(directory):
    p=Path(directory);summary=json.loads((p/'summary.json').read_text());w=summary['window'];w0,w1=w['startEpochMs'],w['endEpochMs'];seconds=(w1-w0)/1000
    ledger=json.loads((p/'reconciliation.json').read_text());ids={r[0] for r in ledger['requests']}
    with (p/'results.jtl').open(encoding='utf-8-sig') as f:posts=[r for r in csv.DictReader(f) if r['label']=='POST-purchase']
    with (p/'server-events.tsv.batches.tsv').open(encoding='utf8') as f:batches=list(csv.DictReader(f,delimiter='\t'))
    wanted={b['traceId'] for b in batches if any(x in ids for x in b.get('reservationIds',b['newReservationIds']).split(','))}
    phases=collections.defaultdict(list);window_sql=collections.Counter();window_commits=0;loss=0
    kinds={'bridge-prepare','bridge-scan','bridge-cache-sync','bridge-cycle','persist-batch','quarantine'}
    with (p/'server-events.tsv').open(encoding='utf8') as f:
        for e in csv.DictReader(f,delimiter='\t'):
            if e['event']=='DATA_LOSS':loss+=1
            if e['event']!='phase':continue
            if e['traceId'] in wanted:phases[e['traceId']].append(e)
            if e['kind'] in kinds:
                if e['phase'].startswith('sql.') and w0<=int(e['startMs'])<w1:window_sql[e['phase']]+=1
                if e['phase']=='jdbc.commit' and w0<=int(e['endMs'])<w1:window_commits+=1
    selected=[];new_ids=[];sql=collections.Counter();commit_count=0;rollback_count=0;tx=[];sql_per=[]
    for b in batches:
        created=[x for x in b['newReservationIds'].split(',') if x]
        formal=[x for x in created if x in ids]
        if b['traceId'] not in wanted:continue
        pp=phases[b['traceId']];cc=[x for x in pp if x['phase']=='jdbc.commit']
        if not cc:raise ValueError('Committed bridge batch lacks its JDBC commit timing')
        # The batch metadata is emitted only after TransactionTemplate returned.
        # If a failed unique-key attempt retried, the last commit is the confirmed one.
        at=max(int(c['endMs']) for c in cc);queries=[x for x in pp if x['phase'].startswith('sql.')]
        sql.update(x['phase'] for x in queries);sql_per.append(len(queries));commit_count+=len(cc);rollback_count+=sum(x['phase']=='jdbc.rollback' for x in pp)
        tx.extend(int(x['endMs'])-int(x['startMs']) for x in pp if x['phase']=='bridge.transaction')
        selected.append({'traceId':b['traceId'],'readCount':int(b['readCount']),'normalCount':int(b['normalCount']),'insertCount':len(formal),'commitReturnedAtMs':at,'sqlCount':len(queries)})
        new_ids.extend((rid,at) for rid in formal)
    counts=collections.Counter(rid for rid,_ in new_ids);coverage=not loss and all(counts[rid]==1 for rid in ids) and len(ids)==summary['groups']['accepted']['count']
    eligible=[b for b in selected if w0<=b['commitReturnedAtMs']<w1]
    first=min(int(r['timeStamp']) for r in posts)
    result={'configuredBatchSize':summary['config'].get('bridgeBatchSize'),'identityCommitCoverageComplete':coverage,'newRequestIdsObserved':len(counts),'expectedNewRequests':len(ids),
            'committedFormalBatches':len(selected),'actualReadSizeDistribution':dict(sorted(collections.Counter(b['readCount'] for b in selected).items())),
            'actualNormalSizeDistribution':dict(sorted(collections.Counter(b['normalCount'] for b in selected).items())),
            'normalBatchSqlCount':sum(sql.values()),'normalBatchSqlStatements':dict(sql),'normalBatchSqlPerBatch':dist(sql_per),
            'normalBatchExplicitJdbcCommits':commit_count,'normalBatchSuccessfulTransactions':len(selected),'normalBatchJdbcRollbacks':rollback_count,'normalBatchTransactionMs':dist(tx),
            'normalBatchSqlPerPersistedRequest':sum(sql.values())/len(ids) if ids else None,
            'windowBridgePersistedRequests':sum(b['insertCount'] for b in eligible) if coverage else None,
            'windowBridgePersistRate':sum(b['insertCount'] for b in eligible)/seconds if coverage else None,
            'fullBridgePersistRate':len(ids)/((max(at for _,at in new_ids)-first)/1000) if coverage and new_ids else None,
            'windowAllBridgeSqlCount':sum(window_sql.values()),'windowAllBridgeSqlStatements':dict(window_sql),'windowAllBridgeExplicitJdbcCommits':window_commits,
            'allBridgeSqlScope':'Includes prepare/scans including empty scans and quarantine; statement start in window. Batch commits use return in window. No per-request autocommit SELECT transaction counts inferred.',
            'batchDetails':selected}
    (p/'bridge-batch-metrics.json').write_text(json.dumps(result,indent=2),encoding='utf8');print(json.dumps({k:v for k,v in result.items() if k not in ('batchDetails','normalBatchSqlStatements','windowAllBridgeSqlStatements')},indent=2));return result
if __name__=='__main__':analyze(sys.argv[1])
