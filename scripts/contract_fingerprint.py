"""Persist one reproducible contract of models, ili2pg mapping, DataMap and migrations."""
import hashlib
from management_db import R,sql

from persistence_contract import calculate
body,value=calculate()
exists=sql("SELECT to_regclass('di_ops.contract_fingerprint') IS NOT NULL;")=='t'
existing=sql('SELECT fingerprint FROM di_ops.contract_fingerprint WHERE singleton;') if exists else ''
if existing and existing!=value:
    raise RuntimeError('Known database contract differs: explicit versioned migration required')
sql((R/'db/migrations/V006__contract_fingerprint.sql').read_text())
sql("INSERT INTO di_ops.contract_fingerprint(singleton,fingerprint) VALUES(true,'"+value+"') ON CONFLICT DO NOTHING;")
(R/'db/persistence-contract.properties').write_text(body)
print('Persisted reproducible schema/mapping/migration contract fingerprint')
