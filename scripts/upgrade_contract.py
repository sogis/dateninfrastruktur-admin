"""Explicit known MA-F→MA-G1 upgrade. Never invoked by normal startup or fresh setup."""
import hashlib,json,re
from management_db import R,sql
from persistence_contract import calculate

def plan():return json.loads((R/'db/upgrade/ma-f-to-ma-g1.json').read_text())
def recognized_contract(current):return current in (plan()['fromFingerprint'],calculate()[1])
def literal(value):
    if value is None:return 'NULL'
    return "'"+str(value).replace("'","''")+"'"
def ident(value):
    if not re.fullmatch('[a-z_][a-z0-9_]*',value):raise RuntimeError('Unknown generated SQL identifier')
    return '"'+value+'"'
def rows(query):return [json.loads(line) for line in sql('SELECT row_to_json(x) FROM ('+query+')x;').splitlines()]
def canonical(rows):return sorted(json.dumps(row,sort_keys=True) for row in rows)
def verify_predecessor():
    path=R/'db/upgrade/ma-f-mapping.json'
    if hashlib.sha256(path.read_bytes()).hexdigest()!=plan()['sourceMappingSha256']:raise RuntimeError('Pinned predecessor mapping changed')
    old=json.loads(path.read_text())
    for key,table in [('classes','t_ili2db_classname'),('attributes','t_ili2db_attrname'),('meta','t_ili2db_meta_attrs')]:
        if canonical(rows('SELECT * FROM di_management.'+table))!=canonical(old[key]):raise RuntimeError('Unknown predecessor ILI metadata: '+table)
    expected={(x['table_name'],x['column_name']):x for x in old['columns']}
    actual={(x['table_name'],x['column_name']):x for x in rows("SELECT table_name,column_name,data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema='di_management'")}
    tables={p['table'] for p in json.loads((R/'db/audit-policy.json').read_text())}
    if set(actual)!=set(expected)|{(table,'row_version') for table in tables}:raise RuntimeError('Unknown predecessor column set')
    for key,wanted in expected.items():
        observed=actual[key]
        if any(observed[k]!=wanted[k] for k in ['data_type','column_default']):raise RuntimeError('Unknown predecessor type/default')
        if observed['is_nullable']!=wanted['is_nullable'] and not (key[1]=='t_ili_tid' and observed['is_nullable']=='NO'):raise RuntimeError('Unknown predecessor nullability')
    if sql('SELECT di_ops.runtime_contract();')!='t':raise RuntimeError('Predecessor audit coverage invalid')
    return tables

def apply():
    target=calculate()[1];current=sql('SELECT fingerprint FROM di_ops.contract_fingerprint WHERE singleton;')
    if current==target:
        if sql('SELECT di_ops.single_storage_contract();')!='t':raise RuntimeError('Current storage schema guard failed')
        print('Current reviewed contract verified; no migration repeated');return
    if current!=plan()['fromFingerprint']:raise RuntimeError('Unknown predecessor fingerprint; no schema mutation')
    tables=verify_predecessor();metadata=json.loads((R/'db/reference-ma-g1-metadata.json').read_text())
    allowed={'t_ili2db_model','t_ili2db_classname','t_ili2db_attrname','t_ili2db_meta_attrs','t_ili2db_trafo','t_ili2db_column_prop','t_ili2db_table_prop','t_ili2db_inheritance'}
    if set(metadata)!=allowed:raise RuntimeError('Unexpected ILI metadata replacement set')
    commands=['BEGIN;','LOCK TABLE di_ops.contract_fingerprint IN EXCLUSIVE MODE;',"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM di_ops.contract_fingerprint WHERE singleton AND fingerprint="+literal(current)+") THEN RAISE EXCEPTION 'Concurrent contract change'; END IF; END $$;"]
    for table in sorted(tables):commands.append('LOCK TABLE di_management.'+ident(table)+' IN SHARE ROW EXCLUSIVE MODE;')
    commands.append('CREATE TEMP TABLE retained_hash(name text PRIMARY KEY,digest bytea) ON COMMIT DROP;')
    def row_hash(schema,table,order):
        return "SELECT sha256(convert_to(coalesce(string_agg((to_jsonb(r)-ARRAY['storageprofile','legacyaccessurl'])::text,E'\\n' ORDER BY "+order+"),''),'UTF8')) FROM "+schema+'.'+ident(table)+' r'
    for table in sorted(tables):
        commands.append('INSERT INTO retained_hash VALUES('+literal(table)+',('+row_hash('di_management',table,'t_id')+'));')
    commands.append("INSERT INTO retained_hash VALUES('historical_audit',("+row_hash('audit','row_change','change_id')+"));")
    commands.append((R/'db/migrations/V010__single_storage.sql').read_text())
    for table,records in metadata.items():
        commands.append('DELETE FROM di_management.'+ident(table)+';')
        for record in records:
            columns=','.join(ident(k) for k in record)
            values=','.join(literal(v) for v in record.values())
            if table=='t_ili2db_model':
                # Import time is instance data, excluded from deterministic reference metadata.
                columns+=',importdate';values+=',CURRENT_TIMESTAMP'
            commands.append('INSERT INTO di_management.'+ident(table)+'('+columns+') VALUES('+values+');')
    commands.append((R/'db/storage-contract.sql').read_text())
    for table in sorted(tables):
        commands.append("DO $$ BEGIN IF (SELECT digest FROM retained_hash WHERE name="+literal(table)+') IS DISTINCT FROM ('+row_hash('di_management',table,'t_id')+") THEN RAISE EXCEPTION 'Business data changed during model migration'; END IF; END $$;")
    commands.append("DO $$ BEGIN IF (SELECT digest FROM retained_hash WHERE name='historical_audit') IS DISTINCT FROM ("+row_hash('audit','row_change','change_id')+") THEN RAISE EXCEPTION 'Historical audit changed'; END IF; END $$;")
    commands.append('UPDATE di_ops.contract_fingerprint SET fingerprint='+literal(target)+' WHERE singleton;COMMIT;')
    sql('\n'.join(commands));print('Explicit MA-F→MA-G1 schema/metadata migration committed; business identities and audit preserved')
if __name__=='__main__':apply()
