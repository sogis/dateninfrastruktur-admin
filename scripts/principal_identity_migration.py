"""Bounded, private operator. Exact UUID proof; same audited SQL transaction.
No public API, user-name matching, historical rewrite, or implicit identity merge.
"""
import hashlib,json,os,pathlib,sys,uuid
from database_target import sql
from persistence_contract import calculate
def literal(value):return "'"+str(value).replace("'","''")+"'"
def load(path):
    path=pathlib.Path(path);m=json.loads(path.read_text());uuid.UUID(m['operation_id'])
    for name in ['source_export','target_users']:
        proof=m[name];file=pathlib.Path(proof['path'])
        if hashlib.sha256(file.read_bytes()).hexdigest()!=proof['sha256']:raise RuntimeError('IDENTITY_CONFLICT: identity proof changed')
    exported=json.loads(pathlib.Path(m['source_export']['path']).read_text());target=json.loads(pathlib.Path(m['target_users']['path']).read_text())
    source_ids={u['id'] for u in exported['users']};target_ids={u['id'] for u in target}
    if exported.get('realm')!='dateninfrastruktur' or len(source_ids)!=len(exported['users']) or len(target_ids)!=len(target):raise RuntimeError('IDENTITY_CONFLICT: realm or duplicate user UUID')
    ids=set();bindings=set()
    for row in m['mapping']:
        uuid.UUID(row['principal_uuid']);uuid.UUID(row['old_subject']);uuid.UUID(row['new_subject'])
        if row['principal_uuid'] in ids or row['old_subject']!=row['new_subject'] or row['old_subject'] not in source_ids or row['new_subject'] not in target_ids or not row['old_issuer'].startswith('http://') or not row['new_issuer'].startswith('http://') or row['old_issuer']==row['new_issuer']:raise RuntimeError('IDENTITY_CONFLICT: unique exact source/target UUID proof required')
        binding=(row['new_issuer'],row['new_subject'])
        if binding in bindings:raise RuntimeError('IDENTITY_CONFLICT: duplicated target binding')
        bindings.add(binding);ids.add(row['principal_uuid'])
    if len({r['new_issuer'] for r in m['mapping']})!=1:raise RuntimeError('IDENTITY_CONFLICT: one canonical target issuer required')
    if not m['mapping']:raise RuntimeError('IDENTITY_CONFLICT: empty mapping')
    return m

def run(action,path):
    if os.environ.get('PGUSER')!='di_migrator':raise RuntimeError('Bounded migration requires the existing migrator account')
    m=load(path);_,expected=calculate()
    if m['expected_fingerprint']!=expected or sql('SELECT di_ops.contract_fingerprint();')!=expected:raise RuntimeError('VERIFICATION_FAILED: management fingerprint')
    rows=json.loads(sql("SELECT coalesce(json_agg(json_build_object('principal_uuid',t_ili_tid,'issuer',aissuer,'subject',asubject,'kind',akind) ORDER BY t_ili_tid),'[]') FROM di_management.principal;"));by_id={r['principal_uuid']:r for r in rows}
    old=[];new=[]
    for entry in m['mapping']:
        current=by_id.get(entry['principal_uuid'])
        if not current or current['kind']!='user':raise RuntimeError('IDENTITY_CONFLICT: principal missing or technical')
        binding=(current['issuer'],current['subject'])
        if binding==(entry['old_issuer'],entry['old_subject']):old.append(entry)
        elif binding==(entry['new_issuer'],entry['new_subject']):new.append(entry)
        else:raise RuntimeError('IDENTITY_CONFLICT: current binding differs')
        if any(r['principal_uuid']!=entry['principal_uuid'] and (r['issuer'],r['subject'])==(entry['new_issuer'],entry['new_subject']) for r in rows):raise RuntimeError('IDENTITY_CONFLICT: target already bound to another principal')
    selected={e['principal_uuid'] for e in m['mapping']};issuers={e['old_issuer'] for e in m['mapping']}
    if any(r['kind']=='user' and r['issuer'] in issuers and r['principal_uuid'] not in selected for r in rows):raise RuntimeError('IDENTITY_CONFLICT: unmapped user in selected issuer')
    if old and new:raise RuntimeError('IDENTITY_CONFLICT: partial migration state')
    if action=='plan':print('Principal identity mapping verified; '+str(len(old))+' pending, '+str(len(new))+' already applied');return
    if not old:
        if sql("SELECT count(*) FROM audit.row_change WHERE command_id="+literal(m['operation_id'])+" AND action_code='operator.migrate_identity' AND table_name='principal';")!=str(len(new)):raise RuntimeError('VERIFICATION_FAILED: missing migration audit')
        print('Principal identity migration already applied; no changes');return
    if action!='apply':raise RuntimeError('VERIFICATION_FAILED: identity migration not applied')
    # Revalidate all preconditions under row lock inside the same physical transaction.
    command='BEGIN; SELECT pg_advisory_xact_lock(749583299); LOCK TABLE di_management.principal IN SHARE ROW EXCLUSIVE MODE; SELECT t_id FROM di_management.principal FOR UPDATE;'
    for key,value in {'di.actor_issuer':'urn:di:migration','di.actor_subject':m['operation_id'],'di.actor_kind':'service','di.action':'operator.migrate_identity','di.request_id':m['operation_id'],'di.command_id':m['operation_id'],'di.migration_operation':m['operation_id'],'di.migration_target_issuer':m['mapping'][0]['new_issuer']}.items():command+='SELECT set_config('+literal(key)+','+literal(value)+',true);'
    command+="DO $migration$ BEGIN IF EXISTS(SELECT 1 FROM di_management.principal WHERE akind='user' AND aissuer IN ("+','.join(literal(v) for v in issuers)+") AND t_ili_tid NOT IN ("+','.join(literal(v)+'::uuid' for v in selected)+")) THEN RAISE EXCEPTION 'IDENTITY_CONFLICT'; END IF; END $migration$;"
    for entry in old:
        predicate='t_ili_tid='+literal(entry['principal_uuid'])+'::uuid AND aissuer='+literal(entry['old_issuer'])+' AND asubject='+literal(entry['old_subject'])+" AND akind='user'"
        command+='DO $migration$ BEGIN IF (SELECT count(*) FROM di_management.principal WHERE '+predicate+")<>1 THEN RAISE EXCEPTION 'IDENTITY_CONFLICT'; END IF; END $migration$;"
        command+='UPDATE di_management.principal SET aissuer='+literal(entry['new_issuer'])+',asubject='+literal(entry['new_subject'])+' WHERE '+predicate+';'
    command+='COMMIT;';sql(command)
    run('verify',path);print('Audited current principal bindings migrated; principal UUIDs and historical records retained')
if __name__=='__main__':
    try:
        if len(sys.argv)!=3 or sys.argv[1] not in ['plan','apply','verify']:raise RuntimeError('Usage: principal_identity_migration.py plan|apply|verify <private mapping>')
        run(sys.argv[1],sys.argv[2])
    except Exception as error:print(str(error) if type(error) is RuntimeError else 'IDENTITY_MIGRATION_FAILED: details withheld',file=sys.stderr);sys.exit(1)
