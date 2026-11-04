#!/usr/bin/env python3
"""MA-E final integration. Own fresh resources; no duplicate earlier-phase suite."""
import datetime,json,os,pathlib,shutil,subprocess,sys,xml.etree.ElementTree as ET
R=pathlib.Path(__file__).resolve().parents[1];P=R.parent/'dateninfrastruktur-sodata'
root=R/'.runtime/managed-test';database=root/'database';identity=root/'identity/state.json'
if identity.exists() or (database/'connection.json').exists():raise SystemExit('Existing managed test runtime retained. Explicit owned cleanup required before a fresh run.')
root.mkdir(parents=True,exist_ok=True);root.chmod(0o700)
env=os.environ.copy();env.update(DI_MANAGEMENT_RUNTIME_DIR=str(database),DI_MANAGED_DIRECTORY=str(root),PLAYWRIGHT_SKIP_BROWSER_GC='1')
evidence=R/'.runtime/download-evidence'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ');evidence.mkdir(parents=True)
def redact(log):
    for path in [database/'roles.json',database/'connection.json',identity,root/'test-sensitive.json']:
        if path.exists():
            for key,value in json.loads(path.read_text()).items():
                if isinstance(value,str) and (key.startswith('di_') or any(t in key.lower() for t in ['password','secret']) or key.endswith('Key') or path.name=='test-sensitive.json'):log=log.replace(value,'[REDACTED]')
    return log
def run(name,args,cwd=R,junit=None):
    p=subprocess.run(args,cwd=cwd,env=env,capture_output=True,text=True)
    (evidence/(name+'.log')).write_text(redact(p.stdout+p.stderr));print(name+': '+str(p.returncode),flush=True)
    if p.returncode:raise RuntimeError(name+' failed; ignored evidence: '+str(evidence))
    if junit:
        files=list((cwd/'build/test-results'/junit).glob('TEST-*.xml'));suites=[ET.parse(p).getroot() for p in files]
        if not suites or any(int(s.get('tests','0'))==0 or any(int(s.get(k,'0')) for k in ['skipped','errors','failures']) for s in suites):raise RuntimeError('Missing/skipped/failed JUnit suite')
        for arg in args:
            if arg.startswith('*') and not any(s.get('name','').endswith(arg[1:]) for s in suites):raise RuntimeError('Requested JUnit class did not execute: '+arg)
        out=evidence/name;out.mkdir()
        for p in files:(out/p.name).write_text(redact(p.read_text()))
successful=False
try:
    run('lifecycle-ownership-negative',['python3','scripts/test_managed_ownership.py'])
    run('explicit-setup',['python3','scripts/setup.py'])
    run('reserve-owned-topology',['python3','scripts/managed_runtime.py','prepare'])
    s=json.loads(identity.read_text());env.update(DI_TEST_PILOT_ISSUER=s['keycloak']+'/realms/dateninfrastruktur',DI_TEST_PILOT_FIXTURE=str(root/'fixture.json'))
    run('explicit-pilot-seed',['python3','scripts/test_management_services.py','*ManagedDownloadFixtureIT'],junit='integrationTest')
    run('admin-jar',[str(R/'gradlew'),'--no-daemon','bootJar'])
    run('public-jar',[str(P/'gradlew'),'--no-daemon','bootJar'],cwd=P)
    run('private-two-instance-start',['python3','scripts/managed_runtime.py','start'])
    run('missing-private-object-rollback',['python3','scripts/managed_pilot.py','reject-missing'])
    run('private-upload',['python3','scripts/managed_pilot.py','upload'])
    run('M29-old-public-cutover',['python3','scripts/managed_pilot.py','canary'])
    run('real-S3-operator-activation',['python3','scripts/managed_pilot.py','activate'])
    run('M26-M33-final-journey',[str(R/'gradlew'),'--no-daemon','integrationTest','--tests','*ManagedDownloadsIT'],junit='integrationTest')
    run('protected-links-VM-regression',[str(P/'gradlew'),'--no-daemon','test','--tests','*DetailPageVmFactoryTest','--tests','*ResultsVmFactoryTest'],cwd=P,junit='test')
    successful=True
    print('PASS MA-E M26-M33 final integration. Evidence: '+str(evidence),flush=True)
finally:
    if not ((successful and '--keep-runtime' in sys.argv) or '--keep-failed-runtime' in sys.argv):
        if identity.exists():run('owned-cleanup',['python3','scripts/managed_runtime.py','cleanup'])
        elif (database/'connection.json').exists():run('owned-db-cleanup',['python3','scripts/management_db.py','cleanup'])
