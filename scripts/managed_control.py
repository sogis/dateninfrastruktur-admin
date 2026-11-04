#!/usr/bin/env python3
"""Bounded controls and observations for the owned MA-E integration runtime."""
import argparse,hashlib,json,subprocess,time
from management_db import R,D,state,sql,cmd
from identity_runtime import I,save,own
from managed_runtime import load,routes,resource,rclone,BUCKET,stop,start
from managed_pilot import rewrite_routes
PROBE_IMAGE='python:3.13.7-alpine3.22@sha256:9ba6d8cbebf0fb6546ae71f2a1c14f6ffd2fdab83af7fa5669734ef30ad48844'
def snapshot():
    s=load();st=state()
    if st['container']!=s['database']:raise RuntimeError('Database mismatch')
    material=sql("SELECT jsonb_build_array((SELECT jsonb_agg(to_jsonb(p) ORDER BY t_id) FROM di_management.principal p),(SELECT jsonb_agg(to_jsonb(d) ORDER BY t_id) FROM di_management.dataset d),(SELECT jsonb_agg(to_jsonb(g) ORDER BY t_id) FROM di_management.accessgrant g),(SELECT jsonb_agg(to_jsonb(a) ORDER BY t_id) FROM di_management.accessdecision a),(SELECT count(*) FROM audit.row_change))::text;")
    secrets={k:v for k,v in s.items() if any(x in k.lower() for x in ['secret','password','key'])}
    return {'databaseSha256':hashlib.sha256(material.encode()).hexdigest(),'secretsSha256':hashlib.sha256(json.dumps(secrets,sort_keys=True).encode()).hexdigest()}
def probe(enabled):
    s=load()
    if enabled:
        if 'origin-probe' not in s['services']:resource(s,'origin-probe',['--mount','type=bind,src='+str(R/'scripts/origin_header_probe.py')+',dst=/probe.py,readonly',PROBE_IMAGE,'python','/probe.py'])
        for n in ('a','b'):
            config=routes(s,n);next(r for r in config['routes'] if r['id']=='download')['upstream']['nodes']={'origin-probe:8080':1};rewrite_routes(s,n,config)
    else:
        for n in ('a','b'):rewrite_routes(s,n,routes(s,n))
    time.sleep(2)
def probe_result():
    s=load();own(s,s['services']['origin-probe']);p=subprocess.run(['docker','logs',s['services']['origin-probe']],capture_output=True,text=True,check=True)
    records=[json.loads(x) for x in p.stdout.splitlines() if x.strip()]
    if len(records)<2 or any(x['credentialHeaderNames'] for x in records):raise RuntimeError('Origin credential stripping proof failed; values were never logged')
    return {'observedRequests':len(records),'credentialHeaders':0}
def storage_boundaries():
    s=load();file=json.loads((D.parent/'fixture.json').read_text())['files'][0]
    # Positive credentialed access to the same real private object.
    content=rclone(s,['cat','managed:'+BUCKET+'/'+file['key']])+'\n'
    if hashlib.sha256(content.encode()).hexdigest()!=file['sha256']:raise RuntimeError('Private signed object positive control failed')
    script='''import urllib.request,urllib.error,json
urls=[('private','http://garage:3900/di-managed-private/'''+file['key']+'''',{}),('oldS3','http://garage:3900/di-managed-legacy/canary.csv',{}),('oldWebsite','http://garage:3902/canary.csv',{'Host':'di-managed-legacy.web.garage.localhost'})]
out={}
for name,url,headers in urls:
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=3) as r:out[name]=r.status
 except urllib.error.HTTPError as e:out[name]=e.code
print(json.dumps(out))
'''
    observed=json.loads(cmd(['docker','run','--rm','--label','di.identity='+s['id'],'--network',s['network'],PROBE_IMAGE,'python','-c',script]))
    if observed['private']!=403 or observed['oldS3']!=403 or observed['oldWebsite'] not in (403,404):raise RuntimeError('Anonymous storage bypass')
    # Garage offers bucket ACLs, not prefix IAM. Origin has read, no write/owner/create.
    from managed_runtime import rclone_config
    from identity_runtime import write
    conf=write('origin-rclone.conf',rclone_config(s,True))
    test=subprocess.run(['docker','run','--rm','--label','di.identity='+s['id'],'--network',s['network'],'--mount','type=bind,src='+str(conf)+',dst=/config/rclone/rclone.conf,readonly','rclone/rclone:1.75.2','rcat','managed:'+BUCKET+'/must-not-write'],input=b'denied',capture_output=True)
    if test.returncode==0:raise RuntimeError('Origin key unexpectedly has write access')
    return observed|{'signedSameObject':200,'originWriteDenied':True}
def private_ports():
    s=load();st=state();observed={}
    for name,cid in {'database':st['container'],**s['services']}.items():
        if name!='database':own(s,cid)
        ports=json.loads(cmd(['docker','inspect','--format','{{json .HostConfig.PortBindings}}',cid])) or {}
        if not name.startswith('gateway-') and ports:raise RuntimeError('Internal service has host port: '+name)
        if name.startswith('gateway-') and any(v['HostIp']!='127.0.0.1' for values in ports.values() for v in values):raise RuntimeError('Gateway is not loopback-only')
        observed[name]=bool(ports)
    return observed
def scan_logs():
    s=load();values=[v for k,v in s.items() if isinstance(v,str) and (any(x in k.lower() for x in ['secret','password']) or k.endswith('Key'))]
    values+=list(json.loads((D/'roles.json').read_text()).values());extra=D.parent/'test-sensitive.json'
    if extra.exists():values+=list(json.loads(extra.read_text()).values())
    for cid in s['containers']:
        own(s,cid);p=subprocess.run(['docker','logs',cid],capture_output=True,text=True,check=True)
        if any(value and value in p.stdout+p.stderr for value in values):raise RuntimeError('Sensitive value found in owned runtime logs; value withheld')
    return {'sensitiveLogMatches':0}
def main(action,target=None):
    s=load()
    if action in ('pause','resume'):
        if target not in ('database','admin-a','admin-b','garage','origin'):raise RuntimeError('Unapproved failure injection target')
        cid=state()['container'] if target=='database' else s['services'][target]
        if target!='database':own(s,cid)
        cmd(['docker','pause' if action=='pause' else 'unpause',cid]);return {'action':action,'target':target}
    if action=='snapshot':return snapshot()
    if action=='restart':
        stop();start();cmd(['python3',str(R/'scripts/managed_setup.py')]);return {'restarted':True}
    if action=='ports':return private_ports()
    if action=='storage':return storage_boundaries()
    if action=='probe-on':probe(True);return {'probe':True}
    if action=='probe-off':probe(False);return {'probe':False}
    if action=='probe-result':return probe_result()
    if action=='logs':return scan_logs()
    raise ValueError('Unknown action')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['pause','resume','snapshot','restart','ports','storage','probe-on','probe-off','probe-result','logs']);p.add_argument('target',nargs='?');a=p.parse_args();print(json.dumps(main(a.action,a.target)))
