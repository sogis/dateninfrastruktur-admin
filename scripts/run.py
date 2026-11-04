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
identity_file=R/'.runtime/identity/state.json'
if not identity_file.exists():raise SystemExit('Start own scripts/identity_runtime.py first, or configure an explicit secured JVM deployment. Web startup never defaults to anonymous access.')
identity=json.loads(identity_file.read_text());issuer=identity['keycloak']+'/realms/dateninfrastruktur'
env.update(DI_MANAGEMENT_AUDIT_JDBCURL=url,DI_MANAGEMENT_AUDIT_USERNAME='di_audit_reader',DI_MANAGEMENT_AUDIT_PASSWORD=roles['di_audit_reader'],DI_WEB_ISSUER=issuer,DI_WEB_JWKSURI=issuer+'/protocol/openid-connect/certs',DI_WEB_AUDIENCE='management-api',DI_WEB_PUBLICORIGIN=identity['gateway'],DI_WEB_GATEWAYSECRET=identity['gatewaySecret'])
port=env.get('DI_ADMIN_PORT','18090')
print('Admin JVM start: http://127.0.0.1:'+port+' (secured host-JVM diagnostic instance)',flush=True)
raise SystemExit(subprocess.run([str(R/'gradlew'),'bootRun',
    '--args=--server.address=127.0.0.1 --server.port='+port],cwd=R,env=env).returncode)
