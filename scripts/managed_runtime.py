#!/usr/bin/env python3
"""Managed mode of the identity harness: persistent, private two-instance topology.
Only explicit setup creates/migrates a DB. start/restart never seed or reset data.
"""
import argparse,json,os,pathlib,secrets,subprocess,time,urllib.request
from management_db import R,D,state,cmd,IMAGE
from identity_runtime import I,port,write,save,own,wait
STACK=R.parent/'dateninfrastruktur-dev-stack'
BUCKET='di-managed-private'
def network_identity(s):
    if cmd(['docker','network','inspect','--format','{{index .Labels \"di.identity\"}}',s['network']])!=s['id']:raise RuntimeError('Managed network ownership mismatch')
def load():return json.loads((I/'state.json').read_text())
def resource(s,name,args,env=None):
    network_identity(s)
    cid=cmd(['docker','run','--detach','--name',s['id']+'-'+name,'--label','di.identity='+s['id'],'--network',s['network'],'--network-alias',name,*args],env=env)
    s['containers'].append(cid);s.setdefault('services',{})[name]=cid;save(s);return cid
def prepare():
    state() # refuse a runtime without the checked, explicitly created database
    if (I/'state.json').exists():
        s=load();network_identity(s);return s
    rid='di-ma-e-'+secrets.token_hex(6)
    gateways=['http://127.0.0.1:'+str(port()) for _ in range(2)]
    s=dict(id=rid,network=rid,containers=[],services={},gateways=gateways,gateway=gateways[0],keycloak=gateways[0]+'/auth',password=secrets.token_urlsafe(24),clientSecret=secrets.token_urlsafe(40),sessionSecret=secrets.token_urlsafe(48),gatewaySecret=secrets.token_urlsafe(40),storageKey='GK'+secrets.token_hex(16),storageSecret=secrets.token_hex(32),originKey='GK'+secrets.token_hex(16),originSecret=secrets.token_hex(32))
    save(s);cmd(['docker','network','create','--label','di.identity='+rid,rid]);return s
def private_database(s):
    st=state()
    if st.get('private'):
        if s.get('database')!=st['container']:raise RuntimeError('Private DB identity changed')
        return
    info=json.loads(cmd(['docker','inspect',st['container']]))[0]
    volumes=[m for m in info['Mounts'] if m['Type']=='volume' and m['Destination']=='/var/lib/postgresql/data']
    if len(volumes)!=1:raise RuntimeError('Expected exactly one owned PostgreSQL data volume')
    volume=volumes[0]['Name'];cmd(['docker','stop',st['container']])
    # Keep the old container and volume until the new private container is healthy.
    env=os.environ.copy();env['POSTGRES_PASSWORD']=st['password']
    name=st['name']+'-private'
    cid=cmd(['docker','run','--detach','--name',name,'--label','di.phase=MA-B','--network',s['network'],'--network-alias','database','--env','POSTGRES_PASSWORD','--mount','type=volume,src='+volume+',dst=/var/lib/postgresql/data',IMAGE],env=env)
    for _ in range(120):
        if subprocess.run(['docker','exec',cid,'pg_isready','-U','postgres'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
        time.sleep(.5)
    else:raise RuntimeError('Private PostgreSQL failed; original owned container and volume retained')
    old=st['container'];st.update(container=cid,name=name,private=True,port=None);(D/'connection.json').write_text(json.dumps(st));(D/'connection.json').chmod(0o600)
    s['database']=cid;s['databaseVolume']=volume;save(s);cmd(['docker','rm',old]) # no -v: same checked volume is retained
def rclone_config(s,origin=False):
    return '\n'.join(['[managed]','type = s3','provider = Other','env_auth = false','access_key_id = '+s['originKey' if origin else 'storageKey'],'secret_access_key = '+s['originSecret' if origin else 'storageSecret'],'endpoint = http://garage:3900','region = garage','force_path_style = true','no_check_bucket = true',''])
def rclone(s,args,input=None):
    network_identity(s)
    config=write('operator-rclone.conf',rclone_config(s))
    return cmd(['docker','run','--rm','--label','di.identity='+s['id'],'--network',s['network'],'--mount','type=bind,src='+str(config)+',dst=/config/rclone/rclone.conf,readonly','-i','rclone/rclone:1.75.2',*args],input=input)
def activate(dataset,expected,distribution):
    s=load();st=state()
    if st['container']!=s['database']:raise RuntimeError('Owned database identity mismatch')
    network_identity(s)
    binary=I/'rclone'
    if not binary.exists():
        cid=cmd(['docker','create','--label','di.identity='+s['id'],'rclone/rclone:1.75.2'])
        try:cmd(['docker','cp',cid+':/usr/local/bin/rclone',str(binary)]);binary.chmod(0o755)
        finally:own(s,cid);cmd(['docker','rm',cid])
    config=write('operator-rclone.conf',rclone_config(s))
    jar=next(p for p in (R/'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar'))
    roles=json.loads((D/'roles.json').read_text());env=os.environ.copy()
    values=dict(DI_MANAGEMENT_WRITER_JDBCURL='jdbc:postgresql://database:5432/dateninfrastruktur_management',DI_MANAGEMENT_WRITER_PASSWORD=roles['di_writer'],DI_OPERATOR_ISSUER='urn:di:operator',DI_OPERATOR_SUBJECT='managed-delivery',DI_RCLONE_EXECUTABLE='/operator/rclone',DI_RCLONE_CONFIG='/operator/rclone.conf',DI_PRIVATE_STORAGE_PROFILE='managed',DI_PRIVATE_STORAGE_REMOTE='managed:'+BUCKET);env.update(values);args=[]
    for key in values:args+=['--env',key]
    try:
        result=cmd(['docker','run','--rm','--label','di.identity='+s['id'],'--network',s['network'],*args,'--mount','type=bind,src='+str(jar)+',dst=/app.jar,readonly','--mount','type=bind,src='+str(binary)+',dst=/operator/rclone,readonly','--mount','type=bind,src='+str(config)+',dst=/operator/rclone.conf,readonly','eclipse-temurin:25-jre','java','-Dloader.main=ch.so.agi.dateninfrastruktur.admin.operator.ManagedDeliveryCommand','-cp','/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher','activate',dataset,str(expected),distribution],env=env)
    except Exception:
        raise RuntimeError('Explicit private delivery activation failed; no credentials emitted') from None
    if 'Private object verified and distribution activated.' not in result:raise RuntimeError('Operator activation did not complete')
def start(portal_jar=None):
    s=prepare()
    if s.get('started'):
        if state()['container']!=s['database']:raise RuntimeError('Owned database identity mismatch')
        for cid in [s['database'],*s['containers']]:
            if cid!=s['database']:own(s,cid)
            cmd(['docker','start',cid])
        wait(s['gateway']+'/');wait(s['keycloak']+'/realms/dateninfrastruktur/.well-known/openid-configuration');wait(s['keycloak']+'/realms/dateninfrastruktur/protocol/openid-connect/certs');return
    private_database(s)
    jar=next(p for p in (R/'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar'))
    pj=pathlib.Path(portal_jar) if portal_jar else next(p for p in (R.parent/'dateninfrastruktur-sodata/build/libs').glob('*.jar') if not p.name.endswith('-plain.jar'))
    realm=json.loads((STACK/'services/keycloak/realm-template.json').read_text())
    for user in realm['users']:user['credentials'][0]['value']=s['password']
    audience={'name':'management API audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper','consentRequired':False,'config':{'included.custom.audience':'management-api','id.token.claim':'false','access.token.claim':'true'}}
    realm['clients']=[{'clientId':'management-gateway','enabled':True,'protocol':'openid-connect','publicClient':False,'secret':s['clientSecret'],'standardFlowEnabled':True,'directAccessGrantsEnabled':False,'redirectUris':[g+'/oidc/callback' for g in s['gateways']],'webOrigins':s['gateways'],'protocolMappers':[audience]}, {'clientId':'management-test','enabled':True,'protocol':'openid-connect','publicClient':True,'standardFlowEnabled':False,'directAccessGrantsEnabled':True,'protocolMappers':[audience]}]
    realm['accessTokenLifespan']=900
    realmfile=write('realm.json',realm,True)
    resource(s,'keycloak',['--env','KC_HOSTNAME='+s['keycloak'],'--env','KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true','--env','KC_HTTP_RELATIVE_PATH=/auth','--mount','type=bind,src='+str(realmfile)+',dst=/opt/keycloak/data/import/realm.json,readonly','quay.io/keycloak/keycloak:26.8.1','start-dev','--import-realm'])
    garage=(STACK/'services/garage/garage.toml.template').read_text().replace('__RPC_SECRET__',secrets.token_hex(32)).replace('__ADMIN_TOKEN__',secrets.token_hex(32)).replace('__METRICS_TOKEN__',secrets.token_hex(32))
    gf=write('garage.toml',garage)
    env=os.environ.copy();env.update(GARAGE_DEFAULT_ACCESS_KEY=s['storageKey'],GARAGE_DEFAULT_SECRET_KEY=s['storageSecret'],GARAGE_DEFAULT_BUCKET=BUCKET,RUST_LOG='warn')
    resource(s,'garage',['--env','GARAGE_DEFAULT_ACCESS_KEY','--env','GARAGE_DEFAULT_SECRET_KEY','--env','GARAGE_DEFAULT_BUCKET','--env','RUST_LOG','--mount','type=bind,src='+str(gf)+',dst=/etc/garage.toml,readonly','--volume','/var/lib/garage/meta','--volume','/var/lib/garage/data','dxflrs/garage:v2.4.1','/garage','server','--single-node','--default-bucket'],env)
    for _ in range(100):
        result=subprocess.run(['docker','exec',s['services']['garage'],'/garage','bucket','info',BUCKET],capture_output=True,text=True)
        if result.returncode==0:break
        time.sleep(.5)
    else:raise RuntimeError('Own private Garage bucket not ready')
    garagecid=s['services']['garage']
    cmd(['docker','exec',garagecid,'/garage','key','import',s['originKey'],s['originSecret'],'-n','managed-origin','--yes'])
    cmd(['docker','exec',garagecid,'/garage','key','deny',s['originKey'],'--create-bucket'])
    cmd(['docker','exec',garagecid,'/garage','bucket','allow','--read',BUCKET,'--key',s['originKey']])
    cmd(['docker','exec',garagecid,'/garage','bucket','deny','--write','--owner',BUCKET,'--key',s['originKey']])
    originconf=write('origin-rclone.conf',rclone_config(s,True))
    resource(s,'origin',['--mount','type=bind,src='+str(originconf)+',dst=/config/rclone/rclone.conf,readonly','rclone/rclone:1.75.2','serve','webdav','managed:'+BUCKET+'/downloads','--addr',':8080','--baseurl','/downloads','--read-only','--disable-dir-list','--disable-zip','--etag-hash','auto','--dir-cache-time','0s','--poll-interval','0','--vfs-cache-mode','off','--buffer-size','0','--contimeout','2s','--timeout','3s','--retries','1','--low-level-retries','1','--response-header','Content-Disposition: attachment','--response-header','Cache-Control: no-store'])
    roles=json.loads((D/'roles.json').read_text());url='jdbc:postgresql://database:5432/dateninfrastruktur_management'
    values=dict(DI_MANAGEMENT_WRITER_JDBCURL=url,DI_MANAGEMENT_WRITER_USERNAME='di_writer',DI_MANAGEMENT_WRITER_PASSWORD=roles['di_writer'],DI_MANAGEMENT_AUTHORIZATION_JDBCURL=url,DI_MANAGEMENT_AUTHORIZATION_USERNAME='di_authz',DI_MANAGEMENT_AUTHORIZATION_PASSWORD=roles['di_authz'],DI_MANAGEMENT_AUDIT_JDBCURL=url,DI_MANAGEMENT_AUDIT_USERNAME='di_audit_reader',DI_MANAGEMENT_AUDIT_PASSWORD=roles['di_audit_reader'],DI_WEB_ISSUER=s['keycloak']+'/realms/dateninfrastruktur',DI_WEB_JWKSURI='http://keycloak:8080/auth/realms/dateninfrastruktur/protocol/openid-connect/certs',DI_WEB_AUDIENCE='management-api',DI_WEB_PUBLICORIGIN=','.join(s['gateways']),DI_WEB_GATEWAYSECRET=s['gatewaySecret'])
    for n in ('a','b'):
        env=os.environ.copy();env.update(values);args=[]
        for key in values:args+=['--env',key]
        resource(s,'admin-'+n,[*args,'--mount','type=bind,src='+str(jar)+',dst=/app.jar,readonly','eclipse-temurin:25-jre','java','-jar','/app.jar','--server.port=8080'],env)
        pvalues=dict(DATENINFRASTRUKTUR_CATALOG_SOURCE_TYPE='database',DATENINFRASTRUKTUR_CATALOG_DATABASE_JDBCURL=url,DATENINFRASTRUKTUR_CATALOG_DATABASE_USERNAME='di_public',DATENINFRASTRUKTUR_CATALOG_DATABASE_PASSWORD=roles['di_public'],DATENINFRASTRUKTUR_EXPLORE_ENABLED='false',DATENINFRASTRUKTUR_WEB_COMPONENTS_ENABLED='false');env=os.environ.copy();env.update(pvalues);args=[]
        for key in pvalues:args+=['--env',key]
        resource(s,'portal-'+n,[*args,'--mount','type=bind,src='+str(pj.resolve())+',dst=/app.jar,readonly','eclipse-temurin:25-jre','java','-jar','/app.jar','--server.port=8080'],env)
        config={'apisix':{'node_listen':9080,'enable_ipv6':False,'ssl':{'enable':False}},'deployment':{'role':'data_plane','role_data_plane':{'config_provider':'yaml'}},'nginx_config':{'http_configuration_snippet':'add_header Cache-Control no-store always;','worker_processes':1,'error_log':'/dev/stderr','http':{'access_log':'/dev/stdout','access_log_format':'$remote_addr $uri $status'}}}
        cf=write('config-'+n+'.yaml',config,True)
        routefile=write('apisix-'+n+'.yaml',json.dumps(routes(s,n),indent=2)+'\n#END\n',True)
        resource(s,'gateway-'+n,['--publish','127.0.0.1:'+s['gateways'][0 if n=='a' else 1].rsplit(':',1)[1]+':9080','--mount','type=bind,src='+str(cf)+',dst=/usr/local/apisix/conf/config.yaml,readonly','--mount','type=bind,src='+str(routefile)+',dst=/usr/local/apisix/conf/apisix.yaml,readonly','apache/apisix:3.19.0-debian'])
    for g in s['gateways']:wait(g+'/');wait(g+'/assets/management.css')
    wait(s['keycloak']+'/realms/dateninfrastruktur/.well-known/openid-configuration')
    s['started']=True;save(s);print('Managed two-instance runtime ready. Only gateway loopback ports published. Private state: '+str(I/'state.json'))
def routes(s,n):
    upstream={'type':'roundrobin','nodes':{'admin-'+n+':8080':1}}
    oidc={'client_id':'management-gateway','client_secret':s['clientSecret'],'discovery':'http://keycloak:8080/auth/realms/dateninfrastruktur/.well-known/openid-configuration','scope':'openid profile email','redirect_uri':s['gateways'][0 if n=='a' else 1]+'/oidc/callback','ssl_verify':False,'use_pkce':True,'set_access_token_header':True,'access_token_in_authorization_header':True,'set_id_token_header':False,'set_userinfo_header':False,'session':{'secret':s['sessionSecret'],'cookie_name':'di_management_session','cookie_path':'/','cookie_secure':False,'cookie_http_only':True,'cookie_same_site':'Lax'}}
    optional={**oidc,'unauth_action':'pass'}
    auth={'uri':'http://admin-'+n+':8080/internal/authorization/check','request_method':'GET','request_headers':['Authorization','X-Request-ID'],'extra_headers':{'X-DI-Gateway-Credential':s['gatewaySecret'],'X-Forwarded-Uri':'$real_request_uri'},'upstream_headers':['Authorization','Cookie','X-Access-Token','X-ID-Token','X-Userinfo','X-DI-Gateway-Credential'],'allow_degradation':False,'status_on_error':503,'timeout':2000,'keepalive':True}
    deny={'mocking':{'response_status':403,'response_example':'Forbidden'}}
    canonical=r'^/downloads(?=[/?]|$)(?!/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[A-Za-z0-9][A-Za-z0-9._-]{0,127}$)'
    ambiguous=r'(?i)^[^?]*(?:%2[ef]|%5c|%00|\\|//|/\.\.?(?:/|$))'
    return {'global_rules':[{'id':'canonical-managed-paths','plugins':{'fault-injection':{'abort':{'http_status':400,'body':'Invalid canonical request path','headers':{'Cache-Control':'no-store'},'percentage':100,'vars':[[['real_request_uri','~~',canonical]],[['real_request_uri','~~',ambiguous]]]}},'response-rewrite':{'headers':{'set':{'Cache-Control':'no-store'}}}}}], 'routes':[
        {'id':'identity','priority':200,'uris':['/auth','/auth/*'],'upstream':{'type':'roundrobin','nodes':{'keycloak:8080':1}}},
        {'id':'internal-deny','priority':200,'uris':['/internal','/internal/*','/actuator/*','/admin','/admin/*','/s3/*','/legacy/*'],'plugins':deny},
        {'id':'download','priority':100,'uris':['/downloads','/downloads/*'],'plugins':{'openid-connect':optional,'forward-auth':auth,'response-rewrite':{'headers':{'set':{'Cache-Control':'no-store'}}}},'upstream':{'type':'roundrobin','nodes':{'origin:8080':1}},'timeout':{'connect':2,'send':5,'read':5}},
        {'id':'management','priority':100,'uris':['/management','/management/*','/access/*','/oidc/callback'],'plugins':{'openid-connect':oidc},'upstream':upstream},
        {'id':'management-assets','priority':100,'uri':'/assets/*','upstream':upstream},
        {'id':'public-catalog','priority':0,'uri':'/*','upstream':{'type':'roundrobin','nodes':{'portal-'+n+':8080':1}}}]}
def stop():
    s=load();st=state()
    if st['container']!=s['database']:raise RuntimeError('Owned database identity mismatch')
    network_identity(s)
    for cid in reversed(s['containers']):own(s,cid);cmd(['docker','stop',cid])
    cmd(['docker','stop',s['database']]);print('Owned containers stopped; data, UUIDs and secrets retained')
def cleanup():
    s=load();st=state()
    if st['container']!=s.get('database',st['container']):raise RuntimeError('Database ownership changed')
    for cid in reversed(s['containers']):own(s,cid);cmd(['docker','rm','--force','--volumes',cid])
    from management_db import cleanup as dbcleanup
    dbcleanup()
    if cmd(['docker','network','inspect','--format','{{index .Labels "di.identity"}}',s['network']])!=s['id']:raise RuntimeError('Network identity mismatch')
    cmd(['docker','network','rm',s['network']]);(I/'state.json').unlink();print('Only checked owned managed resources removed')
def main(action,portal_jar=None):
    if action=='start':start(portal_jar)
    elif action=='prepare':prepare()
    elif action=='stop':stop()
    elif action=='restart':stop();start()
    elif action=='cleanup':cleanup()
    elif action=='status':
        s=load();print('\n'.join(s['gateways']))
    else:raise ValueError('Unknown managed runtime action')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['prepare','start','stop','restart','cleanup','status']);a=p.parse_args();main(a.action)
