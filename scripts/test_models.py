#!/usr/bin/env python3
"""MA-A: real compiler/validator and semantic checks over compiler IMD output."""
import hashlib, json, pathlib, subprocess, urllib.request, zipfile, xml.etree.ElementTree as E
from model_fixtures import I, C, A, N, oid
R=pathlib.Path(__file__).resolve().parents[1]; OUT=R/'.runtime/model-tests';OUT.mkdir(parents=True,exist_ok=True)
def run(args,name,expected=0,needle=None):
 p=subprocess.run(args,cwd=R,capture_output=True,text=True);log=p.stdout+p.stderr;(OUT/(name+'.log')).write_text(log)
 assert p.returncode==expected,(name,p.returncode,log[-2500:])
 if needle: assert needle in log,(name,'missing expected diagnostic',log[-2500:])
 assert 'compiler failed' not in log and 'incompatible values' not in log,(name,'tool failure, not semantic rejection')
 return log
for name,item in json.loads((R/'db/toolchain.json').read_text()).items():
 target=R/'.runtime/tools'/name
 if not target.exists():
  target.mkdir(parents=True);data=urllib.request.urlopen(item['url']).read();assert hashlib.sha256(data).hexdigest()==item['sha256']
  archive=target.parent/(name+'.zip');archive.write_bytes(data)
  with zipfile.ZipFile(archive) as z:z.extractall(target)
compiler=next((R/'.runtime/tools/ili2c').glob('ili2c.jar')); validator=next((R/'.runtime/tools/ilivalidator').glob('ilivalidator-*.jar'))
models=[str(R/'models'/(name+'.ili')) for name in [C,A]];assert len(list((R/'models').glob('*.ili')))==2
imd=OUT/'models-imd.xtf'
run(['java','-jar',str(compiler),'--no-auto','-oIMD16','--out',str(imd),*models],'compile')
validate=['java','-jar',str(validator),'--modeldir',str(R/'models'),'--allObjectsAccessible','--forceTypeValidation']
run([*validate,str(R/'models/fixtures/valid-workflows.xtf')],'valid-workflows')
print('M03 PASS: both models compiled and positive XTF validated')
M='{http://www.interlis.ch/xtf/2.4/IlisMeta16}';ii='{'+I+'}';meta=E.parse(imd).getroot()
def ref(e,name):
 child=e.find(M+name);return child.get(ii+'ref') if child is not None else None
classes={e.get(ii+'tid'):e for e in meta.iter(M+'Class') if e.get(ii+'tid','').startswith((C+'.',A+'.'))}
for name,element in classes.items():
 if element.findtext(M+'Kind')=='Class':assert ref(element,'Oid')=='INTERLIS.UUIDOID',name
attrs={}
for e in meta.iter(M+'AttrOrParam'):
 parent=ref(e,'AttrParent')
 if parent:attrs.setdefault(parent,set()).add(e.findtext(M+'Name'))
for child in ['DatasetIssue','DataVersion','Distribution']:
 assert not attrs[C+'.Catalog.'+child].intersection({'accessLevel','description','ownerOffice','contactEmail','title'}),child
role_targets={ref(e,'CRT'):ref(e,'BaseClass') for e in meta.iter(M+'BaseClass') if e.find(M+'CRT') is not None}
associations={}
for e in meta.iter(M+'Role'):
 associations.setdefault(ref(e,'Association'),set()).add(role_targets.get(e.get(ii+'tid')))
for name in ['AccessRequest','AccessDecision','AccessGrant']:
 linked=set().union(*(targets for targets in associations.values() if A+'.Access.'+name in targets))
 assert {x for x in linked if x and x.startswith(C+'.Catalog.')}=={C+'.Catalog.Dataset'},(name,linked)
assert 'accessLevel' in attrs[C+'.Catalog.Dataset']
print('M04 PASS: compiled UUID identities, sole dataset rights anchor, no child metadata copies')
expected={'invalid-interval':A+'.Access.AccessGrant.Constraint1','invalid-scope':A+'.Access.AuthorityAssignment.Constraint2','invalid-principal-kind':A+'.Access.GroupMembership.Constraint3','invalid-available':C+'.Catalog.Distribution.Constraint2','duplicate-identifier':C+'.Catalog.Dataset.Constraint1'}
for case,needle in expected.items():run([*validate,str(R/'models/fixtures'/(case+'.xtf'))],case,1,needle)
print('M05 PASS: five semantic invalid fixtures rejected for the intended constraints')
policies={(ref(e,'MetaElement'),e.findtext(M+'Name')):e.findtext(M+'Value') for e in meta.iter(M+'MetaAttribute')}
for name in classes:
 assert policies.get((name,'di.audit'))=='full',name
assert policies[(A+'.Access.Principal.displayName','di.audit.redact')]=='true'
tree=E.parse(R/'models/fixtures/valid-workflows.xtf');objects={e.get(ii+'tid'):e for e in tree.iter() if e.get(ii+'tid')}
def field(key,model,name):return objects[oid(key)].find('{'+N[model]+'}'+name)
assert field('direct',A,'request') is None
assert field('approval',A,'request').get(ii+'ref')==oid('request')
assert field('grant',A,'grantingDecision').get(ii+'ref')==oid('approval')
assert field('revoked-grant',A,'revocationDecision').get(ii+'ref')==oid('revocation')
assert field('series',C,'description').text=='Grüezi — Statistik\n\n**Mehrzeilig**, keine Kopie je Ausgabe.'
assert field('request',A,'reason').text=='Forschung\nmit Statistik'
assert field('expired-grant',A,'validUntil').text<'2026-01-01T00:00:00'
assert field('future-grant',A,'validFrom').text>'2026-12-31T23:59:59'
assert field('revoked-grant',A,'revokedAt') is not None
# Pinned legacy vocabulary codes must be preserved, with no third new base model.
import re
vendor=(R/'models/vendor/SO_AGI_DataCatalog_Base_20260529.ili').read_text()
catalog=(R/'models'/ (C+'.ili')).read_text()
assert hashlib.sha256(vendor.encode()).hexdigest()=='fbb1bccc0e84795284f9fb1a3c0be9e6db516c3c4b8ae43ad2f856cf70948431'
for domain in ['Origin','Theme','MaintenanceFrequencyCode']:
 block=lambda s:re.search(r'\b'+domain+r' = \((.*?)\n    \);',s,re.S).group(1)
 assert block(vendor)==block(catalog),domain
print('M06 PASS: workflow identities, direct grant without request, revoke, MTEXT, audit metadata, legacy vocabularies')
