"""Actual Java CLI refuses missing audit before any import/provenance/receipt mutation."""
import subprocess,hashlib
from management_db import R,sql
source=R.parent/'dateninfrastruktur-sodata/spec/fixtures/published_catalog_examples_only.xtf'
def snapshot():return sql("SELECT count(*) FROM di_management.dataset; SELECT count(*) FROM audit.row_change; SELECT count(*) FROM di_ops.legacy_origin; SELECT count(*) FROM di_ops.command_receipt;")
before=snapshot()
sql('ALTER TABLE di_management.dataset DISABLE TRIGGER di_audit_log;')
try:
    result=subprocess.run(['python3',str(R/'scripts/legacy_import.py'),str(source),'--apply','--select=ch.so.gemeindegrenzen','--actor-issuer=urn:di:operator','--actor-subject=negative-cli-proof','--report='+str(R/'.runtime/operator-contract-negative.json')],capture_output=True,text=True)
    assert result.returncode!=0 and 'Management schema/audit contract is not ready' in result.stdout+result.stderr
    assert before==snapshot(),'Rejected operator command changed data/provenance/receipt/audit'
finally:sql('ALTER TABLE di_management.dataset ENABLE TRIGGER di_audit_log;')
print('PASS actual Java operator CLI refuses disabled audit before any Fach/provenance/receipt write')
