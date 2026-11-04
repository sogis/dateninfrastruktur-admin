#!/usr/bin/env python3
"""Compose tooling entry point: explicit empty setup or read-only current-contract verification.
Never starts a database, generates ORM sources, or rewrites pinned artifacts.
"""
import argparse,json,os,pathlib,subprocess,tempfile
from management_db import R,D,sql,TARGET_MODE
from audit_setup import apply,resolve,verify,literal,ident
from persistence_contract import calculate
from verify_physical_mapping import verify as verify_mapping
def verify_existing():
    _,expected=calculate()
    if sql('SELECT di_ops.runtime_contract();')!='t' or sql('SELECT di_ops.contract_fingerprint();')!=expected:raise RuntimeError('Existing contract differs; explicit reviewed migration required')
    verify_mapping();verify(resolve());print('Existing management contract verified read-only; data/rights/audit retained')
def setup():
    if not TARGET_MODE:raise RuntimeError('Compose setup requires an explicit PostgreSQL target')
    if sql("SELECT count(*) FROM pg_namespace WHERE nspname='di_management';")!='0':verify_existing();return
    if sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='r' AND n.nspname NOT IN ('pg_catalog','information_schema');")!='0':raise RuntimeError('Target management database is not empty')
    commands=['CREATE ROLE di_owner NOLOGIN;','CREATE ROLE di_audit_owner NOLOGIN;']
    for role in ['di_migrator','di_writer','di_authz','di_public','di_audit_reader']:
        password=os.environ.get(role.upper()+'_PASSWORD','')
        if not password:raise RuntimeError('Explicit role credential missing: '+role)
        if sql('SELECT count(*) FROM pg_roles WHERE rolname='+literal(role)+';')=='0':commands.append('CREATE ROLE '+ident(role)+' LOGIN PASSWORD '+literal(password)+' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;')
    # NOLOGIN owner roles can already exist from a controlled role-only restore.
    commands=[q for q in commands if not q.startswith('CREATE ROLE di_owner') or sql("SELECT count(*) FROM pg_roles WHERE rolname='di_owner';")=='0']
    commands=[q for q in commands if not q.startswith('CREATE ROLE di_audit_owner') or sql("SELECT count(*) FROM pg_roles WHERE rolname='di_audit_owner';")=='0']
    commands+=['GRANT di_owner,di_audit_owner TO di_migrator;','REVOKE ALL ON DATABASE dateninfrastruktur_management FROM PUBLIC;','GRANT CONNECT ON DATABASE dateninfrastruktur_management TO di_migrator,di_writer,di_authz,di_public,di_audit_reader;']
    sql('BEGIN;'+''.join(commands)+'COMMIT;')
    jar=next((R/'.runtime/tools/ili2pg').glob('ili2pg-*.jar'))
    args=['-jar',str(jar),'--schemaimport','--dbhost',os.environ['PGHOST'],'--dbport',os.environ['PGPORT'],'--dbdatabase',os.environ['PGDATABASE'],'--dbusr',os.environ['PGUSER'],'--dbpwd',os.environ['PGPASSWORD'],'--dbschema','di_management','--models','SO_AGI_DataInfrastructure_Catalog_20261104;SO_AGI_DataInfrastructure_Access_20261104','--modeldir',str(R/'models'),*json.loads((R/'db/mapping-profile.json').read_text())['options']]
    # Java @argfile keeps the password out of OS process arguments.
    with tempfile.NamedTemporaryFile(mode='w',dir=D,suffix='.args',delete=False) as f:
        f.write('\n'.join('"'+a.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n')+'"' for a in args));path=pathlib.Path(f.name)
    try:
        result=subprocess.run(['java','@'+str(path)],capture_output=True,text=True)
        if result.returncode:raise RuntimeError('Pinned ili2pg schema import failed (credentials/SQL withheld)')
    finally:path.unlink()
    sql((R/'db/migrations/V001__audit.sql').read_text());apply(resolve())
    subprocess.run(['python3',str(R/'scripts/apply_constraints.py')],check=True)
    for name in ['V003__public_views.sql','V004__runtime_contract.sql','V005__aggregate_revision.sql'] :sql((R/'db/migrations'/name).read_text())
    for policy in resolve():
        if policy['table']!='dataset' and policy['dataset_path']:
            table=ident(policy['table']);sql('CREATE TRIGGER di_aggregate_revision AFTER INSERT OR UPDATE OR DELETE ON di_management.'+table+' FOR EACH ROW EXECUTE FUNCTION di_ops.touch_aggregate();')
    # Use the unchanged accepted migration SQL and fingerprint, without writing source files.
    sql((R/'db/migrations/V006__contract_fingerprint.sql').read_text())
    from upgrade_contract import apply as upgrade
    upgrade();_,fingerprint=calculate()
    sql("INSERT INTO di_ops.contract_fingerprint(singleton,fingerprint) VALUES(true,'"+fingerprint+"');")
    verify_existing();print('Explicit empty management setup completed; no demo data or grants')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['setup','verify']);a=parser.parse_args()
    if not TARGET_MODE:raise RuntimeError('No explicit database target')
    setup() if a.action=='setup' else verify_existing()
