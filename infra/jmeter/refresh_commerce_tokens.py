"""Refresh only isolated jmeter test identities using ordinary login; never change auth TTL."""
import argparse,base64,concurrent.futures,csv,json,time,uuid
from pathlib import Path
from urllib.request import Request,urlopen
from purchase_capacity import mysql

def remaining(token):
 p=token.split('.')[0];return json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4)))['expiresAt']/1000-time.time()
def refresh(path):
 p=Path(path);m=json.loads(p.read_text(encoding='utf-8-sig'))
 with open(m['buyersCsv'],encoding='utf-8-sig') as f:buyers=list(csv.DictReader(f))
 ids=[int(m['authorUserId'])]+[int(r['userId']) for r in buyers]
 names=dict(mysql('SELECT id,username FROM users WHERE id IN ('+','.join(map(str,ids))+');'))
 def login(uid,password):
  name=names[str(uid)]
  if not name.startswith(('jma','jmb')):raise ValueError('Only isolated jmeter identities may be refreshed')
  req=Request(m['apiBase']+'/api/auth/login',data=json.dumps({'username':name,'password':password}).encode(),headers={'Content-Type':'application/json'})
  with urlopen(req,timeout=30) as r:s=json.load(r)
  if int(s['userId'])!=int(uid):raise ValueError('Login identity mismatch')
  return s['token']
 m['authorToken']=login(m['authorUserId'],'password-a1')
 def change(row):row['token']=login(row['userId'],'password-b1')
 for attempt in range(4):
  needed=[r for r in buyers if remaining(r['token'])<420]
  if not needed:break
  with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:list(pool.map(change,needed))
  if min(remaining(r['token']) for r in buyers)>=360:break
 if min(remaining(r['token']) for r in buyers)<360:raise ValueError('Cannot establish a six-minute token lifetime for every buyer')
 # Refresh author last as preparing 5000 logins can itself consume several minutes.
 m['authorToken']=login(m['authorUserId'],'password-a1')
 name='fresh-'+uuid.uuid4().hex[:10];out=p.parent/('buyers-'+name+'.csv')
 with out.open('x',newline='',encoding='utf8') as f:w=csv.DictWriter(f,fieldnames=['userId','token','clientKey']);w.writeheader();w.writerows(buyers)
 m['buyersCsv']=str(out.resolve());dest=p.parent/('meta-'+name+'.json')
 with dest.open('x',encoding='utf8') as f:json.dump(m,f)
 print(dest.resolve())
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('meta');a=p.parse_args();refresh(a.meta)
