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
    if 'column_details' in recorded:
        detailed={(r['table_name'],r['column_name']):r for r in recorded['column_details']}
        query="SELECT row_to_json(x) FROM (SELECT c.relname AS table_name,a.attname AS column_name,pg_catalog.format_type(a.atttypid,a.atttypmod) AS sql_type,a.attnotnull AS not_null,pg_get_expr(d.adbin,d.adrelid) AS default_expression FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE n.nspname='di_management' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped)x;"
        observed={(r['table_name'],r['column_name']):r for r in (json.loads(x) for x in sql(query).splitlines())}
        if set(observed)!=set(detailed)|technical:raise RuntimeError('Physical detailed column set differs')
        for key,wanted in detailed.items():
            found=observed[key]
            if found['sql_type']!=wanted['sql_type'] or found['default_expression']!=wanted['default_expression']:raise RuntimeError('Physical length/precision/default differs')
            if found['not_null']!=wanted['not_null'] and not (key[1]=='t_ili_tid' and found['not_null']):raise RuntimeError('Physical detailed nullability differs')
    print('PASS read-only physical mapping: all recorded columns/types/defaults and controlled technical additions')

if __name__=='__main__':
    verify()
