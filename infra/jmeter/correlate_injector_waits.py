"""Correlate whitelisted JFR wait intervals to one-shot JTL thread names."""
import argparse,csv,json
from pathlib import Path

def union_ms(intervals):
    merged=[]
    for start,end in sorted(intervals):
        if merged and start<=merged[-1][1]:merged[-1][1]=max(merged[-1][1],end)
        else:merged.append([start,end])
    return sum(b-a for a,b in merged)

def correlate(jtl,events,out):
    rows=list(csv.DictReader(jtl.open(encoding='utf-8-sig')))
    slow=sorted(rows,key=lambda r:int(r['elapsed']),reverse=True)[:10]
    slow+=sorted([r for r in rows if r['responseCode']=='200'],key=lambda r:int(r['elapsed']),reverse=True)[:10]
    chosen={r['threadName']:r for r in slow};found={name:[] for name in chosen}
    for event in csv.DictReader(events.open(encoding='utf8'),delimiter='\t'):
        name=event['thread']
        if name not in chosen:continue
        row=chosen[name];start=int(row['timeStamp']);end=start+int(row['elapsed'])
        a=max(start,int(event['startMs']));b=min(end,int(event['endMs']))
        if b<=a:continue
        found[name].append(dict(type=event['stack'].split('|')[0],startMs=int(event['startMs']),endMs=int(event['endMs']),
            durationMs=float(event['durationMs']),overlapStartMs=a,overlapEndMs=b,stack=event['stack']))
    report=[]
    for name,row in chosen.items():
        waits=found[name];client=[e for e in waits if e['type'] in ['jdk.JavaMonitorEnter','jdk.ClassLoad']]
        report.append(dict(clientKey=row['effClientKey'],threadName=name,elapsed=int(row['elapsed']),status=row['responseCode'],
          jmeterStartMs=int(row['timeStamp']),jmeterEndMs=int(row['timeStamp'])+int(row['elapsed']),
          clientJvmWaitUnionMs=union_ms([(e['overlapStartMs'],e['overlapEndMs']) for e in client]),
          observedWaitCount=len(waits),largestWaits=sorted(waits,key=lambda e:e['durationMs'],reverse=True)[:12]))
    out.write_text(json.dumps(dict(requests=report,note='Only intervals overlapping HTTP sample are included. Nested class-load and monitor waits use interval union, not addition. SocketRead includes backend/network/scheduling; excluded from client-JVM wait union.'),indent=2),encoding='utf8')
    print(json.dumps([dict(clientKey=r['clientKey'],elapsed=r['elapsed'],clientJvmWaitUnionMs=r['clientJvmWaitUnionMs']) for r in report[:3]]))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('jtl',type=Path);p.add_argument('events',type=Path);p.add_argument('output',type=Path);a=p.parse_args();correlate(a.jtl,a.events,a.output)
