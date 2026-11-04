#!/usr/bin/env python3
"""One fresh, bounded MA-B integration run; no user services, env files or data."""
import hashlib, json, os, subprocess, sys, uuid, shutil, xml.etree.ElementTree as E
from pathlib import Path
from management_db import R, D, start, state, sql, cmd, ili_schema, mapping, cleanup
import runtime_lock

def run(args, env=None):
    result=subprocess.run(args,cwd=R,env=env,capture_output=True,text=True)
    name='step-'+str(len(list(D.glob('step-*.log'))))
    log=result.stdout+result.stderr
    for value in credentials:
        log=log.replace(value,'[REDACTED]')
    (D/(name+'.log')).write_text(log)
    print(log[-6000:],flush=True)
    if result.returncode:
        raise RuntimeError('integration step failed; see '+name+'.log')
    if 'integrationTest' in args:
        reports=list((R/'build/test-results/integrationTest').glob('TEST-*.xml'))
        suites=[E.parse(path).getroot() for path in reports]
        if not suites or any(int(x.get('tests','0'))==0 or int(x.get('skipped','0')) or
                int(x.get('errors','0')) or int(x.get('failures','0')) for x in suites):
            raise RuntimeError('Mandatory JUnit execution missing, skipped or failed')
        for i,arg in enumerate(args):
            if arg=='--tests':
                suffix=args[i+1].lstrip('*')
                if not any(x.get('name','').endswith(suffix) for x in suites):
                    raise RuntimeError('Requested JUnit class was not executed: '+suffix)
        destination=D/(name+'-junit');destination.mkdir(exist_ok=True)
        for report in reports:shutil.copyfile(report,destination/report.name)

def snapshot():
    paths=[R/'src/main/resources/cayenne/management.map.xml',*(R/'src/main/java/ch/so/agi/dateninfrastruktur/admin/model').rglob('*.java')]
    return {str(p.relative_to(R)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths if p.exists()}

credentials=[]
if (D/'connection.json').exists():
    raise SystemExit('Existing own harness retained. Explicitly clean it with scripts/management_db.py cleanup before a fresh test.')
# Reuse the exact pinned INTERLIS downloads without requiring a previous phase run.
for name,item in json.loads((R/'db/toolchain.json').read_text()).items():
    target=R/'.runtime/tools'/name
    if not target.exists():
        import urllib.request,zipfile
        data=urllib.request.urlopen(item['url']).read()
        if hashlib.sha256(data).hexdigest()!=item['sha256']:raise RuntimeError('tool hash mismatch')
        target.mkdir(parents=True)
        archive=target.parent/(name+'.zip');archive.write_bytes(data)
        with zipfile.ZipFile(archive) as zipped:zipped.extractall(target)
started=False
try:
    start();started=True
    st=state();credentials.append(st['password'])
    ili_schema()
    roles={name:uuid.uuid4().hex+uuid.uuid4().hex for name in
        ['di_migrator','di_writer','di_authz','di_public','di_audit_reader']}
    credentials+=list(roles.values())
    (D/'roles.json').write_text(json.dumps(roles));(D/'roles.json').chmod(0o600)
    statements=['CREATE ROLE di_owner NOLOGIN;','CREATE ROLE di_audit_owner NOLOGIN;']
    for name,password in roles.items():
        statements.append(f"CREATE ROLE {name} LOGIN PASSWORD '{password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;")
    statements+=['GRANT di_owner,di_audit_owner TO di_migrator;',
        'REVOKE ALL ON DATABASE dateninfrastruktur_management FROM PUBLIC;',
        'GRANT CONNECT ON DATABASE dateninfrastruktur_management TO '+','.join(roles)+';']
    sql('\n'.join(statements))
    sql((R/'db/migrations/V001__audit.sql').read_text())
    run(['python3','scripts/audit_setup.py','apply'])
    # A real additive migration over existing UUID/audit rows, not an empty schema.
    env=os.environ.copy()
    env.update(DI_TEST_URL='jdbc:postgresql://127.0.0.1:'+st['port']+'/dateninfrastruktur_management',
        DI_TEST_WRITER_PASSWORD=roles['di_writer'],DI_TEST_AUTHZ_PASSWORD=roles['di_authz'],
        DI_TEST_ADMIN_PASSWORD=st['password'])
    mapping()
    run([str(R/'gradlew'),'--no-daemon','integrationTest','--tests','*AuditedUnitOfWorkIT'],env)
    preserve="SELECT count(*),md5(string_agg(t_ili_tid::text,',' ORDER BY t_id)) FROM di_management.dataset; SELECT count(*) FROM audit.row_change;"
    baseline=sql(preserve)
    if baseline.splitlines()[0].startswith('0|'):raise RuntimeError('upgrade baseline is empty')
    run(['python3','scripts/apply_constraints.py'])
    if baseline!=sql(preserve):raise RuntimeError('additive migration changed baseline data/audit')
    sql((R/'db/migrations/V003__public_views.sql').read_text())
    sql((R/'db/migrations/V004__runtime_contract.sql').read_text())
    sql((R/'db/migrations/V005__aggregate_revision.sql').read_text())
    from audit_setup import resolve,ident
    for policy in resolve():
        if policy['table']!='dataset' and policy['dataset_path']:
            sql('CREATE TRIGGER di_aggregate_revision AFTER INSERT OR UPDATE OR DELETE ON di_management.'+
                ident(policy['table'])+' FOR EACH ROW EXECUTE FUNCTION di_ops.touch_aggregate();')
    mapping()
    first=snapshot();mapping()
    if first!=snapshot():raise RuntimeError('second cdbimport/cgen changed generated product bytes')
    run(['python3','scripts/contract_fingerprint.py'])
    print('M07 PASS: second M3 cdbimport/cgen is byte-identical; real shared sequence mapping')
    run([str(R/'gradlew'),'--no-daemon','integrationTest',
        '--tests','*AuditedUnitOfWorkIT','--tests','*ManagementStartupIT'],env)
    print('M08 PASS: real Cayenne actor/transaction/rollback/pool, Boot mapping startup')
    run(['python3','scripts/test_persistence.py'])
    # Export actual ORM-written object graph with ili2pg and validate independently.
    exported=D/'roundtrip.xtf'
    args=['java','-jar',str(next((R/'.runtime/tools/ili2pg').glob('ili2pg-*.jar'))),
        '--export','--dbhost','127.0.0.1','--dbport',st['port'],'--dbdatabase','dateninfrastruktur_management',
        '--dbusr','postgres','--dbpwd',st['password'],'--dbschema','di_management',
        '--models','SO_AGI_DataInfrastructure_Catalog_20261104;SO_AGI_DataInfrastructure_Access_20261104',
        '--modeldir',str(R/'models'),'--exportTid',str(exported)]
    run(args)
    run(['java','-jar',str(next((R/'.runtime/tools/ilivalidator').glob('ilivalidator-*.jar'))),
        '--modeldir',str(R/'models'),'--allObjectsAccessible','--forceTypeValidation',str(exported)])
    expected=sql('SELECT t_ili_tid FROM di_management.dataset ORDER BY t_id;').splitlines()
    body=exported.read_text()
    if not all(value in body for value in expected):raise RuntimeError('UUID lost during export')
    if 'Grüezi' not in body or 'Mehrzeilig' not in body:raise RuntimeError('Unicode MTEXT lost during export')
    print('M07 PASS: actual ili2pg export validates and preserves UUIDs/Unicode/relationships')
    print('PASS MA-B M07-M13 fresh integration run')
finally:
    if started:cleanup()
