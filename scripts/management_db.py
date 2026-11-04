#!/usr/bin/env python3
"""Explicit isolated MA-B database harness. Never reads the user's stack env."""
import argparse, hashlib, json, os, pathlib, secrets, subprocess, time
R=pathlib.Path(__file__).resolve().parents[1];D=R/'.runtime/management-db';D.mkdir(parents=True,exist_ok=True)
IMAGE='ghcr.io/baosystems/postgis:16-3.5@sha256:0f1c5c0f70f03d4d19ad1d7308d86e6162dff5429c491002298a7b5e46d2f2e8'
def cmd(args,env=None,input=None):
 p=subprocess.run(args,capture_output=True,text=True,env=env,input=input)
 if p.returncode:raise RuntimeError((p.stdout+p.stderr)[-6000:])
 return p.stdout.strip()
def state():
 st=json.loads((D/'connection.json').read_text())
 actual=cmd(['docker','inspect','--format','{{.Name}}|{{index .Config.Labels "di.phase"}}',st['container']])
 if actual!='/'+st['name']+'|MA-B' or not st['name'].startswith('di-ma-b-'):
  raise RuntimeError('Resource identity mismatch; refusing access outside the owned MA-B harness')
 return st
def sql(query,st=None):
 st=st or state()
 return cmd(['docker','exec','-i',st['container'],'psql','-X','-U','postgres','-d','dateninfrastruktur_management','-v','ON_ERROR_STOP=1','-At'],input=query)
def start():
 if (D/'connection.json').exists():raise RuntimeError('Own harness already exists; use its state or explicit cleanup, never reset')
 password=secrets.token_urlsafe(32);env=os.environ.copy();env['POSTGRES_PASSWORD']=password
 name='di-ma-b-'+secrets.token_hex(6)
 cid=cmd(['docker','run','--detach','--name',name,'--label','di.phase=MA-B','--env','POSTGRES_PASSWORD','--env','POSTGRES_DB=dateninfrastruktur_management','--publish','127.0.0.1::5432',IMAGE],env=env)
 st={'container':cid,'name':name,'password':password}
 f=D/'connection.json';f.write_text(json.dumps(st));f.chmod(0o600)
 for _ in range(90):
  if subprocess.run(['docker','exec',cid,'pg_isready','-h','127.0.0.1','-U','postgres','-d','dateninfrastruktur_management'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
  time.sleep(.5)
 else:raise RuntimeError('Own database not ready')
 st['port']=cmd(['docker','port',cid,'5432/tcp']).rsplit(':',1)[1];f.write_text(json.dumps(st));f.chmod(0o600)
 print('Own isolated PG16/PostGIS harness ready')
def ili_schema():
 st=state(); jar=next((R/'.runtime/tools/ili2pg').glob('ili2pg-*.jar'))
 if sql("SELECT count(*) FROM pg_namespace WHERE nspname='di_management';")!='0':
  if sql("SELECT di_ops.runtime_contract();")!='t':
   raise RuntimeError('Existing schema has unknown/incomplete contract; no automatic schema reimport')
  contract=R/'db/persistence-contract.properties'
  if not contract.exists() or hashlib.sha256(contract.read_bytes()).hexdigest()!=sql('SELECT di_ops.contract_fingerprint();'):
   raise RuntimeError('Existing schema fingerprint differs; explicit reviewed upgrade required')
  print('Known existing schema retained; no ili2pg reimport')
  return
 options=['--createMetaInfo','--createStdCols','--createTidCol','--createFk','--createFkIdx','--createUnique','--createNumChecks','--setupPgExt']
 args=['java','-jar',str(jar),'--schemaimport','--dbhost','127.0.0.1','--dbport',st['port'],'--dbdatabase','dateninfrastruktur_management','--dbusr','postgres','--dbpwd',st['password'],'--dbschema','di_management','--models','SO_AGI_DataInfrastructure_Catalog_20261104;SO_AGI_DataInfrastructure_Access_20261104','--modeldir',str(R/'models'),*options]
 try:log=cmd(args)
 except RuntimeError as e:raise RuntimeError(str(e).replace(st['password'],'[REDACTED]')) from None
 (D/'schemaimport.log').write_text(log.replace(st['password'],'[REDACTED]'))
 print(sql("SELECT iliname||'|'||sqlname FROM di_management.t_ili2db_classname ORDER BY iliname;"))
 print(sql("SELECT column_name||'|'||data_type||'|'||coalesce(column_default,'') FROM information_schema.columns WHERE table_schema='di_management' AND table_name='dataset' ORDER BY ordinal_position;"))
 profile={'schema':'di_management','tools':json.loads((R/'db/toolchain.json').read_text()),'options':options,'models':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((R/'models').glob('*.ili'))}}
 (R/'db/mapping-profile.json').write_text(json.dumps(profile,indent=2)+'\n')
def mapping():
 st=state();env=os.environ.copy();env.update(DI_MAPPING_URL='jdbc:postgresql://127.0.0.1:'+st['port']+'/dateninfrastruktur_management',DI_MAPPING_USER='postgres',DI_MAPPING_PASSWORD=st['password'])
 p=subprocess.run([str(R/'gradlew'),'--no-daemon','cdbimport','cgen'],cwd=R,env=env,capture_output=True,text=True)
 log=(p.stdout+p.stderr).replace(st['password'],'[REDACTED]');(D/'cayenne-mapping.log').write_text(log)
 if p.returncode:raise RuntimeError(log[-7000:])
 print('M3 cdbimport/cgen completed against real ili2pg schema')
def cleanup():
 st=state();label=cmd(['docker','inspect','--format','{{index .Config.Labels "di.phase"}}',st['container']])
 if label!='MA-B':raise RuntimeError('Not our resource; refusing cleanup')
 cmd(['docker','rm','--force','--volumes',st['container']]);(D/'connection.json').unlink();print('Removed only own MA-B container and own anonymous volumes')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('command',choices=['start','schema','mapping','cleanup','inspect']);a=p.parse_args()
 {'start':start,'schema':ili_schema,'mapping':mapping,'cleanup':cleanup,'inspect':lambda:print(sql("SELECT version(); SELECT postgis_full_version(); SELECT * FROM di_management.t_ili2db_attrname LIMIT 12;"))}[a.command]()
