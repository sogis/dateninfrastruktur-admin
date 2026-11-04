"""Resolve M3 reverse-engineering's untyped PostgreSQL UUID attributes from verified SQL metadata."""
from pathlib import Path
import json, xml.etree.ElementTree as E
R=Path(__file__).resolve().parents[1];p=R/'src/main/resources/cayenne/management.map.xml'
n='http://cayenne.apache.org/schema/13/modelMap';E.register_namespace('',n);ns='{'+n+'}'
m=json.loads((R/'db/model-mapping.json').read_text());types={(x['table_name'],x['column_name']):x['data_type'] for x in m['columns']}
t=E.parse(p)
for entity in t.getroot().findall(ns+'db-entity'):
 for a in entity.findall(ns+'db-attribute'):
  if a.get('name')=='t_id':
   column=next(x for x in m['columns'] if x['table_name']==entity.get('name') and x['column_name']=='t_id')
   if column['data_type']!='bigint' or column['column_default']!="nextval('di_management.t_ili2db_seq'::regclass)":raise RuntimeError('Unknown ili2pg PK strategy')
   a.attrib.pop('isGenerated',None)
for entity in t.getroot().findall(ns+'obj-entity'):
 for a in entity.findall(ns+'obj-attribute'):
  if a.get('db-attribute-path') in ('t_createdate','t_lastchange','t_user','row_version'):
   entity.remove(a)
   continue
  if not a.get('type'):
   key=(entity.get('dbEntityName'),a.get('db-attribute-path'))
   if types.get(key)!='uuid':raise RuntimeError('Unresolved non-UUID mapping: '+str(key))
   a.set('type','java.util.UUID')
E.indent(t,space='\t');t.write(p,encoding='utf-8',xml_declaration=True)
