"""Real known B->C upgrade over existing model UUIDs/audit, plus refusal of unknown state."""
import json,subprocess,hashlib
from management_db import R,sql
from upgrade_contract import apply

assert sql('SELECT count(*) FROM di_ops.command_receipt;')=='0','Run before workflow tests; never drop live command receipts'
assert int(sql('SELECT count(*) FROM di_management.dataset;'))>0,'Nonempty audited baseline is mandatory'
plan=json.loads((R/'db/upgrade-MA-B-to-MA-C.json').read_text())
def baseline():
    return sql("SELECT count(*),md5(string_agg(to_jsonb(d)::text,',' ORDER BY t_id)) FROM di_management.dataset d; SELECT count(*) FROM audit.row_change;")
before=baseline()
sql("DROP TABLE di_ops.command_receipt; DELETE FROM di_ops.schema_revision WHERE revision='MA-C-1'; UPDATE di_ops.contract_fingerprint SET fingerprint='"+plan['fromFingerprint']+"' WHERE singleton;")
apply()
assert baseline()==before,'Additive upgrade changed existing catalogue/audit data'
current=sql('SELECT fingerprint FROM di_ops.contract_fingerprint WHERE singleton;')
file=R/'db/persistence-contract.properties';source=hashlib.sha256(file.read_bytes()).hexdigest()
sql("UPDATE di_ops.contract_fingerprint SET fingerprint='"+'f'*64+"' WHERE singleton;")
try:
    result=subprocess.run(['python3',str(R/'scripts/setup.py')],capture_output=True,text=True)
    assert result.returncode!=0 and 'explicit reviewed upgrade required' in result.stderr
    assert before==baseline() and source==hashlib.sha256(file.read_bytes()).hexdigest()
finally:sql("UPDATE di_ops.contract_fingerprint SET fingerprint='"+current+"' WHERE singleton;")
print('PASS nonempty B->C upgrade preserves UUID/data/audit; unknown predecessor refuses setup before product mutation')
