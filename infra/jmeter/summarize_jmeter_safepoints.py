"""Summarize HotSpot safepoints separately from GC pause duration."""
import argparse,json,re
from collections import defaultdict
from datetime import datetime
from pathlib import Path

def summarize(log,acceptance,out):
    report=json.loads(acceptance.read_text(encoding='utf8'))
    first=report['firstRequestStartMs'];last=report['lastHttpCompletionMs'];all_events=[]
    for path in log.parent.glob(log.name+'*'):
        for line in path.open(encoding='utf8',errors='replace'):
            m=re.search(r'Safepoint .([^,]+).,.*?Reaching safepoint: ([0-9]+) ns.*?At safepoint: ([0-9]+) ns.*?Total: ([0-9]+) ns',line)
            if not m:continue
            at=datetime.fromisoformat(line.split(']')[0][1:]).timestamp()*1000
            reach,stopped,total=[int(x)/1e6 for x in m.groups()[1:]]
            all_events.append(dict(operation=m[1],startMs=at-total,endMs=at,reachingMs=reach,atSafepointMs=stopped,totalMs=total))
    def aggregate(events):
        groups=defaultdict(list)
        for event in events:groups[event['operation']].append(event)
        return {name:dict(count=len(v),sumReachingMs=sum(e['reachingMs'] for e in v),maxReachingMs=max(e['reachingMs'] for e in v),
            sumAtSafepointMs=sum(e['atSafepointMs'] for e in v),maxTotalMs=max(e['totalMs'] for e in v)) for name,v in groups.items()}
    formal=[e for e in all_events if e['startMs']<=last and e['endMs']>=first]
    result=dict(available=bool(all_events),httpStartMs=first,httpEndMs=last,allJvmLifetime=aggregate(all_events),
        overlappingHttpWindow=aggregate(formal),longest=sorted(formal,key=lambda e:e['totalMs'],reverse=True)[:15],
        caveat='Reaching safepoint is synchronization delay, not all threads stopped. AtSafepoint is distinct from GC pause. Do not add them to overlapping JFR wait durations.')
    out.write_text(json.dumps(result,indent=2),encoding='utf8')
    print(json.dumps(result['overlappingHttpWindow']))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('log',type=Path);p.add_argument('acceptance',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();summarize(a.log,a.acceptance,a.output)
