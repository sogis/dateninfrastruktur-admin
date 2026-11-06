"""Read the explicit current schema manifest and produce canonical package bytes."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

R = Path(__file__).resolve().parents[1]
MANIFEST = 'db/schema/manifest.json'
PHYSICAL = 'db/schema/physical-fingerprint.txt'
PROPERTIES = 'db/persistence-contract.properties'


def input_path(root, name):
    if not isinstance(name, str):
        raise ValueError('Invalid schema input path')
    path = PurePosixPath(name)
    if not path.parts or path.is_absolute() or '..' in path.parts or path.as_posix()!=name or '\\' in name:
        raise ValueError(f'Invalid schema input path: {name}')
    target = root/path
    if target.resolve()!=target or not target.is_file():
        raise ValueError(f'Missing or linked schema input: {name}')
    return target


def unique_pairs(pairs):
    result = {}
    for key,value in pairs:
        if key in result:
            raise ValueError(f'Duplicate schema manifest key: {key}')
        result[key] = value
    return result


def load_manifest(root=R):
    manifest=json.loads(input_path(root,MANIFEST).read_text(encoding='utf-8'),object_pairs_hook=unique_pairs)
    if set(manifest)!={'schema_version','baseline','models','steps','artifacts'} or manifest['schema_version']!=1:
        raise ValueError('Unsupported schema manifest')
    if not re.fullmatch('management-[0-9]{8}',manifest['baseline']):
        raise ValueError('Invalid baseline identifier')
    models,steps,artifacts=(manifest[k] for k in ('models','steps','artifacts'))
    if not models or not all(re.fullmatch(r'models/[A-Za-z][A-Za-z0-9_]*\.ili',p) for p in models):
        raise ValueError('Invalid current model list')
    helpers={'audit_mapping','catalog_keys','catalog_triggers'}
    sql=[step for step in steps if step not in helpers]
    if not sql or set(steps)-set(sql)!=helpers or not all(re.fullmatch(r'db/schema/[a-z_]+\.sql',p) for p in sql):
        raise ValueError('Invalid current schema sequence')
    if len(set(steps))!=len(steps) or not artifacts or not all(
            re.fullmatch(r'[a-z_]+\.sha256',key) for key in artifacts):
        raise ValueError('Duplicate step or invalid artifact key')
    files=models+sql+list(artifacts.values())+[MANIFEST,PHYSICAL]
    if (len(set(files))!=len(files) or PROPERTIES in files
            or 'manifest.sha256' in artifacts or 'physical.schema.sha256' in artifacts
            or any(not p.startswith(('db/','models/','scripts/','src/main/resources/cayenne/'))
                   or '/migrations/' in p or '/upgrade/' in p or Path(p).name.startswith('upgrade-')
                   for p in files)):
        raise ValueError('Duplicate or historical schema input')
    for name in files:
        input_path(root,name)
    return manifest


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def physical_expected(root=R):
    fingerprint=input_path(root,PHYSICAL).read_text(encoding='ascii')
    if not re.fullmatch(r'[0-9a-f]{64}\n',fingerprint):
        raise ValueError('Invalid physical schema fingerprint')
    return fingerprint.strip()


def calculate(root=R):
    manifest=load_manifest(root)
    profile=json.loads(input_path(root,manifest['artifacts']['profile.sha256']).read_text())
    tools=json.loads(input_path(root,manifest['artifacts']['toolchain.sha256']).read_text())
    if profile['tools']!=tools or profile['models']!={Path(p).name:digest(input_path(root,p)) for p in manifest['models']}:
        raise ValueError('Pinned models or toolchain differ from mapping profile')
    properties={key:digest(input_path(root,p)) for key,p in manifest['artifacts'].items()}
    properties.update({
        'baseline':manifest['baseline'],
        'manifest.sha256':digest(input_path(root,MANIFEST)),
        'physical.schema.sha256':physical_expected(root),
    })
    properties.update({p.replace('/','.')+'.sha256':digest(input_path(root,p))
                       for p in manifest['models']+manifest['steps'] if '/' in p})
    body=''.join(key+'='+value+'\n' for key,value in sorted(properties.items()))
    return body,hashlib.sha256(body.encode('utf-8')).hexdigest()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    body,_=calculate()
    if args.check:
        if (R/PROPERTIES).read_bytes()!=body.encode('utf-8'):
            raise SystemExit('Packaged persistence contract differs from the manifest inputs')
    else:
        print(body,end='')
