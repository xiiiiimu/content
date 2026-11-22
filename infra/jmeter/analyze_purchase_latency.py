"""Read-only purchase JTL and committed DB timelines. No credentials or input CSV content."""
import argparse,csv,json,math
from pathlib import Path
from datetime import datetime,timezone,timedelta
from purchase_capacity import mysql
def distribution(rows,key):
    values=sorted(int(r[key]) for r in rows)
    return {q:values[math.ceil(len(values)*p)-1] for q,p in [('p50',.5),('p95',.95),('p99',.99)]} if values else {}
def local(ms):
    return datetime.fromtimestamp(int(ms)/1000,timezone(timedelta(hours=8))).isoformat() if ms else None
def analyze(directory,out,campaign,phase_timelines=None):
    rows=list(csv.DictReader((directory/'results.jtl').open(encoding='utf-8-sig')))
    has_dispatch_clock=int(mysql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='content' AND table_name='material_sale_dispatch' AND column_name='sent_at_ms'")[0][0])==1
    ack_clock='COALESCE(d.sent_at_ms,r.mq_sent_at_ms,0)' if has_dispatch_clock else 'COALESCE(r.mq_sent_at_ms,0)'
    ack_join='LEFT JOIN material_sale_dispatch d ON d.reservation_id=r.reservation_id' if has_dispatch_clock else ''
    db=mysql(f"""SELECT r.client_key,r.reservation_id,r.user_id,r.state,r.created_at_ms,
    {ack_clock},r.updated_at_ms,COALESCE(r.order_id,0),
    COALESCE(o.state,''),COALESCE(o.paid_at_ms,0),COALESCE(c.created_at_ms,0),COALESCE(c.decision,'')
    FROM material_purchase_requests r LEFT JOIN material_orders o ON o.id=r.order_id
    {ack_join}
    LEFT JOIN material_sale_consume_receipts c ON c.reservation_id=r.reservation_id WHERE r.campaign_id={campaign}""")
    bykey={r[0]:r for r in db}
    phases={r['clientKey']:r for r in json.loads(phase_timelines.read_text(encoding='utf8'))} if phase_timelines else {}
    records=[]
    for row in rows:
        start=int(row['timeStamp']);end=start+int(row['elapsed']);durable=bykey.get(row['effClientKey'])
        record={'clientKey':row['effClientKey'],'reservationId':row.get('reservationId',''),'userId':row.get('userId'),
         'status':row['responseCode'],'businessCode':row.get('businessCode'),'startMs':start,'endMs':end,
         'startLocal':local(start),'endLocal':local(end),'Connect':int(row['Connect']),'Latency':int(row['Latency']),'elapsed':int(row['elapsed']),
         'serverReceivedMs':None,'serverResponseMs':None,
         'database':None if durable is None else {'reservationId':durable[1],'userId':durable[2],'requestState':durable[3],
         'createdAtMs':int(durable[4]),'mqSentAtMs':int(durable[5]),'requestUpdatedAtMs':int(durable[6]),'orderId':int(durable[7]),
         'orderState':durable[8],'paidAtMs':int(durable[9]),'receiptCreatedAtMs':int(durable[10]),'receiptDecision':durable[11],
         'paidRecordAfterSampleStartMs':int(durable[9])-start,'paidRecordAfterHttpEndMs':int(durable[9])-end}}
        timing=phases.get(row['effClientKey'])
        if timing and timing.get('server'):
            root=timing['server'];record.update(serverReceivedMs=root['startMs'],serverFilterEndMs=root['startMs']+root['durationMs'],tcpAcceptedMs=timing.get('acceptMs'))
            if timing.get('accessLog'):record['serverResponseMs']=timing['accessLog']['endMs']
            commits=[p for p in timing.get('phases',[]) if p['phase']=='jdbc.commit']
            if commits:record['acceptCommitReturnedMs']=commits[-1]['startMs']+commits[-1]['durationMs']
            settle=timing.get('settlement')
            if settle:
                record['settlementStartMs']=settle['startMs'];record['settlementEndMs']=settle['startMs']+settle['durationMs']
                commits=[p for p in settle['phases'] if p['phase']=='jdbc.commit']
                # Main order/inventory/entitlement/receipt transaction commits first;
                # the subsequent durable cache-job synchronization has its own commits.
                if commits:
                    main=min(commits,key=lambda p:p['startMs'])
                    record['settlementCommitReturnedMs']=main['startMs']+main['durationMs']
        records.append(record)
    groups={}
    for status in sorted({r['responseCode'] for r in rows}):
        subset=[r for r in rows if r['responseCode']==status]
        groups[status]={'count':len(subset),**{k:distribution(subset,k) for k in ['elapsed','Latency','Connect']}}
    report={'campaignId':campaign,'sourceJtl':str(directory/'results.jtl'),'sampleCount':len(rows),
     'labels':sorted({r['label'] for r in rows}),'groups':groups,
     'all':{k:distribution(rows,k) for k in ['elapsed','Latency','Connect']},
     'top10':sorted(records,key=lambda r:r['elapsed'],reverse=True)[:10],
     'acceptedTop10':sorted((r for r in records if r['status']=='200'),key=lambda r:r['elapsed'],reverse=True)[:10],
     'timelineNote':'Missing historical receive/response times remain null. DB created_at/paid_at are record points; the first settlement jdbc.commit is the atomic order transaction, later commits can synchronize cache jobs. Verify one settlement attempt per reservation before treating this as a unique order commit timeline; duplicate attempts need interval separation. JFR commit end is JDBC commit returned, not the physical commit instant. Access-log end is server response completion, not a wire ACK.',
     'acceptedWithCommittedRequest':sum(r['status']=='200' and r['database'] is not None for r in records),
     'soldOutWithNoDurableRequest':sum(r['status']=='409' and r['database'] is None for r in records)}
    out.mkdir(parents=True,exist_ok=True)
    (out/'jtl-distributions.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    (out/'request-db-timelines.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps({'samples':len(rows),'groups':groups,'acceptedMatched':report['acceptedWithCommittedRequest']}))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('output',type=Path);p.add_argument('--campaign',type=int,required=True);p.add_argument('--phase-timelines',type=Path);a=p.parse_args();analyze(a.directory,a.output,a.campaign,a.phase_timelines)

