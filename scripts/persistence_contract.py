import hashlib
from management_db import R
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def calculate():
    files={'profile.sha256':R/'db/mapping-profile.json',
           'physical_mapping.sha256':R/'db/model-mapping.json',
           'datamap.sha256':R/'src/main/resources/cayenne/management.map.xml'}
    files.update({'migration.'+p.name+'.sha256':p for p in sorted((R/'db/migrations').glob('*.sql'))})
    body=''.join(key+'='+digest(path)+'\n' for key,path in sorted(files.items()))
    value=hashlib.sha256(body.encode()).hexdigest()
    return body,value
