"""Run scoped service tests against the explicitly owned development database."""
import json,os,subprocess,sys
from management_db import R,D,state
st=state();roles=json.loads((D/'roles.json').read_text());env=os.environ.copy();env["PLAYWRIGHT_SKIP_BROWSER_GC"]="1"
env.update(DI_TEST_URL='jdbc:postgresql://127.0.0.1:'+st['port']+'/dateninfrastruktur_management',DI_TEST_ADMIN_PASSWORD=st['password'],DI_TEST_WRITER_PASSWORD=roles['di_writer'],DI_TEST_AUTHZ_PASSWORD=roles['di_authz'],DI_TEST_AUDIT_PASSWORD=roles['di_audit_reader'])
identity=R/'.runtime/identity/state.json'
if identity.exists():
    identity_state=json.loads(identity.read_text())
    env.update(DI_TEST_GATEWAY=identity_state['gateway'],DI_TEST_BACKEND=identity_state['admin'],DI_TEST_OIDC_ISSUER=identity_state['keycloak']+'/realms/dateninfrastruktur',DI_TEST_DEMO_PASSWORD=identity_state['password'],DI_TEST_GATEWAY_SECRET=identity_state['gatewaySecret'])
    roles['identity_password']=identity_state['password'];roles['gateway_secret']=identity_state['gatewaySecret']
args=[str(R/'gradlew'),'--no-daemon','integrationTest']
for pattern in sys.argv[1:] or ['*AccessWorkflowIT']:args+=['--tests',pattern]
p=subprocess.run(args,cwd=R,env=env,capture_output=True,text=True)
log=p.stdout+p.stderr
for secret in [st['password'],*roles.values()]:log=log.replace(secret,'[REDACTED]')
(R/'.runtime/management-services.log').write_text(log);print(log[-7000:]);sys.exit(p.returncode)
