import hashlib,json,re
from management_db import R
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def calculate():
    profile=json.loads((R/'db/mapping-profile.json').read_text())
    if profile['tools']!=json.loads((R/'db/toolchain.json').read_text()):raise RuntimeError('Pinned toolchain/profile mismatch')
    for name,expected in profile['models'].items():
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*\.ili',name) or digest(R/'models'/name)!=expected:raise RuntimeError('Pinned management model bytes differ')
    files={'profile.sha256':R/'db/mapping-profile.json',
           'physical_mapping.sha256':R/'db/model-mapping.json',
           'datamap.sha256':R/'src/main/resources/cayenne/management.map.xml',
           'storage_contract.sha256':R/'db/storage-contract.sql',
           'ili_metadata.sha256':R/'db/reference-ma-g2-metadata.json',
           'ingestion_model_contract.sha256':R/'db/ingestion-model-contract.sql',
           'ingestion_contract.sha256':R/'db/ingestion-contract.sql'}
    files.update({'migration.'+p.name+'.sha256':p for p in sorted((R/'db/migrations').glob('*.sql'))})
    body=''.join(key+'='+digest(path)+'\n' for key,path in sorted(files.items()))
    value=hashlib.sha256(body.encode()).hexdigest()
    return body,value
