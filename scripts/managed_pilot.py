#!/usr/bin/env python3
"""Explicit synthetic pilot operations; never run from ordinary runtime start."""
import argparse,hashlib,json,pathlib,subprocess,time,urllib.request,zipfile
from management_db import R,D,state,sql,cmd
from identity_runtime import I,write
from managed_runtime import load,rclone_config,BUCKET,activate,routes,own
FIXTURE=D.parent/'fixture.json'
def reject_missing():
    f=next(f for f in json.loads(FIXTURE.read_text())['files'] if sql("SELECT availability FROM di_management.distribution WHERE t_ili_tid='"+f['id']+"';")=='prepared')
    query="SELECT jsonb_build_array((SELECT row_version FROM di_management.dataset WHERE t_ili_tid='"+f['dataset']+"'),(SELECT to_jsonb(d) FROM di_management.distribution d WHERE t_ili_tid='"+f['id']+"'),(SELECT count(*) FROM audit.row_change),(SELECT count(*) FROM di_ops.command_receipt))::text;"
    before=sql(query);revision=int(sql("SELECT row_version FROM di_management.dataset WHERE t_ili_tid='"+f['dataset']+"';"))
    try:activate(f['dataset'],revision,f['id'])
    except RuntimeError:pass
    else:raise RuntimeError('Missing private object was activated')
    if sql(query)!=before:raise RuntimeError('Failed verification left a partial distribution, revision, audit or receipt write')
    print('Missing private object rejected; distribution/revision/audit/receipt unchanged')
def upload():
    s=load();data=json.loads(FIXTURE.read_text());payload=D.parent/'payload';payload.mkdir(exist_ok=True)
    config=write('operator-rclone.conf',rclone_config(s))
    for f in data['files']:
        file=payload/(f['kind']+'-'+f['filename'])
        if f['filename'].endswith('.csv'):file.write_text('jahr,wert\n2025,42\n2026,43\n',encoding='utf-8')
        else:
            with zipfile.ZipFile(file,'w',compression=zipfile.ZIP_DEFLATED) as z:
                z.writestr('[Content_Types].xml','<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>')
                z.writestr('_rels/.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
                z.writestr('xl/workbook.xml','<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Pilot" sheetId="1" r:id="rId1"/></sheets></workbook>')
                z.writestr('xl/_rels/workbook.xml.rels','<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
                z.writestr('xl/worksheets/sheet1.xml','<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1"><v>42</v></c></row></sheetData></worksheet>')
        f['sha256']=hashlib.sha256(file.read_bytes()).hexdigest();f['size']=file.stat().st_size
        cmd(['docker','run','--rm','--label','di.identity='+s['id'],'--network',s['network'],'--mount','type=bind,src='+str(config)+',dst=/config/rclone/rclone.conf,readonly','--mount','type=bind,src='+str(file)+',dst=/payload,readonly','rclone/rclone:1.75.2','copyto','/payload','managed:'+BUCKET+'/'+f['key'],'--immutable','--retries','1'])
    FIXTURE.write_text(json.dumps(data,indent=2));FIXTURE.chmod(0o600)
    print('Synthetic private objects uploaded; not yet activated')
def activate_all():
    data=json.loads(FIXTURE.read_text())
    for f in data['files']:
        revision=int(sql("SELECT row_version FROM di_management.dataset WHERE t_ili_tid='"+f['dataset']+"';"))
        activate(f['dataset'],revision,f['id'])
        row=sql("SELECT sha256||'|'||bytesize||'|'||availability FROM di_management.distribution WHERE t_ili_tid='"+f['id']+"';")
        if row!=f['sha256']+'|'+str(f['size'])+'|available':raise RuntimeError('Verified DB bytes/hash differs from actual private pilot object')
    print('All pilot distributions activated using real streaming S3 verification')
def rewrite_routes(s,n,config):
    # Existing bind mount inode retained. APISIX's standard YAML watcher reloads static routes.
    write('apisix-'+n+'.yaml',json.dumps(config,indent=2)+'\n#END\n',True)
def canary():
    s=load();garage=s['services']['garage'];bucket='di-managed-legacy'
    cmd(['docker','exec',garage,'/garage','bucket','create',bucket]);cmd(['docker','exec',garage,'/garage','bucket','allow','--read','--write','--owner',bucket,'--key',s['storageKey']])
    from managed_runtime import rclone
    content='synthetic-old-public-canary,42\n';rclone(s,['rcat','managed:'+bucket+'/canary.csv'],input=content)
    cmd(['docker','exec',garage,'/garage','bucket','website','--allow',bucket])
    legacy={'id':'old-canary','priority':300,'uri':'/legacy/canary.csv','plugins':{'proxy-rewrite':{'uri':'/canary.csv','host':bucket+'.web.garage.localhost'}},'upstream':{'type':'roundrobin','nodes':{'garage:3902':1}}}
    for n in ('a','b'):
        config=routes(s,n);config['routes'].insert(0,legacy);rewrite_routes(s,n,config)
    def response(url):
        try:
            with urllib.request.urlopen(url,timeout=3) as r:return r.status,r.read()
        except urllib.error.HTTPError as e:return e.code,e.read()
    for g in s['gateways']:
        for _ in range(40):
            status,body=response(g+'/legacy/canary.csv')
            if status==200 and body==content.encode():break
            time.sleep(.5)
        else:raise RuntimeError('Old controlled canary was not actually public before cutover')
    # Only our synthetic legacy object is copied; verify its bytes in the private bucket.
    rclone(s,['copyto','managed:'+bucket+'/canary.csv','managed:'+BUCKET+'/migration-evidence/canary.csv'])
    if rclone(s,['cat','managed:'+BUCKET+'/migration-evidence/canary.csv'])!=content.strip():raise RuntimeError('Canary copy mismatch')
    cmd(['docker','exec',garage,'/garage','bucket','website','--deny',bucket])
    for n in ('a','b'):rewrite_routes(s,n,routes(s,n))
    for g in s['gateways']:
        for _ in range(40):
            if response(g+'/legacy/canary.csv')[0]==403:break
            time.sleep(.5)
        else:raise RuntimeError('Old controlled gateway canary still public')
    (D.parent/'canary-evidence.json').write_text(json.dumps({'oldGatewayPaths':[g+'/legacy/canary.csv' for g in s['gateways']],'oldWebsiteHost':bucket+'.web.garage.localhost','oldS3Path':bucket+'/canary.csv','sha256':hashlib.sha256(content.encode()).hexdigest(),'previousStatus':200,'afterStatus':403,'privateCopyVerified':True,'websiteDisabled':True},indent=2))
    print('M29: real old public canary positive on both gateways, private copy verified, own website disabled and old routes denied')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['upload','canary','activate','reject-missing']);a=p.parse_args();{'upload':upload,'canary':canary,'activate':activate_all,'reject-missing':reject_missing}[a.action]()
