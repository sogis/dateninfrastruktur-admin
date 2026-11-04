#!/usr/bin/env python3
"""JVM local start with dedicated own runtime roles; never prints passwords."""
import json,os,subprocess
from management_db import R,D,state
s=state()
roles=json.loads((D/'roles.json').read_text())
env=os.environ.copy()
url='jdbc:postgresql://127.0.0.1:'+s['port']+'/dateninfrastruktur_management'
env.update(DI_MANAGEMENT_WRITER_JDBCURL=url,DI_MANAGEMENT_WRITER_USERNAME='di_writer',
    DI_MANAGEMENT_WRITER_PASSWORD=roles['di_writer'],
    DI_MANAGEMENT_AUTHORIZATION_JDBCURL=url,DI_MANAGEMENT_AUTHORIZATION_USERNAME='di_authz',
    DI_MANAGEMENT_AUTHORIZATION_PASSWORD=roles['di_authz'])
port=env.get('DI_ADMIN_PORT','18090')
print('Admin JVM start: http://127.0.0.1:'+port+' (MA-B: schema/runtime only, UI follows MA-C)',flush=True)
raise SystemExit(subprocess.run([str(R/'gradlew'),'bootRun',
    '--args=--server.address=127.0.0.1 --server.port='+port],cwd=R,env=env).returncode)
