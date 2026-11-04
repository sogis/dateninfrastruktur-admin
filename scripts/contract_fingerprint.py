"""Persist one reproducible contract of models, ili2pg mapping, DataMap and migrations."""
import hashlib
from management_db import R,sql

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

files={'profile.sha256':R/'db/mapping-profile.json',
       'physical_mapping.sha256':R/'db/model-mapping.json',
       'datamap.sha256':R/'src/main/resources/cayenne/management.map.xml'}
files.update({'migration.'+p.name+'.sha256':p for p in sorted((R/'db/migrations').glob('*.sql'))})
body=''.join(key+'='+digest(path)+'\n' for key,path in sorted(files.items()))
(R/'db/persistence-contract.properties').write_text(body)
value=hashlib.sha256(body.encode()).hexdigest()
sql((R/'db/migrations/V006__contract_fingerprint.sql').read_text())
existing=sql('SELECT fingerprint FROM di_ops.contract_fingerprint WHERE singleton;')
if existing and existing!=value:
    raise RuntimeError('Known database contract differs: explicit versioned migration required')
sql("INSERT INTO di_ops.contract_fingerprint(singleton,fingerprint) VALUES(true,'"+value+"') ON CONFLICT DO NOTHING;")
print('Persisted reproducible schema/mapping/migration contract fingerprint')
