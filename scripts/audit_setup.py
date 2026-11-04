#!/usr/bin/env python3
"""Resolve the real ili2db mapping, install and verify complete audit coverage."""
import argparse, hashlib, json, re
from pathlib import Path
from management_db import R, D, state, sql, TARGET_MODE

def ident(s):
 if not re.fullmatch(r'[a-z_][a-z0-9_]*',s):raise ValueError('unsafe identifier')
 return '"'+s+'"'
def literal(s):return "'"+s.replace("'","''")+"'"
def rows(query):return [json.loads(line) for line in sql('SELECT row_to_json(x) FROM ('+query+')x;').splitlines()]
def resolve():
 mappings=rows('SELECT * FROM di_management.t_ili2db_attrname');classes=rows('SELECT * FROM di_management.t_ili2db_classname')
 meta=rows("SELECT * FROM di_management.t_ili2db_meta_attrs WHERE attr_name LIKE 'di.%'")
 physical={x['tablename'] for x in rows("SELECT tablename FROM pg_tables WHERE schemaname='di_management' AND tablename NOT LIKE 't_ili2db_%'")}
 elements={x['iliname']:x['sqlname'] for x in classes}
 policies={t:{'table':t,'model_elements':[],'redacted_columns':[],'dataset_path':[],'protection':'normal'} for t in physical}
 for m in meta:
  if m['attr_name']=='di.audit' and m['attr_value']=='full':
   element=m['ilielement'];table=elements[element]
   if table in physical:targets={table}
   else:targets={x['colowner'] for x in mappings if x['iliname'].startswith(element+'.')}
   if not targets:raise RuntimeError('unresolved audited model element: '+element)
   for t in targets:
    if t not in policies:raise RuntimeError('unresolved physical table: '+t)
    policies[t]['model_elements'].append(element)
  elif m['attr_name']=='di.audit.redact' and m['attr_value']=='true':
   matches=[x for x in mappings if x['iliname']==m['ilielement']]
   if len(matches)!=1:raise RuntimeError('ambiguous redaction mapping')
   policies[matches[0]['colowner']]['redacted_columns'].append(matches[0]['sqlname'])
 # All parent paths are resolved from actual role mappings, not class-name guesses.
 links={}
 for x in mappings:
  if x['target']:links.setdefault(x['colowner'],[]).append((x['sqlname'],x['target']))
 root=elements['SO_AGI_DataInfrastructure_Catalog_20261104.Catalog.Dataset']
 for t,p in policies.items():
  if not p['model_elements']:raise RuntimeError('physical table without audit policy: '+t)
  p['model_elements'].sort();p['redacted_columns'].sort()
  def path(table,seen):
   if table==root:return []
   for col,target in sorted(links.get(table,[]),key=lambda x:x[1]!=root):
    if col in ('currentissue','currentversion') or target in seen:continue
    rest=path(target,seen|{target})
    if rest is not None:return [{'column':col,'table':target}]+rest
   return None
  p['dataset_path']=path(t,{t}) or []
  models=[x.rsplit('.',1)[-1] for x in p['model_elements']]
  if any(x in models for x in ['AccessDecision','CatalogAction']):p['protection']='immutable'
  if 'AccessGrant' in models:p['protection']='grant'
 return sorted(policies.values(),key=lambda x:x['table'])
def verify(policies):
 actual=rows("SELECT c.relname AS table_name,t.tgname,t.tgtype,t.tgenabled,p.proname,n.nspname FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace s ON s.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_namespace n ON n.oid=p.pronamespace WHERE s.nspname='di_management' AND NOT t.tgisinternal")
 for policy in policies:
  for name,fn,kind in [('di_audit_stamp','stamp_row',31),('di_audit_log','log_row_change',29)]:
   found=[x for x in actual if x['table_name']==policy['table'] and x['tgname']==name and x['proname']==fn and x['nspname']=='audit' and x['tgenabled']=='O' and x['tgtype']==kind]
   if len(found)!=1:raise RuntimeError('audit trigger coverage failed: '+policy['table']+'/'+name)
 installed=rows('SELECT * FROM audit.table_policy ORDER BY table_name')
 for p,x in zip(policies,installed):
  if p['table']!=x['table_name'] or p['redacted_columns']!=x['redacted_columns'] or p['dataset_path']!=x['dataset_path']:raise RuntimeError('audit policy mismatch')
 if len(installed)!=len(policies):raise RuntimeError('audit table set mismatch')
 print('PASS audit coverage: '+str(len(policies))+' physical tables, timing/events/enabled/functions/policies verified')
def apply(policies):
 if TARGET_MODE and policies!=json.loads((R/'db/audit-policy.json').read_text()):raise RuntimeError('Runtime audit policy differs from the pinned physical mapping')
 commands=['BEGIN;','SELECT pg_advisory_xact_lock(749583200);']
 for p in policies:
  t=ident(p['table'])
  commands+=['ALTER TABLE di_management.'+t+' OWNER TO di_owner;', 'ALTER TABLE di_management.'+t+' ADD COLUMN IF NOT EXISTS row_version bigint NOT NULL DEFAULT 1;',
   'INSERT INTO audit.table_policy VALUES('+','.join([literal(p['table']),literal('{'+','.join(p['model_elements'])+'}')+'::text[]',literal('{'+','.join(p['redacted_columns'])+'}')+'::text[]',literal(json.dumps(p['dataset_path']))+'::jsonb',literal(p['protection'])])+') ON CONFLICT(table_name) DO UPDATE SET model_elements=EXCLUDED.model_elements,redacted_columns=EXCLUDED.redacted_columns,dataset_path=EXCLUDED.dataset_path,protection=EXCLUDED.protection;',
   'DROP TRIGGER IF EXISTS di_audit_stamp ON di_management.'+t+';', 'CREATE TRIGGER di_audit_stamp BEFORE INSERT OR UPDATE OR DELETE ON di_management.'+t+' FOR EACH ROW EXECUTE FUNCTION audit.stamp_row();',
   'DROP TRIGGER IF EXISTS di_audit_log ON di_management.'+t+';', 'CREATE TRIGGER di_audit_log AFTER INSERT OR UPDATE OR DELETE ON di_management.'+t+' FOR EACH ROW EXECUTE FUNCTION audit.log_row_change();',
   'GRANT SELECT,INSERT,UPDATE,DELETE ON di_management.'+t+' TO di_writer;', ('GRANT SELECT ON di_management.'+t+' TO di_authz;') if any(x.rsplit('.',1)[-1] in ('Dataset','DatasetIssue','DataVersion','Distribution','Principal','AccessGrant','GroupMembership') for x in p['model_elements']) else ('REVOKE ALL ON di_management.'+t+' FROM di_authz;')]
 commands+=['ALTER SEQUENCE di_management.t_ili2db_seq OWNER TO di_owner;','GRANT USAGE,SELECT ON SEQUENCE di_management.t_ili2db_seq TO di_writer;',"INSERT INTO di_ops.schema_revision(revision) VALUES('MA-B-1') ON CONFLICT DO NOTHING;",'COMMIT;']
 sql('\n'.join(commands));verify(policies)
 if not TARGET_MODE:(R/'db/audit-policy.json').write_text(json.dumps(policies,indent=2)+'\n')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('mode',choices=['plan','apply','verify']);a=p.parse_args();policies=resolve()
 if a.mode=='plan':print(json.dumps(policies,indent=2))
 elif a.mode=='apply':apply(policies)
 else:verify(policies)
