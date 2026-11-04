"""Read-only verification of every recorded physical column, type and default."""
import json
from management_db import R,sql

def verify():
    recorded=json.loads((R/'db/model-mapping.json').read_text())
    expected={(r['table_name'],r['column_name']):r for r in recorded['columns']}
    query="""SELECT row_to_json(x) FROM (SELECT table_name,column_name,data_type,is_nullable,column_default
        FROM information_schema.columns WHERE table_schema='di_management'
        ORDER BY table_name,ordinal_position)x;"""
    actual={(r['table_name'],r['column_name']):r for r in
        (json.loads(line) for line in sql(query).splitlines())}
    policies=json.loads((R/'db/audit-policy.json').read_text())
    technical={(p['table'],'row_version') for p in policies}
    if set(actual)!=set(expected)|technical:
        raise RuntimeError('Physical mapping column set differs from the pinned ili2pg mapping plus row_version')
    for key,old in expected.items():
        new=actual[key]
        if old['data_type']!=new['data_type'] or old['column_default']!=new['column_default']:
            raise RuntimeError('Physical mapping type/default mismatch: '+'.'.join(key))
        strengthened_uuid=key[1]=='t_ili_tid' and old['data_type']=='uuid' and new['is_nullable']=='NO'
        if old['is_nullable']!=new['is_nullable'] and not strengthened_uuid:
            raise RuntimeError('Physical mapping nullability mismatch: '+'.'.join(key))
    for key in technical:
        row=actual[key]
        if (row['data_type'],row['is_nullable'],row['column_default'])!=('bigint','NO','1'):
            raise RuntimeError('Unexpected row_version mapping: '+'.'.join(key))
    print('PASS read-only physical mapping: all recorded columns/types/defaults and controlled technical additions')

if __name__=='__main__':
    verify()
