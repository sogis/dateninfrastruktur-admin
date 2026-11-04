"""Real C->D additive upgrade over existing UUID/audit data; retain prior B->C path too."""
import json,hashlib
from management_db import R,sql
from upgrade_contract import apply
plan=json.loads((R/'db/upgrade-MA-C-to-MA-D.json').read_text())
assert sql('SELECT count(*) FROM di_ops.legacy_origin;')=='0','No provenance may be removed; run before import'
assert int(sql('SELECT count(*) FROM di_management.dataset;'))>0,'Nonempty baseline required'
def baseline():return sql("SELECT count(*),md5(string_agg(to_jsonb(d)::text,',' ORDER BY t_id)) FROM di_management.dataset d; SELECT count(*) FROM audit.row_change;")
before=baseline();source=hashlib.sha256((R/'db/persistence-contract.properties').read_bytes()).hexdigest()
sql("DROP TABLE di_ops.legacy_origin; DELETE FROM di_ops.schema_revision WHERE revision='MA-D-1'; UPDATE di_ops.contract_fingerprint SET fingerprint='"+plan['fromFingerprint']+"' WHERE singleton;")
apply();assert baseline()==before
assert source==hashlib.sha256((R/'db/persistence-contract.properties').read_bytes()).hexdigest()
assert sql("SELECT to_regclass('di_ops.legacy_origin') IS NOT NULL;")=='t'
print('PASS explicit C->D upgrade retains nonempty UUID/data/audit and source artifact')
