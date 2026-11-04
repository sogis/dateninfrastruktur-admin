#!/usr/bin/env python3
"""Own disposable Keycloak/APISIX/JVM topology for real MA-C browser/security tests.
Never reads the user's .env or attaches user containers. Test-only direct loopback ports.
"""
import argparse,json,os,pathlib,secrets,socket,subprocess,time,urllib.request
from management_db import R,D,state,cmd
I=R/'.runtime/identity';I.mkdir(exist_ok=True,parents=True);I.chmod(0o700)
def port():
    with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def write(name,data,public=False):
    p=I/name;p.write_text(json.dumps(data,indent=2) if not isinstance(data,str) else data);p.chmod(0o644 if public else 0o600);return p

def save(s):write('state.json',s)
def wait(url):
    for _ in range(120):
        try:
            with urllib.request.urlopen(url,timeout=2) as response:
                if response.status==200:return
        except Exception:pass
        time.sleep(.5)
    raise RuntimeError('Own identity dependency did not become ready: '+url)
def own(s,cid):
    if cmd(['docker','inspect','--format','{{index .Config.Labels "di.identity"}}',cid])!=s['id']:raise RuntimeError('Identity resource ownership mismatch')
def start(portal_jar=None):
    if (I/'state.json').exists():raise RuntimeError('Own identity runtime already exists; retain or explicitly stop it')
    st=state();rid='di-ma-c-'+secrets.token_hex(6);kcport,gatewayport=port(),port()
    s={'id':rid,'network':rid,'containers':[],'database':st['container'],'keycloak':'http://127.0.0.1:'+str(kcport),'gateway':'http://127.0.0.1:'+str(gatewayport),'password':secrets.token_urlsafe(24),'clientSecret':secrets.token_urlsafe(40),'sessionSecret':secrets.token_urlsafe(48),'gatewaySecret':secrets.token_urlsafe(40)}
    save(s)
    cmd(['docker','network','create','--label','di.identity='+rid,rid]);cmd(['docker','network','connect','--alias','database',rid,st['container']])
    realm=json.loads((R.parent/'dateninfrastruktur-dev-stack/services/keycloak/realm-template.json').read_text())
    for user in realm['users']:user['credentials'][0]['value']=s['password']
    audience={'name':'management API audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper','consentRequired':False,'config':{'included.custom.audience':'management-api','id.token.claim':'false','access.token.claim':'true'}}
    realm['clients']=[{'clientId':'management-gateway','enabled':True,'protocol':'openid-connect','publicClient':False,'secret':s['clientSecret'],'standardFlowEnabled':True,'directAccessGrantsEnabled':False,'redirectUris':[s['gateway']+'/oidc/callback'],'webOrigins':[s['gateway']],'protocolMappers':[audience]}, {'clientId':'management-test','enabled':True,'protocol':'openid-connect','publicClient':True,'standardFlowEnabled':False,'directAccessGrantsEnabled':True,'protocolMappers':[audience]}]
    realm['accessTokenLifespan']=300
    realmfile=write('realm.json',realm,True)
    def run(name,args,env=None):
        cid=cmd(['docker','run','--detach','--name',rid+'-'+name,'--label','di.identity='+rid,'--network',rid,'--network-alias',name,*args],env=env);s['containers'].append(cid);save(s);return cid
    run('keycloak',['--publish','127.0.0.1:'+str(kcport)+':8080','--env','KC_HOSTNAME='+s['keycloak'],'--env','KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true','--env','KC_HEALTH_ENABLED=true','--mount','type=bind,src='+str(realmfile)+',dst=/opt/keycloak/data/import/realm.json,readonly','quay.io/keycloak/keycloak:26.8.1','start-dev','--import-realm'])
    wait(s['keycloak']+'/realms/dateninfrastruktur/.well-known/openid-configuration')
    roles=json.loads((D/'roles.json').read_text());url='jdbc:postgresql://database:5432/dateninfrastruktur_management'
    env=os.environ.copy();runtime_values=dict(DI_MANAGEMENT_WRITER_JDBCURL=url,DI_MANAGEMENT_WRITER_USERNAME='di_writer',DI_MANAGEMENT_WRITER_PASSWORD=roles['di_writer'],DI_MANAGEMENT_AUTHORIZATION_JDBCURL=url,DI_MANAGEMENT_AUTHORIZATION_USERNAME='di_authz',DI_MANAGEMENT_AUTHORIZATION_PASSWORD=roles['di_authz'],DI_MANAGEMENT_AUDIT_JDBCURL=url,DI_MANAGEMENT_AUDIT_USERNAME='di_audit_reader',DI_MANAGEMENT_AUDIT_PASSWORD=roles['di_audit_reader'],DI_WEB_ISSUER=s['keycloak']+'/realms/dateninfrastruktur',DI_WEB_JWKSURI='http://keycloak:8080/realms/dateninfrastruktur/protocol/openid-connect/certs',DI_WEB_AUDIENCE='management-api',DI_WEB_PUBLICORIGIN=s['gateway'],DI_WEB_GATEWAYSECRET=s['gatewaySecret']);env.update(runtime_values)
    args=['--publish','127.0.0.1::8080']
    for key in runtime_values:args+=['--env',key]
    jars=list((R/'build/libs').glob('*.jar'));jar=next(p for p in jars if not p.name.endswith('-plain.jar'))
    admin=run('admin',[*args,'--mount','type=bind,src='+str(jar)+',dst=/app.jar,readonly','eclipse-temurin:25-jre','java','-jar','/app.jar','--server.port=8080'],env)
    s['admin']='http://127.0.0.1:'+cmd(['docker','port',admin,'8080/tcp']).rsplit(':',1)[1];save(s)
    wait(s['admin']+'/actuator/health/readiness')
    if portal_jar:
        portal_jar=pathlib.Path(portal_jar).resolve()
        if not portal_jar.is_file():raise RuntimeError('Explicit portal jar does not exist')
        portal_env=os.environ.copy();portal_values=dict(DATENINFRASTRUKTUR_CATALOG_SOURCE_TYPE='database',DATENINFRASTRUKTUR_CATALOG_DATABASE_JDBCURL=url,DATENINFRASTRUKTUR_CATALOG_DATABASE_USERNAME='di_public',DATENINFRASTRUKTUR_CATALOG_DATABASE_PASSWORD=roles['di_public'],DATENINFRASTRUKTUR_EXPLORE_ENABLED='false',DATENINFRASTRUKTUR_WEB_COMPONENTS_ENABLED='false');portal_env.update(portal_values)
        portal_args=[]
        for key in portal_values:portal_args+=['--env',key]
        run('portal',[*portal_args,'--mount','type=bind,src='+str(portal_jar)+',dst=/app.jar,readonly','eclipse-temurin:25-jre','java','-jar','/app.jar','--server.port=8080'],portal_env)
    config={'apisix':{'node_listen':9080,'enable_ipv6':False,'ssl':{'enable':False}},'deployment':{'role':'data_plane','role_data_plane':{'config_provider':'yaml'}},'nginx_config':{'worker_processes':1,'error_log':'/dev/stderr','http':{'access_log':'/dev/stdout','access_log_format':'$remote_addr $uri $status'}}}
    configfile=write('config.yaml',config,True)
    oidc={'client_id':'management-gateway','client_secret':s['clientSecret'],'discovery':'http://keycloak:8080/realms/dateninfrastruktur/.well-known/openid-configuration','scope':'openid profile email','redirect_uri':s['gateway']+'/oidc/callback','ssl_verify':False,'use_pkce':True,'set_access_token_header':True,'access_token_in_authorization_header':True,'set_id_token_header':False,'set_userinfo_header':False,'session':{'secret':s['sessionSecret'],'cookie_name':'di_management_session','cookie_path':'/','cookie_secure':False,'cookie_http_only':True,'cookie_same_site':'Lax'}}
    upstream={'type':'roundrobin','nodes':{'admin:8080':1}}
    routes={'routes':[{'id':'management','uris':['/management','/management/*','/access/*','/oidc/callback'],'plugins':{'openid-connect':oidc},'upstream':upstream},{'id':'assets','uri':'/assets/*','upstream':upstream},{'id':'internal-deny','uris':['/internal/*','/actuator/*','/admin/*'],'plugins':{'mocking':{'response_status':403,'response_example':'Forbidden'}}}]}
    if portal_jar:
        for route in routes['routes']:route['priority']=100
        routes['routes'].append({'id':'public-catalog','uri':'/*','priority':0,'upstream':{'type':'roundrobin','nodes':{'portal:8080':1}}})
    routefile=write('apisix.yaml',json.dumps(routes,indent=2)+'\n#END\n',True)
    run('gateway',['--publish','127.0.0.1:'+str(gatewayport)+':9080','--mount','type=bind,src='+str(configfile)+',dst=/usr/local/apisix/conf/config.yaml,readonly','--mount','type=bind,src='+str(routefile)+',dst=/usr/local/apisix/conf/apisix.yaml,readonly','apache/apisix:3.19.0-debian'])
    wait(s['gateway']+'/assets/management.css')
    if portal_jar:wait(s['gateway']+'/')
    print('Own MA-C identity runtime ready. Addresses in private .runtime/identity/state.json; no user services changed.')
def stop():
    s=json.loads((I/'state.json').read_text())
    for cid in reversed(s['containers']):own(s,cid);cmd(['docker','rm','--force','--volumes',cid])
    st=state()
    if st['container']!=s['database']:raise RuntimeError('Database identity changed; refusing network mutation')
    cmd(['docker','network','disconnect',s['network'],s['database']])
    if cmd(['docker','network','inspect','--format','{{index .Labels "di.identity"}}',s['network']])!=s['id']:raise RuntimeError('Network ownership mismatch')
    cmd(['docker','network','rm',s['network']]);(I/'state.json').unlink();print('Removed only owned MA-C identity resources; database retained')
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['start','stop']);parser.add_argument('--portal-jar');args=parser.parse_args();start(args.portal_jar) if args.action=='start' else stop()
