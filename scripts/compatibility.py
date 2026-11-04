#!/usr/bin/env python3
"""MA-0 probe on a new, exclusively owned PostgreSQL16 container. No user env."""
import hashlib, json, os, pathlib, secrets, subprocess, sys, time, urllib.request, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
def run(args, **kw): return subprocess.run(args,check=True,**kw)
def tools():
    manifest=json.loads((ROOT/'db/toolchain.json').read_text())
    for name,item in manifest.items():
        target=ROOT/'.runtime/tools'/name
        if not target.exists():
            target.mkdir(parents=True)
            data=urllib.request.urlopen(item['url']).read()
            if hashlib.sha256(data).hexdigest()!=item['sha256']: raise RuntimeError('tool hash mismatch')
            archive=target.parent/(name+'.zip'); archive.write_bytes(data)
            with zipfile.ZipFile(archive) as z: z.extractall(target)
    return manifest
manifest=tools()
name='di-ma0-'+secrets.token_hex(6)
password=secrets.token_urlsafe(32)
env=os.environ.copy(); env['POSTGRES_PASSWORD']=password
cid=None
try:
    cid=subprocess.check_output(['docker','run','--detach','--name',name,'--label','di.phase=MA-0','--env','POSTGRES_PASSWORD','--env','POSTGRES_DB=compatibility','--publish','127.0.0.1::5432','ghcr.io/baosystems/postgis:16-3.5@sha256:0f1c5c0f70f03d4d19ad1d7308d86e6162dff5429c491002298a7b5e46d2f2e8'],env=env,text=True).strip()
    for _ in range(60):
        if subprocess.run(['docker','exec',cid,'pg_isready','-U','postgres','-d','compatibility'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0: break
        time.sleep(.5)
    else: raise RuntimeError('isolated database not ready')
    port=subprocess.check_output(['docker','port',cid,'5432/tcp'],text=True).strip().rsplit(':',1)[1]
    print('Owned PostgreSQL16 container ready; native image; loopback random port',flush=True)
    env.update(SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:'+port+'/compatibility',SPRING_DATASOURCE_USERNAME='postgres',SPRING_DATASOURCE_PASSWORD=password)
    run([str(ROOT/'gradlew'),'--no-daemon','integrationTest','--tests','*CayenneRuntimeCompatibilityIT'],cwd=ROOT,env=env)
    model=ROOT/'.runtime/compatibility.ili'
    model.write_text('INTERLIS 2.4;\nMODEL Compatibility (en) AT "https://example.invalid" VERSION "2026-11-04" =\n  TOPIC Probe =\n    OID AS INTERLIS.UUIDOID;\n    CLASS Item =\n      name : MANDATORY TEXT*80;\n    END Item;\n  END Probe;\nEND Compatibility.\n')
    jar=next((ROOT/'.runtime/tools/ili2pg').glob('ili2pg-*.jar'))
    try:
        run(['java','-jar',str(jar),'--schemaimport','--dbhost','127.0.0.1','--dbport',port,'--dbdatabase','compatibility','--dbusr','postgres','--dbpwd',password,'--dbschema','ili_probe','--models','Compatibility','--modeldir',str(model.parent),'--createMetaInfo','--createStdCols','--createTidCol','--setupPgExt',str(model)],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    except subprocess.CalledProcessError as e:
        message=(e.stdout+e.stderr).decode(errors='replace').replace(password,'[REDACTED]')
        print(message); raise RuntimeError('ili2pg geometriefreie Probe fehlgeschlagen') from None
    run(['docker','exec',cid,'psql','-U','postgres','-d','compatibility','-Atc',"SELECT extname FROM pg_extension ORDER BY extname; SELECT tablename FROM pg_tables WHERE schemaname='ili_probe' ORDER BY tablename;"])
    print('PASS Boot/Cayenne/PostgreSQL actor/transaction + ili2pg UUID schema with PostGIS')
finally:
    if cid:
        run(['docker','rm','--force','--volumes',cid],stdout=subprocess.DEVNULL)
