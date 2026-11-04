#!/usr/bin/env python3
"""Targeted PostgreSQL boundary/lifecycle tests on the exclusively owned harness."""
import json, os, subprocess, uuid
from management_db import R, D, state, sql, cmd
from audit_setup import resolve, verify, apply
s = state()
roles = json.loads((D/'roles.json').read_text())

def role(user, query, expected=0, needle=None):
    env = os.environ.copy()
    env['PGPASSWORD'] = roles[user]
    p = subprocess.run(['docker','exec','-i','--env','PGPASSWORD',s['container'],
        'psql','-X','-h','127.0.0.1','-U',user,'-d','dateninfrastruktur_management',
        '-v','ON_ERROR_STOP=1','-At'], env=env,input=query,capture_output=True,text=True)
    if p.returncode != expected:
        raise AssertionError((user,p.returncode,p.stdout,p.stderr))
    if needle and needle not in p.stderr:
        raise AssertionError('Wrong error: '+p.stderr)
    return p.stdout.strip()

def transaction(query):
    context = (
        "SELECT set_config('di.actor_issuer','urn:technical:management-test',true),"
        "set_config('di.actor_subject','bootstrap',true),"
        "set_config('di.actor_kind','service',true),"
        "set_config('di.action','test.persistence',true),"
        f"set_config('di.request_id','{uuid.uuid4()}',true),"
        f"set_config('di.command_id','{uuid.uuid4()}',true);")
    return 'BEGIN;'+context+query+'COMMIT;'

root = sql('SELECT t_id FROM di_management.dataset ORDER BY t_id LIMIT 1;')
role('di_writer', transaction(
    f"UPDATE di_management.dataset SET publicationstatus='published' WHERE t_id={root};"))
assert int(role('di_public','SELECT count(*) FROM api_public.datasets;')) >= 1
assert role('di_public',"SELECT has_table_privilege(current_user,'api_public.datasets','UPDATE');") == 'f'
for user, statement in [
    ('di_public','SELECT * FROM di_management.dataset;'),
    ('di_public','SELECT * FROM audit.row_change;'),
    ('di_public',"UPDATE di_management.dataset SET title='bad';"),
    ('di_public','SELECT * FROM di_management.accessgrant;'),
    ('di_writer','DELETE FROM audit.row_change;'),
    ('di_writer','SELECT * FROM audit.row_change;'),
    ('di_writer','TRUNCATE di_management.dataset CASCADE;'),
    ('di_writer','CREATE TABLE di_management.bad(id int);'),
    ('di_writer','SET ROLE di_owner;'),
    ('di_authz',"UPDATE di_management.dataset SET title='bad';"),
    ('di_authz','SELECT * FROM di_management.accessrequest;')]:
    role(user, statement, 3, 'permission denied')
assert sql("SELECT count(*) FROM pg_roles WHERE rolname IN "
    "('di_writer','di_authz','di_public') AND (rolsuper OR rolbypassrls OR rolcreaterole);") == '0'

principal, decision, grant = [str(uuid.uuid4()) for _ in range(3)]
role('di_writer', transaction(f"""
    INSERT INTO di_management.principal(t_ili_tid,akind,aissuer,asubject,aactive,provisionedat)
    VALUES('{principal}','user','urn:test','{principal}',true,'2026-01-01');
    INSERT INTO di_management.accessdecision
      (t_ili_tid,akind,decidedat,reason,commandid,actor,beneficiary,dataset)
    SELECT '{decision}','direct_grant','2026-01-01','technical fixture',
      '{uuid.uuid4()}',t_id,t_id,{root}
    FROM di_management.principal WHERE t_ili_tid='{principal}';
    INSERT INTO di_management.accessgrant
      (t_ili_tid,permission,validfrom,beneficiary,dataset,grantingdecision)
    SELECT '{grant}','read_data','2026-01-01',beneficiary,dataset,t_id
    FROM di_management.accessdecision WHERE t_ili_tid='{decision}';
"""))
role('di_writer',transaction(
    f"UPDATE di_management.accessdecision SET reason='rewrite' WHERE t_ili_tid='{decision}';"),
    3,'immutable')
role('di_writer',transaction(
    f"DELETE FROM di_management.accessdecision WHERE t_ili_tid='{decision}';"),
    3,'deletion forbidden')
role('di_writer',transaction(
    f"UPDATE di_management.accessgrant SET validuntil='2099-01-01' WHERE t_ili_tid='{grant}';"),
    3,'immutable')
role('di_writer',transaction(
    f"UPDATE di_management.principal SET asubject='different-person' WHERE t_ili_tid='{principal}';"),
    3,'identity immutable')
noop_query = f'SELECT count(*) FROM audit.row_change; SELECT row_version FROM di_management.dataset WHERE t_id={root};'
before = sql(noop_query)
role('di_writer',transaction(f'UPDATE di_management.dataset SET title=title WHERE t_id={root};'))
assert before == sql(noop_query)
schema,version=[str(uuid.uuid4()) for _ in range(2)]
issue=sql(f'SELECT t_id FROM di_management.datasetissue WHERE dataset={root} LIMIT 1;')
role('di_writer',transaction(f"""
 INSERT INTO di_management.dataschema(t_ili_tid,identifier,adescription) VALUES('{schema}','schema-{schema}','synthetic accepted schema');
 INSERT INTO di_management.dataversion(t_ili_tid,versionkey,asequence,availability,acceptedat,validationstatus,issue,aschema)
 SELECT '{version}','version-{version}',1,'available','2026-11-04T12:00:00','unknown',{issue},t_id
 FROM di_management.dataschema WHERE t_ili_tid='{schema}';
 UPDATE di_management.datasetissue SET currentversion=(SELECT t_id FROM di_management.dataversion WHERE t_ili_tid='{version}')
 WHERE t_id={issue};
"""))
role('di_writer',transaction(
    f"UPDATE di_management.dataversion SET availability='withdrawn' WHERE t_ili_tid='{version}';"),
    3,'current version must remain available')
role('di_writer',transaction(
    f"UPDATE di_management.dataschema SET adescription='rewrite' WHERE t_ili_tid='{schema}';"),
    3,'schema used')
role('di_writer',transaction(f"""
 INSERT INTO di_management.attributedefinition(t_ili_tid,aname,datatype,ordinal,mandatory,aschema)
 SELECT '{uuid.uuid4()}','new-column','INTEGER',1,true,t_id FROM di_management.dataschema WHERE t_ili_tid='{schema}';
"""),3,'schema used')
prepared,file_id=[str(uuid.uuid4()) for _ in range(2)]
role('di_writer',transaction(f"""
 INSERT INTO di_management.dataversion(t_ili_tid,versionkey,asequence,availability,validationstatus,issue)
 VALUES('{prepared}','prepared-{prepared}',2,'prepared','unknown',{issue});
 INSERT INTO di_management.distribution(t_ili_tid,aformat,filename,mediatype,availability,storageprofile,objectkey,version)
 SELECT '{file_id}','csv','demo.csv','text/csv','prepared','managed-private',
 'downloads/{file_id}/demo.csv',t_id FROM di_management.dataversion WHERE t_ili_tid='{prepared}';
"""))
role('di_writer',transaction(f"""
 DELETE FROM di_management.dataversion WHERE t_ili_tid='{prepared}';
 DELETE FROM di_management.distribution WHERE t_ili_tid='{file_id}';
"""))
expected_root=sql(f'SELECT t_ili_tid FROM di_management.dataset WHERE t_id={root};')
assert sql("SELECT aggregate_dataset_oid FROM audit.row_change WHERE table_name='distribution' "
    f"AND object_oid='{file_id}' AND operation='D';")==expected_root
group = str(uuid.uuid4())
membership = str(uuid.uuid4())
role('di_writer',transaction(f"""
 INSERT INTO di_management.principal(t_ili_tid,akind,aissuer,asubject,aactive,provisionedat)
 VALUES('{group}','group','urn:so:agi:di:local-groups','{group}',true,'2026-01-01');
 INSERT INTO di_management.groupmembership(t_ili_tid,validfrom,reason,agroup,amember)
 SELECT '{membership}','2026-01-01','explicit synthetic team',g.t_id,m.t_id
 FROM di_management.principal g,di_management.principal m
 WHERE g.t_ili_tid='{group}' AND m.t_ili_tid='{principal}';
"""))
assert sql("SELECT count(*) FROM audit.row_change WHERE table_name='groupmembership' "
    f"AND object_oid='{membership}' AND operation='I';") == '1'
saved = sql(f'SELECT title FROM di_management.dataset WHERE t_id={root};')
sql("ALTER TABLE audit.row_change ADD CONSTRAINT forced_audit_failure CHECK(false) NOT VALID;")
try:
    role('di_writer',transaction(
        f"UPDATE di_management.dataset SET title='must roll back' WHERE t_id={root};"),
        3,'forced_audit_failure')
    assert saved == sql(f'SELECT title FROM di_management.dataset WHERE t_id={root};')
finally:
    sql('ALTER TABLE audit.row_change DROP CONSTRAINT forced_audit_failure;')
print('M09/M10 PASS: positive public control, actual role denials, immutable decisions/grant basis, no-op')

policies = resolve()
verify(policies)
sql('ALTER TABLE di_management.groupmembership DISABLE TRIGGER di_audit_log;')
try:
    try:
        verify(policies)
    except RuntimeError as error:
        assert 'coverage failed' in str(error)
    else:
        raise AssertionError('Disabled relationship audit trigger accepted')
finally:
    sql('ALTER TABLE di_management.groupmembership ENABLE TRIGGER di_audit_log;')
apply(policies)
print('M11 PASS: disabled relationship trigger detected; installer rerun verified')

identity_query = """SELECT count(*),md5(string_agg(t_ili_tid::text,',' ORDER BY t_id))
    FROM di_management.dataset; SELECT count(*) FROM audit.row_change;"""
before = sql(identity_query)
cmd(['python3',str(R/'scripts/apply_constraints.py')])
apply(policies)
cmd(['python3',str(R/'scripts/management_db.py'),'schema'])
assert before == sql(identity_query)
print('M12 PASS: setup rerun/additive revision retain UUIDs and audit')

dump = subprocess.check_output(
    ['docker','exec',s['container'],'pg_dump','-U','postgres','-d','dateninfrastruktur_management','-Fc'])
(D/'restore.dump').write_bytes(dump)
cmd(['docker','exec',s['container'],'createdb','-U','postgres','dateninfrastruktur_management_restore'])
p = subprocess.run(['docker','exec','-i',s['container'],'pg_restore','-U','postgres',
    '-d','dateninfrastruktur_management_restore','--exit-on-error'],input=dump,capture_output=True)
if p.returncode:
    raise AssertionError(p.stderr.decode()[-3000:])
restored = cmd(['docker','exec',s['container'],'psql','-X','-U','postgres',
    '-d','dateninfrastruktur_management_restore','-Atc',identity_query])
assert before == restored
rights = cmd(['docker','exec',s['container'],'psql','-X','-U','postgres',
    '-d','dateninfrastruktur_management_restore','-Atc',
    "SELECT has_table_privilege('di_public','api_public.datasets','SELECT'),"
    "has_table_privilege('di_public','di_management.dataset','SELECT');"])
assert rights == 't|f'
print('M13 PASS: separate restore retains identities/audit and public role boundary')
