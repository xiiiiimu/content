"""Refresh isolated JMeter identities with ordinary login, without mutating old evidence."""
import argparse,concurrent.futures,csv,json,re,time
from pathlib import Path
from urllib.request import Request,build_opener,ProxyHandler
from purchase_capacity import mysql

def refresh(meta,warm,destination):
    out=Path(destination);out.mkdir(parents=True,exist_ok=False)
    sources=[json.loads(Path(p).read_text(encoding='utf-8-sig')) for p in [meta,warm]]
    groups=[]
    for m in sources:
        with open(m['buyersCsv'],encoding='utf-8-sig') as f:groups.append(list(csv.DictReader(f)))
    ids={int(m['authorUserId']) for m in sources}|{int(r['userId']) for rr in groups for r in rr}
    names=dict(mysql('SELECT id,username FROM users WHERE id IN ('+','.join(map(str,ids))+');'))
    if len(names)!=len(ids) or not all(re.fullmatch(r'jm[ab][A-Za-z0-9_]+',n) for n in names.values()):
        raise ValueError('Only existing isolated JMeter test identities can be refreshed')
    def login(uid):
        uid=str(uid);author=names[uid].startswith('jma')
        data=json.dumps({'username':names[uid],'password':'password-a1' if author else 'password-b1'}).encode()
        req=Request('http://127.0.0.1:28080/api/auth/login',data=data,headers={'Content-Type':'application/json'})
        with build_opener(ProxyHandler({})).open(req,timeout=15) as response:r=json.load(response)
        if int(r['userId'])!=int(uid):raise ValueError('Login identity mismatch')
        return uid,r['token']
    started=time.time()
    # Do not share author login timing with slow bulk refresh: refresh it last.
    buyers=ids-{int(m['authorUserId']) for m in sources}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:tokens=dict(pool.map(login,buyers))
    for uid in ids-buyers:
        k,v=login(uid);tokens[k]=v
    for label,m,rr in zip(['formal','warm'],sources,groups):
        path=out/(label+'-buyers.csv')
        with path.open('x',encoding='utf8',newline='') as f:
            w=csv.DictWriter(f,fieldnames=['userId','token','clientKey']);w.writeheader()
            for r in rr:w.writerow({**r,'token':tokens[r['userId']]})
        m['buyersCsv']=str(path.resolve());m['authorToken']=tokens[str(m['authorUserId'])]
        (out/(label+'-meta.json')).write_text(json.dumps(m,indent=2),encoding='utf8')
    safe={'refreshedIdentities':len(ids),'elapsedSec':round(time.time()-started,3),'ordinaryLogin':True,'credentialsArchived':False}
    (out/'refresh-summary.json').write_text(json.dumps(safe,indent=2));print(json.dumps(safe),flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--meta',required=True);p.add_argument('--warm',required=True);p.add_argument('--out',required=True)
    a=p.parse_args();refresh(a.meta,a.warm,a.out)
