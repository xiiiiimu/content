import json,sys,re,os
phase_dir=sys.argv[1]; d=phase_dir
dbm={}
for line in open(os.path.join(d,'db-metrics.tsv')):
    f=line.rstrip('\n').split('\t'); name=f[0]; kv={}
    for x in f[1:]:
        k,v=x.split('=',1); kv[k]=v
    def num(s,key):
        m=re.search(key+r'=([0-9.]+)',s); return float(m.group(1)) if m else float('nan')
    w=num(kv['lock_after'],'Innodb_row_lock_waits')-num(kv['lock_before'],'Innodb_row_lock_waits')
    t=num(kv['lock_after'],'Innodb_row_lock_time')-num(kv['lock_before'],'Innodb_row_lock_time')
    ac=num(kv['acq_after'],'COUNT')-num(kv['acq_before'],'COUNT'); at=num(kv['acq_after'],'TOTAL_TIME')-num(kv['acq_before'],'TOTAL_TIME')
    dbm[name]=dict(lock_waits=int(w),lock_ms=int(t),acq=int(ac),acq_avg_ms=round(at/ac*1000,1) if ac else 0,acq_max_s_cum=num(kv['acq_after'],'MAX'),max_pending=kv['max_pending'])
print('| run | target rps | achieved rps | ok | 4xx | 5xx | transport err | dropped iters | p50 ms | p95 ms | p99 ms | max ms | row lock waits | row lock time ms | hikari acquires | avg acquire ms | max pending |')
print('|'+'---|'*17)
for name in ['warmup','a50','a100','a200','b50','b100','b200','c50','c100','c200']:
    p=os.path.join(d,name+'.json')
    if not os.path.exists(p): continue
    m=json.load(open(p))['metrics']; h=m['http_req_duration']
    g=lambda k:int(m[k]['count']) if k in m else 0
    rate=round(m['http_reqs']['rate'],1)
    x=dbm.get(name,{})
    print(f"| {name} | {re.sub('[a-z]','',name) or 20} | {rate} | {g('like_ok')} | {g('like_4xx')} | {g('like_5xx')} | {g('like_transport_err')} | {g('dropped_iterations')} | {h['med']:.1f} | {h['p(95)']:.1f} | {h['p(99)']:.1f} | {h['max']:.1f} | {x.get('lock_waits')} | {x.get('lock_ms')} | {x.get('acq')} | {x.get('acq_avg_ms')} | {x.get('max_pending')} |")
