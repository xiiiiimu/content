"""Bounded-memory summary of private JFR JSON; exports frame names and timings only."""
import argparse,collections,json
from pathlib import Path
from analyze_sale_jfr import ms,duration

def events(path):
    decoder=json.JSONDecoder()
    with path.open(encoding='utf8') as f:
        buf=f.read(65536);buf=buf[buf.index('"events"'):];buf=buf[buf.index('[')+1:]
        while True:
            buf=buf.lstrip(' \r\n\t,')
            if buf.startswith(']'):return
            try:event,end=decoder.raw_decode(buf)
            except json.JSONDecodeError:
                chunk=f.read(1048576)
                if not chunk:raise ValueError('Truncated JFR export')
                buf+=chunk;continue
            yield event;buf=buf[end:]

def summarize(source,out,start,end):
    groups={};count=0
    for event in events(source):
        v=event['values'];t=ms(v['startTime']);d=duration(v.get('duration','PT0S'))
        if t>end or t+d<start:continue
        count+=1;frames=(v.get('stackTrace') or {}).get('frames',[])
        names=[f["method"]["type"]["name"].replace('/','.')+'.'+f['method']['name'] for f in frames]
        key=event['type']+'|'+(' > '.join(names[:12]))
        item=groups.setdefault(key,dict(count=0,totalMs=0,maxMs=0,exampleStartMs=t))
        item['count']+=1;item['totalMs']+=d;item['maxMs']=max(item['maxMs'],d)
    report=dict(startMs=start,endMs=end,matchedEvents=count,
        topByCount=[dict(stack=k,**v) for k,v in sorted(groups.items(),key=lambda kv:kv[1]['count'],reverse=True)[:35]],
        topByBlockedTime=[dict(stack=k,**v) for k,v in sorted(groups.items(),key=lambda kv:kv[1]['totalMs'],reverse=True)[:35]])
    out.write_text(json.dumps(report,indent=2),encoding='utf8')
    print(json.dumps(dict(events=count,groups=len(groups))))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('output',type=Path);p.add_argument('--start',type=float,required=True);p.add_argument('--end',type=float,required=True)
    a=p.parse_args();summarize(a.source,a.output,a.start,a.end)
