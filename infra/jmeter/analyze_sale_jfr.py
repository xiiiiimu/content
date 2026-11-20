"""Export only whitelisted sale timing fields from a private JFR JSON export."""
import argparse,csv,json,math,re
from collections import defaultdict
from datetime import datetime
from pathlib import Path

def ms(iso):
    return datetime.fromisoformat(iso.replace('Z','+00:00')).timestamp()*1000
def duration(value):
    m=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:([0-9.]+)S)?',value)
    if not m: raise ValueError('Unexpected JFR duration: '+value)
    return (float(m[1] or 0)*3600+float(m[2] or 0)*60+float(m[3] or 0))*1000
def dist(values):
    v=sorted(values)
    return dict(count=len(v),**({k:round(v[math.ceil(len(v)*p)-1],3) for k,p in [('p50',.5),('p95',.95),('p99',.99),('max',1)]} if v else {}))
def analyze(source,jtl,out,access_log=None):
    data=json.loads(source.read_text(encoding='utf8'))['recording']['events']
    phases=defaultdict(list);requests={};settlements={};background={};accepts=defaultdict(list);loss=[];gc=[]
    for event in data:
        name=event['type'];v=event['values']
        if name=='content.SalePhase':
            phases[v['traceId']].append(dict(phase=v['phase'],startMs=ms(v['startTime']),durationMs=duration(v['duration'])))
        elif name=='content.SaleRequest':
            item={k:v.get(k) for k in ['traceId','kind','clientKey','reservationId','campaignId','remotePort','status']}
            item.update(startMs=ms(v['startTime']),durationMs=duration(v['duration']))
            target=requests if item['kind']=='http' else settlements if item['kind']=='settle' else background
            target[v['traceId']]=item
        elif name=='content.SaleAccepted': accepts[v['remotePort']].append(ms(v['startTime']))
        elif name=='jdk.DataLoss': loss.append({k:v.get(k) for k in ['amount','total']})
        elif name in ['jdk.GarbageCollection','jdk.GCPhasePause']:
            gc.append(dict(type=name,startMs=ms(v['startTime']),durationMs=duration(v['duration'])))
    access={}
    if access_log:
        for row in csv.reader(access_log.open(encoding='utf8')):
            if len(row)==7 and re.fullmatch(r'jm-c[0-9]+-u[0-9]+',row[5]):
                access[row[5]]=dict(beginMs=int(row[0]),endMs=int(row[1]),durationUs=int(row[2]),status=row[3],thread=row[6])
    groups=defaultdict(lambda:defaultdict(list));timelines=[]
    rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')))
    for row in rows:
        key=row['effClientKey'];root=requests.get(key);start=int(row['timeStamp']);end=start+int(row['elapsed'])
        item=dict(clientKey=key,userId=row.get('userId'),status=row['responseCode'],businessCode=row.get('businessCode'),
                  reservationId=row.get('reservationId'),jmeterStartMs=start,jmeterEndMs=end,
                  elapsed=int(row['elapsed']),Connect=int(row['Connect']),Latency=int(row['Latency']),server=root)
        if root:
            before=[t for t in accepts[root['remotePort']] if t<=root['startMs']]
            accept=max(before) if before else None
            item.update(acceptMs=accept,phases=phases[key])
            values={'http.filter.total':root['durationMs'],'sampler.to.filter':root['startMs']-start,
                    'filter.end.to.sampler.end':end-root['startMs']-root['durationMs']}
            values['sql.total.per.request']=sum(p['durationMs'] for p in phases[key] if p['phase'].startswith('sql.'))
            values['jdbc.acquire.total.per.request']=sum(p['durationMs'] for p in phases[key] if p['phase']=='jdbc.acquire')
            values['redis.total.per.request']=sum(p['durationMs'] for p in phases[key] if p['phase'].startswith('redis.'))
            if key in access:
                item['accessLog']=access[key]
                values['access.end.to.sampler.end']=end-access[key]['endMs']
                values['filter.end.to.access.end']=access[key]['endMs']-root['startMs']-root['durationMs']
            if accept is not None:
                values.update({'sampler.to.accept':accept-start,'accept.to.filter':root['startMs']-accept})
            for phase in phases[key]:
                groups[row['responseCode']][phase['phase']].append(phase['durationMs'])
            for k,v in values.items(): groups[row['responseCode']][k].append(v)
            rid=root.get('reservationId') or row.get('reservationId')
            settle=settlements.get('settle-'+str(rid))
            if settle:item['settlement']=dict(**settle,phases=phases[settle['traceId']])
        timelines.append(item)
    first=min(t['jmeterStartMs'] for t in timelines);last=max(t['jmeterEndMs'] for t in timelines)
    settlement_phases=defaultdict(list)
    for trace,root in settlements.items():
        settlement_phases['settlement.total'].append(root['durationMs'])
        for phase in phases[trace]:settlement_phases[phase['phase']].append(phase['durationMs'])
        locks=[p for p in phases[trace] if p['phase'].endswith('.lockCampaign')]
        commits=[p for p in phases[trace] if p['phase']=='jdbc.commit']
        if locks and commits:
            lock=locks[0];commit=min(commits,key=lambda p:p['startMs'])
            settlement_phases['campaign.lock.return.to.commit.return'].append(commit['startMs']+commit['durationMs']-lock['startMs']-lock['durationMs'])
    background_counts=defaultdict(int);background_phases=defaultdict(lambda:defaultdict(list));background_timelines=[]
    for trace,root in background.items():
        background_counts[root['kind']]+=1
        if not root.get('reservationId'):continue
        background_phases[root['kind']]['total'].append(root['durationMs'])
        for phase in phases[trace]:background_phases[root['kind']][phase['phase']].append(phase['durationMs'])
        background_timelines.append(dict(**root,phases=phases[trace]))
    report=dict(samples=len(rows),httpEvents=len(requests),matched=sum(t['server'] is not None for t in timelines),
                settlementEvents=len(settlements),dataLoss=loss,
                backgroundEvents=dict(background_counts),backgroundStageDistributions={kind:{k:dist(v) for k,v in values.items()} for kind,values in background_phases.items()},
                settlementPhaseDistributions={k:dist(v) for k,v in settlement_phases.items()},
                groups={status:{k:dist(v) for k,v in values.items()} for status,values in groups.items()},
                gcDuringHttp=[g for g in gc if first<=g['startMs']<=last],
                slowest10=sorted(timelines,key=lambda t:t['elapsed'],reverse=True)[:10],
                acceptedSlowest10=sorted([t for t in timelines if t['status']=='200'],key=lambda t:t['elapsed'],reverse=True)[:10],
                caveats=['accept.to.filter includes request bytes/parse/worker scheduling, not exclusively executor queue.',
                         'SQL spans include MyBatis mapping and nested JDBC acquisition; nested phases must not be summed.',
                         'Filter end includes MVC serialization but is not wire ACK; JTL end observes HTTP completion.',
                         'Raw JFR stays private; no environment, headers, SQL parameters or token fields exported.'])
    out.mkdir(parents=True,exist_ok=True)
    (out/'phase-distributions.json').write_text(json.dumps(report,indent=2),encoding='utf8')
    (out/'phase-timelines.json').write_text(json.dumps(timelines,indent=2),encoding='utf8')
    (out/'background-timelines.json').write_text(json.dumps(background_timelines,indent=2),encoding='utf8')
    print(json.dumps({k:report[k] for k in ['samples','matched','httpEvents','settlementEvents','dataLoss']}))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('jtl',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--access-log',type=Path)
    a=p.parse_args();analyze(a.source,a.jtl,a.output,a.access_log)
