#!/usr/bin/env python3
"""Fresh MA-C evidence. Every resource is checkout-local or uniquely labeled; always clean own resources."""
import os,subprocess,json,shutil,xml.etree.ElementTree as E
from management_db import R,D,state,cleanup

if (D/'connection.json').exists() or (R/'.runtime/identity/state.json').exists():raise SystemExit('Existing own runtime retained. Stop its identity runtime and database explicitly before a fresh MA-C suite.')
report=R/'.runtime/management-c-evidence';report.mkdir(exist_ok=True)
def run(name,args):
    result=subprocess.run(args,cwd=R,capture_output=True,text=True,env={**os.environ,'PLAYWRIGHT_SKIP_BROWSER_GC':'1'})
    log=result.stdout+result.stderr
    for path in [D/'connection.json',D/'roles.json',R/'.runtime/identity/state.json']:
        if path.exists():
            data=json.loads(path.read_text())
            for key,value in data.items():
                if isinstance(value,str) and (key.lower().endswith('secret') or key.lower().endswith('password') or key.startswith('di_')):log=log.replace(value,'[REDACTED]')
    (report/(name+'.log')).write_text(log);print(name+': '+str(result.returncode),flush=True)
    if result.returncode:raise RuntimeError(name+' failed; inspect checkout-local evidence')
    if 'test_management_services.py' in ' '.join(args) or 'test' in args:
        folder='test' if 'test' in args else 'integrationTest'
        files=list((R/'build/test-results'/folder).glob('TEST-*.xml'));suites=[E.parse(p).getroot() for p in files]
        if not suites or any(int(s.get('tests','0'))==0 or int(s.get('skipped','0')) or int(s.get('failures','0')) or int(s.get('errors','0')) for s in suites):raise RuntimeError('Missing/skipped/failed mandatory JUnit execution')
        for requested in args:
            if requested.startswith('*') and not any(s.get('name','').endswith(requested[1:]) for s in suites):raise RuntimeError('Requested class did not execute: '+requested)
        destination=report/name;destination.mkdir(exist_ok=True)
        for file in files:shutil.copyfile(file,destination/file.name)
try:
    run('setup',['python3','scripts/setup.py'])
    run('B-transaction-regression',['python3','scripts/test_management_services.py','*AuditedUnitOfWorkIT','*ManagementStartupIT'])
    run('B-C-upgrade',['python3','scripts/test_upgrade_integrity.py'])
    run('B-review-minor-fixes',['python3','scripts/test_mapping_integrity.py'])
    run('jwt',[str(R/'gradlew'),'--no-daemon','test','--tests','*JwtValidationTest'])
    run('M15-M18-M20',['python3','scripts/test_management_services.py','*AccessWorkflowIT','*CatalogCommandServiceIT','*ConcurrentDecisionIT','*IdempotencyIT','*DownloadAuthorizationRepositoryIT','*ManagementViewsIT','*DeliveryRegistrationIT'])
    run('bootJar',[str(R/'gradlew'),'--no-daemon','bootJar'])
    run('identity-start',['python3','scripts/identity_runtime.py','start'])
    run('M14-M18-M19-browser',['python3','scripts/test_management_services.py','*OidcLoginIT','*ManagementHttpSecurityIT'])
    print('PASS MA-C M14-M20: actual JVM/PG/Keycloak/APISIX/browser; source-bound evidence in .runtime/management-c-evidence',flush=True)
finally:
    if (R/'.runtime/identity/state.json').exists():run('identity-stop',['python3','scripts/identity_runtime.py','stop'])
    if (D/'connection.json').exists():cleanup()
