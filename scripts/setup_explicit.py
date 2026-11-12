#!/usr/bin/env python3
"""Create or inspect the explicit management schema and transfer bootstrap XTF."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import subprocess
import stat
import sys
from tempfile import NamedTemporaryFile, TemporaryDirectory
from uuid import UUID
from xml.etree import ElementTree as ET

from database_target import connection
from management_db import D, R, sql

SCHEMA = 'di_management'
TOPICS = ('Organization', 'Catalog', 'Access', 'Delivery')
MODELS = [f'SO_AGI_DataInfrastructure_{name}_20261112' for name in TOPICS]
XTF = 'http://www.interlis.ch/xtf/2.4/'
ILI = '{' + XTF + 'INTERLIS}'
ROLES = ('di_writer', 'di_public')
POST_SCRIPT = R / 'sql/public_views.sql'
MAX_XTF_BYTES = 2 * 1024 * 1024


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def tools():
    versions = json.loads((R / 'db/toolchain.json').read_text())
    return R / '.runtime/tools', versions


def ili2pg(action, *options):
    versions_dir, versions = tools()
    jar = versions_dir / 'ili2pg' / f"ili2pg-{versions['ili2pg']['version']}.jar"
    target = connection()
    args = [
        '-jar', str(jar), action, '--dbschema', SCHEMA, '--modeldir', str(R / 'models'),
        '--dbhost', target['PGHOST'], '--dbport', target['PGPORT'],
        '--dbdatabase', target['PGDATABASE'], '--dbusr', target['PGUSER'],
        '--dbpwd', target['PGPASSWORD'], *options,
    ]
    with NamedTemporaryFile(mode='w', dir=D, suffix='.args') as arguments:
        for argument in args:
            arguments.write('"' + argument.replace('\\', '\\\\').replace('"', '\\"')
                            .replace('\n', '\\n').replace('\r', '\\r') + '"\n')
        arguments.flush()
        result = subprocess.run(['java', '@' + arguments.name], capture_output=True, text=True)
    if result.returncode:
        # ili2pg may include connection parameters in diagnostics.
        raise RuntimeError(f'Pinned ili2pg {action} failed (diagnostics withheld)')


def verify():
    if schemas() != {SCHEMA, 'di_api_public'}:
        raise RuntimeError('Unknown, incomplete or legacy management schema')
    if sql("SELECT to_regclass('di_management.t_ili2db_model') IS NOT NULL "
           "AND to_regclass('di_management.t_ili2db_basket') IS NOT NULL;") != 't':
        raise RuntimeError('Management schema lacks ili2pg model or basket tables')
    present = set(sql("SELECT trim(split_part(modelname, '{', 1)) "
                      'FROM di_management.t_ili2db_model;').splitlines())
    if present != set(MODELS):
        raise RuntimeError('Management model generation differs from the expected four models')
    if sql("""
        SELECT to_regclass('di_management.office') IS NOT NULL
           AND to_regclass('di_management.dataset') IS NOT NULL
           AND to_regclass('di_management.principal') IS NOT NULL
           AND to_regclass('di_management.submission') IS NOT NULL
           AND to_regclass('di_api_public.datasets') IS NOT NULL;
    """) != 't':
        raise RuntimeError('Management schema lacks required tables or public views')
    print('Management models and schemas verified read-only')


def schemas():
    return set(sql("""
        SELECT nspname FROM pg_namespace
        WHERE nspname NOT IN ('pg_catalog','information_schema','public')
          AND nspname NOT LIKE 'pg_toast%' AND nspname NOT LIKE 'pg_temp%';
    """).splitlines())


def setup():
    if schemas():
        verify()
        return
    if sql("""
        SELECT EXISTS (SELECT 1 FROM pg_class WHERE relnamespace='public'::regnamespace)
            OR EXISTS (SELECT 1 FROM pg_proc WHERE pronamespace='public'::regnamespace)
            OR EXISTS (SELECT 1 FROM pg_type WHERE typnamespace='public'::regnamespace)
            OR EXISTS (SELECT 1 FROM pg_extension WHERE extnamespace='public'::regnamespace);
    """) != 'f':
        raise RuntimeError('Refusing setup on a nonempty management database')
    commands = []
    for role in ROLES:
        password = os.environ.get(role.upper() + '_PASSWORD')
        if not password:
            raise RuntimeError(f'Missing credential for {role}')
        commands.append(f'CREATE ROLE {role} LOGIN PASSWORD {literal(password)} '
                        'NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;')
    commands.append('REVOKE ALL ON DATABASE dateninfrastruktur_management FROM PUBLIC;')
    commands.append('GRANT CONNECT ON DATABASE dateninfrastruktur_management TO di_writer, di_public;')
    sql('BEGIN;' + ''.join(commands) + 'COMMIT;')
    ili2pg('--schemaimport', '--models', ';'.join(MODELS), '--createBasketCol',
           '--createTidCol', '--createFk', '--createFkIdx', '--createUnique',
           '--createNumChecks', '--setupPgExt', '--postScript', str(POST_SCRIPT))
    verify()


def runtime_path(raw, *, existing):
    root = Path('/runtime').resolve()
    path = Path(raw)
    if not path.is_absolute() or path != path.resolve():
        raise RuntimeError('Use an absolute regular file in the selected runtime')
    if not path.resolve().is_relative_to(root):
        raise RuntimeError('Transfer path must remain within the selected runtime')
    if existing:
        if not path.is_file() or not 0 < path.stat().st_size <= MAX_XTF_BYTES:
            raise RuntimeError('Use a nonempty, bounded regular XTF file')
    elif path.exists() or not path.parent.is_dir():
        raise RuntimeError('Export requires a new file in an existing runtime directory')
    return path


def transfer_ids(path):
    data = path.read_bytes()
    if b'\x00' in data or b'<!DOCTYPE' in data.upper():
        raise RuntimeError('Bootstrap XTF cannot contain a DOCTYPE')
    document = ET.fromstring(data.decode('utf-8'))
    section = document.find(ILI + 'datasection')
    if document.tag != ILI + 'transfer' or section is None:
        raise RuntimeError('Expected an INTERLIS 2.4 transfer')
    expected = {f'{{{XTF}{model}}}{topic}': f'{model}.{topic}'
                for model, topic in zip(MODELS, TOPICS)}
    baskets = {}
    objects = {}
    for basket in section:
        bid = basket.get(ILI + 'bid')
        if (basket.tag not in expected or not bid or str(UUID(bid)) != bid
                or bid in baskets or expected[basket.tag] in baskets.values()):
            raise RuntimeError('Unexpected, invalid or duplicate bootstrap basket')
        baskets[bid] = expected[basket.tag]
        for obj in basket:
            tid = obj.get(ILI + 'tid')
            if obj.tag == f'{{{XTF}{MODELS[1]}}}DatasetContact' and tid is None:
                continue
            if not tid or tid in objects:
                raise RuntimeError('Missing, empty or duplicate object TID')
            if expected[basket.tag] != f'{MODELS[0]}.Organization' and str(UUID(tid)) != tid:
                raise RuntimeError('Expected a canonical UUID object TID')
            objects[tid] = (bid, obj.tag)
    if set(baskets.values()) != set(expected.values()):
        raise RuntimeError('Bootstrap requires exactly one basket per management topic')
    for element in section.iter():
        tid = element.get(ILI + 'ref')
        if tid is None:
            continue
        ref_bid = element.get(ILI + 'bid')
        if tid not in objects or (ref_bid is not None and ref_bid != objects[tid][0]):
            raise RuntimeError('Reference BID does not match its target object in the bootstrap')
    return baskets, objects


def bootstrap(path):
    verify()
    if sql('SELECT count(*) FROM di_management.t_ili2db_basket;') != '0':
        raise RuntimeError('Bootstrap requires empty management baskets')
    with NamedTemporaryFile(dir=D, suffix='.xtf') as snapshot:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, 'rb') as source:
            mode = os.fstat(source.fileno()).st_mode
            if not stat.S_ISREG(mode) or mode & 0o077:
                raise RuntimeError('Import requires a private regular XTF file')
            contents = source.read(MAX_XTF_BYTES + 1)
        if not 0 < len(contents) <= MAX_XTF_BYTES:
            raise RuntimeError('Bootstrap XTF exceeds the permitted size')
        snapshot.write(contents)
        snapshot.flush()
        try:
            transfer_ids(Path(snapshot.name))
        except (ValueError, ET.ParseError) as error:
            raise RuntimeError('Invalid XML or management UUID in bootstrap XTF') from error
        directory, versions = tools()
        jar = directory / 'ilivalidator' / f"ilivalidator-{versions['ilivalidator']['version']}.jar"
        result = subprocess.run(['java', '-jar', str(jar), '--modeldir', str(R / 'models'),
                                 '--forceTypeValidation', snapshot.name], capture_output=True)
        if result.returncode:
            raise RuntimeError('Bootstrap XTF failed the pinned ilivalidator')
        ili2pg('--import', '--importBid', '--importTid', snapshot.name)
    print('Management bootstrap imported')


def export(path):
    verify()
    with TemporaryDirectory(dir=path.parent, prefix='.management-export-') as directory:
        temporary = Path(directory) / 'export.xtf'
        ili2pg('--export', '--models', ';'.join(MODELS), '--exportTid', str(temporary))
        temporary.chmod(0o600)
        os.link(temporary, path)
    print('Management transfer exported')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('setup', 'verify', 'import', 'export'))
    parser.add_argument('file', nargs='?')
    args = parser.parse_args()
    connection()
    if bool(args.file) != (args.action in ('import', 'export')):
        parser.error('import and export require exactly one XTF path')
    with (D / 'management-setup.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if args.action == 'setup':
            setup()
        elif args.action == 'verify':
            verify()
        elif args.action == 'import':
            bootstrap(runtime_path(args.file, existing=True))
        else:
            export(runtime_path(args.file, existing=False))


if __name__ == '__main__':
    try:
        main()
    except RuntimeError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
    except OSError:
        print('Management setup or transfer could not access its private file', file=sys.stderr)
        sys.exit(1)
