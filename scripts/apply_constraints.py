"""Explicit checked additional migration on this harness only; no startup DDL."""
import json
from management_db import R,sql
from audit_setup import resolve,ident
migration=R/'db/migrations/V002__constraints.sql'
# A single known additive revision; never destructive schema reimport.
if sql("SELECT count(*) FROM di_ops.schema_revision WHERE revision='MA-B-2';").splitlines()[-1]=='0':
 commands=['BEGIN;', 'SELECT pg_advisory_xact_lock(749583200);',migration.read_text()]
 for p in resolve():
  t=ident(p['table'])
  commands+=['ALTER TABLE di_management.'+t+' ALTER COLUMN t_ili_tid SET NOT NULL;', 'CREATE UNIQUE INDEX '+ident('di_uuid_'+p['table'])+' ON di_management.'+t+'(t_ili_tid);']
  if p['table'] in ['dataset','datasetissue','dataversion','accessdecision','accessgrant','groupmembership','accessrequest']:
   commands+=['CREATE CONSTRAINT TRIGGER di_validate_model AFTER INSERT OR UPDATE ON di_management.'+t+' DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION di_ops.validate_references();']
  if p['table'] in ['dataset','datasetissue','dataversion','distribution','dataschema','attributedefinition','principal','accessrequest']:
   commands+=['CREATE TRIGGER di_protect_delivery BEFORE UPDATE OR DELETE ON di_management.'+t+' FOR EACH ROW EXECUTE FUNCTION di_ops.protect_delivery();']
 commands+=["INSERT INTO di_ops.schema_revision(revision) VALUES('MA-B-2');",'COMMIT;']
 print(sql('\n'.join(commands)))
else:print('MA-B-2 already installed; state retained')
