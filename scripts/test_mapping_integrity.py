"""Scoped reproductions for the two nonblocking independent MA-B findings."""
import hashlib,subprocess
from management_db import R,sql

def run(script):
    return subprocess.run(['python3',str(R/'scripts'/script)],capture_output=True,text=True)

positive=run('verify_physical_mapping.py')
assert positive.returncode==0,positive.stderr
sql('ALTER TABLE di_management.dataset ADD COLUMN mapping_counterexample bigint;')
try:
    negative=run('verify_physical_mapping.py')
    assert negative.returncode!=0 and 'column set differs' in negative.stderr
finally:
    sql('ALTER TABLE di_management.dataset DROP COLUMN mapping_counterexample;')
assert run('verify_physical_mapping.py').returncode==0

file=R/'db/persistence-contract.properties'
before=hashlib.sha256(file.read_bytes()).hexdigest()
fingerprint=sql('SELECT fingerprint FROM di_ops.contract_fingerprint WHERE singleton;')
function_version=sql("SELECT xmin::text FROM pg_proc WHERE oid='di_ops.contract_fingerprint()'::regprocedure;")
sql("UPDATE di_ops.contract_fingerprint SET fingerprint='"+'0'*64+"' WHERE singleton;")
try:
    refusal=run('contract_fingerprint.py')
    assert refusal.returncode!=0 and 'explicit versioned migration required' in refusal.stderr
    assert before==hashlib.sha256(file.read_bytes()).hexdigest()
    assert function_version==sql("SELECT xmin::text FROM pg_proc WHERE oid='di_ops.contract_fingerprint()'::regprocedure;")
finally:
    sql("UPDATE di_ops.contract_fingerprint SET fingerprint='"+fingerprint+"' WHERE singleton;")
print('PASS MA-B-REV-01/02: live mapping drift rejected; foreign fingerprint refused before product write or DDL')
