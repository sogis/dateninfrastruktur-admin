#!/usr/bin/env python3
"""MA-D cross-repository real primary-DB/JVM/browser proof with only own resources."""
import os,subprocess,json,shutil,xml.etree.ElementTree as E
from management_db import R,D,cleanup
P=R.parent/'dateninfrastruktur-sodata';out=R/'.runtime/catalog-d-evidence';out.mkdir(exist_ok=True)
if (D/'connection.json').exists() or (R/'.runtime/identity/state.json').exists():raise SystemExit('Existing own runtime retained. Explicitly stop own identity/database before a fresh MA-D run.')
def run(name,args,cwd=R,junit=None):
    result=subprocess.run(args,cwd=cwd,capture_output=True,text=True,env={**os.environ,'PLAYWRIGHT_SKIP_BROWSER_GC':'1'})
    log=result.stdout+result.stderr
    for path in [D/'connection.json',D/'roles.json',R/'.runtime/identity/state.json']:
        if path.exists():
            for key,value in json.loads(path.read_text()).items():
                if isinstance(value,str) and (key.lower().endswith('secret') or key.lower().endswith('password') or key.startswith('di_')):log=log.replace(value,'[REDACTED]')
    (out/(name+'.log')).write_text(log);print(name+': '+str(result.returncode),flush=True)
    if result.returncode:raise RuntimeError(name+' failed; see ignored evidence')
    if junit:
        files=list((cwd/'build/test-results'/junit).glob('TEST-*.xml'));suites=[E.parse(f).getroot() for f in files]
        if not suites or any(int(x.get('tests','0'))==0 or int(x.get('skipped','0')) or int(x.get('errors','0')) or int(x.get('failures','0')) for x in suites):raise RuntimeError('Mandatory JUnit missing/skipped/failed')
        for arg in args:
            if arg.startswith('*') and not any(s.get('name','').endswith(arg[1:]) for s in suites):raise RuntimeError('Requested suite did not execute: '+arg)
        target=out/name;target.mkdir(exist_ok=True)
        for file in files:shutil.copyfile(file,target/file.name)
try:
    run('setup',['python3','scripts/setup.py'])
    run('transaction-startup-regression',['python3','scripts/test_management_services.py','*AuditedUnitOfWorkIT','*ManagementStartupIT'],junit='integrationTest')
    run('B-C-D-upgrade',['python3','scripts/test_upgrade_integrity.py'])
    run('C-D-upgrade',['python3','scripts/test_upgrade_d_integrity.py'])
    run('M23-legacy',['python3','scripts/test_management_services.py','*LegacyCatalogImportIT'],junit='integrationTest')
    run('legacy-cli-dry',['python3','scripts/legacy_import.py',str(P/'spec/fixtures/published_catalog_full_62_entries.xtf'),'--report='+str(out/'legacy-cli-full.json')])
    run('legacy-cli-apply',['python3','scripts/legacy_import.py',str(P/'spec/fixtures/published_catalog_examples_only.xtf'),'--apply','--select=ch.so.wasserqualitaet_grundwasser,ch.so.bevoelkerung.altersstruktur','--actor-issuer=urn:di:operator','--actor-subject=ma-d-synthetic-import','--report='+str(out/'legacy-cli-apply.json')])
    run('operator-contract',['python3','scripts/test_operator_contract.py'])
    run('portal-fixture',['python3','scripts/test_management_services.py','*DatabaseCatalogFixtureIT'],junit='integrationTest')
    run('M21-M22-M24-M25',['python3','scripts/test_database_services.py','*JdbcCatalogRepositoryIT','*PublicCatalogRoleIT','*DatabaseCatalogModeIT','*CatalogDisclosureIT'],cwd=P,junit='managementDatabaseTest')
    run('portal-MVC-regression',[str(P/'gradlew'),'--no-daemon','test','--tests','*CatalogControllerMvcTest','--tests','*CatalogDetailControllerMvcTest','--tests','*DetailPageVmFactoryTest','--tests','*CatalogInputsConfigurationTest','--tests','*CatalogActuatorMvcTest','--tests','*XtfPublishedCatalogParserTest','--tests','*ResultsVmFactoryTest'],cwd=P,junit='test')
    run('portal-bootJar',[str(P/'gradlew'),'--no-daemon','bootJar'],cwd=P)
    run('admin-bootJar',[str(R/'gradlew'),'--no-daemon','bootJar'])
    portal_jar=next(p for p in (P/'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar'))
    run('identity-start',['python3','scripts/identity_runtime.py','start','--portal-jar='+str(portal_jar)])
    run('MA-C-review-minors',['python3','scripts/test_management_services.py','*OidcLoginIT','*ManagementHttpSecurityIT','*PortalNavigationIT'],junit='integrationTest')
    print('PASS MA-D M21-M25 and scoped C review fixes; two Public instances, actual admin commit, real DB outage, no snapshot fallback',flush=True)
finally:
    if (R/'.runtime/identity/state.json').exists():run('identity-stop',['python3','scripts/identity_runtime.py','stop'])
    if (D/'connection.json').exists():cleanup()
