"""Map PostgreSQL UUID attributes omitted by Cayenne reverse engineering."""
from pathlib import Path
from xml.etree import ElementTree as ET

MAP = Path(__file__).resolve().parents[1] / 'src/main/resources/cayenne/management.map.xml'
NAMESPACE = 'http://cayenne.apache.org/schema/13/modelMap'
NS = '{' + NAMESPACE + '}'
ET.register_namespace('', NAMESPACE)

tree = ET.parse(MAP)
root = tree.getroot()
columns = {}
for entity in root.findall(NS + 'db-entity'):
    if entity.get('schema') != 'di_management':
        raise RuntimeError('Unexpected Cayenne schema')
    for attribute in entity.findall(NS + 'db-attribute'):
        name = attribute.get('name')
        columns[entity.get('name'), name] = attribute.get('type')

for entity in root.findall(NS + 'obj-entity'):
    for attribute in entity.findall(NS + 'obj-attribute'):
        if attribute.get('type') is None:
            table = entity.get('dbEntityName')
            column = attribute.get('db-attribute-path')
            if column not in ('t_ili_tid', 'commandid') or columns[table, column] != 'OTHER':
                raise RuntimeError(f'Unexpected unmapped JDBC type: {table}.{column}')
            attribute.set('type', 'java.util.UUID')

ET.indent(tree, space='\t')
tree.write(MAP, encoding='utf-8', xml_declaration=True)
