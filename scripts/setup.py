#!/usr/bin/env python3
"""Explicit local Management setup/rerun. Existing own data and credentials are preserved."""
import json, secrets
from management_db import R,D,start,state,sql,ili_schema,mapping,cmd
import runtime_lock
from audit_setup import apply,resolve,ident
from interlis_tools import ensure
ensure()

if not (D/'connection.json').exists():
    start()
st=state()
ili_schema()
file=D/'roles.json'
if file.exists():
    roles=json.loads(file.read_text())
else:
    roles={name:secrets.token_urlsafe(32) for name in
        ['di_migrator','di_writer','di_authz','di_public','di_audit_reader']}
    file.write_text(json.dumps(roles));file.chmod(0o600)
if sql("SELECT count(*) FROM pg_roles WHERE rolname='di_writer';")=='0':
    commands=['CREATE ROLE di_owner NOLOGIN;','CREATE ROLE di_audit_owner NOLOGIN;']
    for name,password in roles.items():
        commands.append("CREATE ROLE "+name+" LOGIN PASSWORD '"+password+"' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;")
    commands+=['GRANT di_owner,di_audit_owner TO di_migrator;',
        'REVOKE ALL ON DATABASE dateninfrastruktur_management FROM PUBLIC;',
        'GRANT CONNECT ON DATABASE dateninfrastruktur_management TO '+','.join(roles)+';']
    sql('\n'.join(commands))
sql((R/'db/migrations/V001__audit.sql').read_text())
apply(resolve())
cmd(['python3',str(R/'scripts/apply_constraints.py')])
for name in ['V003__public_views.sql','V004__runtime_contract.sql','V005__aggregate_revision.sql']:
    sql((R/'db/migrations'/name).read_text())
for policy in resolve():
    if policy['table']!='dataset' and policy['dataset_path']:
        table=ident(policy['table'])
        sql('DROP TRIGGER IF EXISTS di_aggregate_revision ON di_management.'+table+';'
            'CREATE TRIGGER di_aggregate_revision AFTER INSERT OR UPDATE OR DELETE ON di_management.'+
            table+' FOR EACH ROW EXECUTE FUNCTION di_ops.touch_aggregate();')
mapping()
cmd(['python3',str(R/'scripts/verify_physical_mapping.py')])
cmd(['python3',str(R/'scripts/upgrade_contract.py')])
cmd(['python3',str(R/'scripts/contract_fingerprint.py')])
print('Management setup ready. Existing UUIDs, grants, decisions, audit and credentials retained. No demo reset.')
