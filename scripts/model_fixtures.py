"""Deterministic synthetic XTF2.4 model fixtures; never production seeding."""
from pathlib import Path
import copy, uuid, xml.etree.ElementTree as E
I='http://www.interlis.ch/xtf/2.4/INTERLIS'; C='SO_AGI_DataInfrastructure_Catalog_20261105'; A='SO_AGI_DataInfrastructure_Access_20261105'
N={C:'http://www.interlis.ch/xtf/2.4/'+C,A:'http://www.interlis.ch/xtf/2.4/'+A}
for prefix,uri in [('ili',I),('catalog',N[C]),('access',N[A])]:E.register_namespace(prefix,uri)
def oid(key):return str(uuid.uuid5(uuid.UUID('8143cc8c-f5e6-41e7-8552-bdcd57be9ae9'),key))
def ref(key):return ('ref',oid(key))
def fixture():
 root=E.Element('{'+I+'}transfer');h=E.SubElement(root,'{'+I+'}headersection');m=E.SubElement(h,'{'+I+'}models')
 for name in [C,A]:E.SubElement(m,'{'+I+'}model').text=name
 E.SubElement(h,'{'+I+'}sender').text='Dateninfrastruktur MA-A synthetic fixture'
 d=E.SubElement(root,'{'+I+'}datasection');baskets={name:E.SubElement(d,'{'+N[name]+'}'+topic,{'{'+I+'}bid':oid(topic)}) for name,topic in [(C,'Catalog'),(A,'Access')]}
 objects={}
 def add(model,cls,key,**attrs):
  node=E.SubElement(baskets[model],'{'+N[model]+'}'+cls,{'{'+I+'}tid':oid(key)})
  for k,v in attrs.items():
   if v is None:continue
   child=E.SubElement(node,'{'+N[model]+'}'+k)
   if isinstance(v,tuple):child.set('{'+I+'}ref',v[1])
   else:child.text=str(v).lower() if isinstance(v,bool) else str(v)
  objects[key]=node;return node
 for name in ['agi','afin','other']:add(C,'Office',name,identifier=name,name='Testamt '+name,abbreviation=name.upper(),active=True)
 for key,kind,access,status in [('open','standalone','open','published'),('series','series','restricted','published'),('draft','standalone','restricted','draft')]:
  add(C,'Dataset',key,identifier='ch.so.demo.'+key,kind=kind,title='Demodaten '+key,description='Grüezi — Statistik\n\n**Mehrzeilig**, keine Kopie je Ausgabe.',contactEmail='mailto:demo@example.invalid',accessLevel=access,publicationStatus=status,modified='2026-11-04',origin='cantonal',frequency='annually',ownerOffice=ref('agi'),publisherOffice=ref('afin'),currentIssue=ref(key+'-2025' if kind=='series' else key+'-default') if status=='published' else None)
 for key,ds,label in [('open-default','open','default'),('series-2024','series','2024'),('series-2025','series','2025')]:
  add(C,'DatasetIssue',key,issueKey=label,label=label,publicationStatus='published',referenceDate='2025-12-31' if label=='2025' else None,modified='2026-11-04',dataset=ref(ds),currentVersion=ref(key+'-v1'))
 add(C,'DataSchema','schema',identifier='demo-schema',description='Definition mit Unicode ü')
 add(C,'AttributeDefinition','attr',name='anzahl',dataType='INTEGER',ordinal=1,mandatory=True,description='Anzahl Personen',schema=ref('schema'))
 for key,issue,status in [('open-default-v1','open-default','available'),('series-2024-v1','series-2024','available'),('series-2025-v1','series-2025','available'),('prepared-version','series-2025','prepared'),('withdrawn-version','series-2024','withdrawn')]:
  add(C,'DataVersion',key,versionKey=key,sequence=1 if key.endswith('v1') else 2,availability=status,acceptedAt='2026-10-01T12:00:00' if status=='available' else None,validationStatus='unknown',issue=ref(issue),schema=ref('schema'))
 for key,version,fmt,status in [('open-csv','open-default-v1','csv','available'),('old-csv','series-2024-v1','csv','available'),('new-csv','series-2025-v1','csv','available'),('new-parquet','series-2025-v1','parquet','available'),('prepared-file','prepared-version','csv','prepared'),('withdrawn-file','withdrawn-version','csv','withdrawn')]:
  filename='data.'+fmt
  add(C,'Distribution',key,format=fmt,fileName=filename,mediaType='text/csv' if fmt=='csv' else 'application/vnd.apache.parquet',availability=status,byteSize=42 if status=='available' else None,sha256='a'*64 if status=='available' else None,objectKey='downloads/'+oid(key)+'/'+filename,version=ref(version))
 add(C,'DatasetTheme','theme',theme='Bevoelkerung',dataset=ref('series'))
 add(C,'DatasetKeyword','keyword',keyword='Bevölkerung',dataset=ref('series'))
 for key,kind in [('anna','user'),('ben','user'),('claudia','user'),('dora','user'),('bootstrap','service'),('team','group')]:
  add(A,'Principal',key,kind=kind,issuer='urn:so:agi:di:local-groups' if kind=='group' else 'https://identity.example.invalid/realms/test',subject=key,displayName=key.capitalize(),active=True,provisionedAt='2026-01-01T00:00:00')
 add(A,'GroupMembership','membership',validFrom='2026-01-01T00:00:00',reason='Testteam',group=ref('team'),member=ref('anna'))
 for capability in ['manage_metadata','publish','manage_access']:
  add(A,'AuthorityAssignment','authority-'+capability,capability=capability,validFrom='2026-01-01T00:00:00',reason='Bootstrap Testzuständigkeit',principal=ref('claudia'),assignedBy=ref('bootstrap'),office=ref('agi'))
 add(A,'AccessRequest','request',reason='Forschung\nmit Statistik',requestedAt='2026-10-01T10:00:00',status='approved',applicant=ref('anna'),dataset=ref('series'))
 add(A,'AccessRequest','denied-request',reason='Weitere Analyse',requestedAt='2026-10-01T10:00:00',status='denied',applicant=ref('ben'),dataset=ref('series'))
 decisions=[('approval','approve','anna','request'),('denial','deny','ben','denied-request'),('direct','direct_grant','ben',None),('group-decision','direct_grant','team',None),('future-decision','direct_grant','anna',None),('expired-decision','direct_grant','anna',None),('revoked-decision','direct_grant','anna',None),('revocation','revoke','anna',None)]
 for key,kind,beneficiary,request in decisions:
  add(A,'AccessDecision',key,kind=kind,decidedAt='2026-10-01T12:00:00',reason='Nachvollziehbarer Testentscheid\nGrüezi',commandId=oid('command-'+key),dataset=ref('series'),beneficiary=ref(beneficiary),actor=ref('claudia'),request=ref(request) if request else None)
 for key,person,decision,start,end,revoke in [('grant','anna','approval','2026-01-01T00:00:00',None,False),('direct-grant','ben','direct','2026-01-01T00:00:00',None,False),('group-grant','team','group-decision','2026-01-01T00:00:00',None,False),('future-grant','anna','future-decision','2099-01-01T00:00:00',None,False),('expired-grant','anna','expired-decision','2020-01-01T00:00:00','2021-01-01T00:00:00',False),('revoked-grant','anna','revoked-decision','2026-01-01T00:00:00',None,True)]:
  add(A,'AccessGrant',key,permission='read_data',validFrom=start,validUntil=end,revokedAt='2026-10-02T12:00:00' if revoke else None,dataset=ref('series'),beneficiary=ref(person),grantingDecision=ref(decision),revocationDecision=ref('revocation') if revoke else None)
 add(A,'CatalogAction','publish-action',performedAt='2026-10-01T12:00:00',actionCode='publish',reason='Katalog freigegeben',commandId=oid('publish-command'),dataset=ref('series'),actor=ref('claudia'))
 return root,objects

def write(root,path):E.indent(root,space='  ');E.ElementTree(root).write(path,encoding='utf-8',xml_declaration=True)
if __name__=='__main__':
 p=Path(__file__).resolve().parents[1]/'models/fixtures';root,objects=fixture();write(root,p/'valid-workflows.xtf')
 # Negative files remain full, internally referenced datasets. Each changes ONE contract.
 for case,key,field,value in [('invalid-interval','expired-grant','validUntil','2019-01-01T00:00:00'),('invalid-scope','authority-publish','dataset',ref('series')),('invalid-principal-kind','membership','member',ref('team')),('invalid-available','new-csv','sha256',None),('duplicate-identifier','draft','identifier','ch.so.demo.open')]:
  root,objects=fixture();node=objects[key];el=node.find('{'+N[A if key in ['expired-grant','authority-publish','membership'] else C]+'}'+field)
  if value is None:node.remove(el)
  elif isinstance(value,tuple):
   if el is None:el=E.SubElement(node,'{'+N[A]+'}'+field)
   el.set('{'+I+'}ref',value[1])
  else:el.text=value
  write(root,p/(case+'.xtf'))
