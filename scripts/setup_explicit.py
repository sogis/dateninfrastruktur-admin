#!/usr/bin/env python3
"""Explicit empty management setup and read-only verification of the current baseline."""
import argparse
import json
import os
import pathlib
import subprocess
import tempfile

from audit_setup import ident, installation_sql, literal, resolve, verify as verify_audit
from management_db import D, R, TARGET_MODE, sql
from persistence_contract import PROPERTIES, calculate, input_path, load_manifest, physical_expected
from verify_physical_mapping import verify as verify_mapping

WRITER_ROLES=('di_writer','di_authz','di_public','di_audit_reader')
OWNERS=('di_owner','di_audit_owner')
STRUCTURAL=('di_ops.runtime_contract() AND di_ops.single_storage_contract() '
            'AND di_ops.ingestion_contract() AND di_ops.management_web_contract()')


def verify_structure():
    physical=sql('SELECT di_ops.physical_fingerprint();')
    if physical!=physical_expected() or sql(f'SELECT {STRUCTURAL};')!='t':
        raise RuntimeError('Management schema, grants or trigger definitions differ from the current baseline')
    verify_mapping()
    verify_audit(resolve())


def packaged_contract():
    body,artifact=calculate()
    if (R/PROPERTIES).read_bytes()!=body.encode('utf-8'):
        raise RuntimeError('Packaged schema inputs and persistence contract differ')
    return artifact


def verify_existing(manifest, artifact):
    marker=sql('SELECT di_ops.contract_fingerprint();')
    baseline=literal(manifest['baseline'])
    if marker!=artifact or sql(f'SELECT EXISTS(SELECT 1 FROM di_ops.schema_revision WHERE revision={baseline});')!='t':
        raise RuntimeError('Management database is not marked as the current baseline')
    verify_structure()
    print('Current management baseline verified read-only')


def prepare_roles():
    names=','.join(map(literal,OWNERS+WRITER_ROLES))
    if sql(f'SELECT count(*) FROM pg_roles WHERE rolname IN ({names});')!='0':
        raise RuntimeError('Existing cluster roles require an explicitly verified management installation')
    commands=[f'CREATE ROLE {ident(role)} NOLOGIN;' for role in OWNERS]
    for role in WRITER_ROLES:
        password=os.environ.get(role.upper()+'_PASSWORD')
        if not password:
            raise RuntimeError(f'Missing credential for {role}')
        commands.append(f'CREATE ROLE {ident(role)} LOGIN PASSWORD {literal(password)} '
                        'NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;')
    role_list=','.join(map(ident,WRITER_ROLES))
    commands+=['REVOKE ALL ON DATABASE dateninfrastruktur_management FROM PUBLIC;',
               f'GRANT CONNECT ON DATABASE dateninfrastruktur_management TO {role_list};']
    sql('BEGIN;'+''.join(commands)+'COMMIT;')


def import_models(manifest):
    profile=json.loads(input_path(R,manifest['artifacts']['profile.sha256']).read_text())
    version=profile['tools']['ili2pg']['version']
    jar=R/'.runtime/tools/ili2pg'/f'ili2pg-{version}.jar'
    models=';'.join(pathlib.Path(p).stem for p in manifest['models'])
    args=['-jar',str(jar),'--schemaimport','--dbhost',os.environ['PGHOST'],'--dbport',os.environ['PGPORT'],
          '--dbdatabase',os.environ['PGDATABASE'],'--dbusr',os.environ['PGUSER'],'--dbpwd',os.environ['PGPASSWORD'],
          '--dbschema','di_management','--models',models,'--modeldir',str(R/'models'),*profile['options']]
    with tempfile.NamedTemporaryFile(mode='w',dir=D,suffix='.args',delete=False) as f:
        f.write('\n'.join('"'+a.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n')+'"' for a in args))
        path=pathlib.Path(f.name)
    try:
        result=subprocess.run(['java','@'+str(path)],capture_output=True,text=True)
        if result.returncode:
            raise RuntimeError('Pinned ili2pg schema import failed')
    finally:
        path.unlink()


def catalog_keys(policies):
    commands=[]
    for policy in policies:
        name=policy['table']
        table='di_management.'+ident(name)
        commands+=['ALTER TABLE '+table+' ALTER COLUMN t_ili_tid SET NOT NULL;',
                   'CREATE UNIQUE INDEX '+ident('di_uuid_'+name)+' ON '+table+'(t_ili_tid);']
    return '\n'.join(commands)


def catalog_triggers(policies):
    commands=[]
    for p in policies:
        name=p['table']
        table='di_management.'+ident(name)
        if name in {'dataset','datasetissue','dataversion','accessdecision','accessgrant','groupmembership','accessrequest'}:
            commands.append('CREATE CONSTRAINT TRIGGER di_validate_model AFTER INSERT OR UPDATE ON '+table+
                ' DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION di_ops.validate_references();')
        if name in {'dataset','datasetissue','dataversion','distribution','dataschema','attributedefinition','accessrequest'}:
            commands.append('CREATE TRIGGER di_protect_delivery BEFORE UPDATE OR DELETE ON '+table+
                ' FOR EACH ROW EXECUTE FUNCTION di_ops.protect_delivery();')
        if name!='dataset' and p['dataset_path']:
            commands.append('CREATE TRIGGER di_aggregate_revision AFTER INSERT OR UPDATE OR DELETE ON '+table+
                ' FOR EACH ROW EXECUTE FUNCTION di_ops.touch_aggregate();')
    return '\n'.join(commands)


def install(manifest):
    prepare_roles()
    import_models(manifest)
    policies=resolve()
    for step in manifest['steps']:
        if step=='audit_mapping':
            body=installation_sql(policies)
        elif step=='catalog_keys':
            body=catalog_keys(policies)
        elif step=='catalog_triggers':
            body=catalog_triggers(policies)
        else:
            body=input_path(R,step).read_text()
        sql('BEGIN;\n'+body+'\nCOMMIT;')


def require_empty():
    if os.environ.get('PGDATABASE')!='dateninfrastruktur_management':
        raise RuntimeError('Setup requires the bound management database')
    unknown=sql("""
        SELECT count(*) FROM pg_namespace
        WHERE nspname NOT IN ('pg_catalog','information_schema','public')
        AND nspname NOT LIKE 'pg_toast%' AND nspname NOT LIKE 'pg_temp%';
    """)
    public_objects=sql("""
        SELECT (SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace)
             + (SELECT count(*) FROM pg_proc WHERE pronamespace='public'::regnamespace)
             + (SELECT count(*) FROM pg_type WHERE typnamespace='public'::regnamespace)
             + (SELECT count(*) FROM pg_extension WHERE extnamespace='public'::regnamespace);
    """)
    if unknown!='0' or public_objects!='0':
        raise RuntimeError('Refusing to install on a nonempty or partial management database')


def setup(manifest, artifact):
    if sql("SELECT to_regclass('di_ops.contract_fingerprint') IS NOT NULL;")=='t':
        verify_existing(manifest,artifact)
        return
    require_empty()
    install(manifest)
    verify_structure()
    baseline=literal(manifest['baseline'])
    sql(f'BEGIN; INSERT INTO di_ops.schema_revision(revision) VALUES({baseline}); '
        f'INSERT INTO di_ops.contract_fingerprint(singleton,fingerprint) VALUES(true,{literal(artifact)}); COMMIT;')
    verify_existing(manifest,artifact)
    print('Current empty management schema installed')


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=['setup','verify'])
    args=parser.parse_args()
    if not TARGET_MODE:
        raise RuntimeError('Setup or verify requires an explicit management database target')
    manifest=load_manifest()
    fingerprint=packaged_contract()
    if args.action=='setup':
        setup(manifest,fingerprint)
    else:
        verify_existing(manifest,fingerprint)
