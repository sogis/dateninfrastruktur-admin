#!/usr/bin/env python3
"""Compile the four management models and validate their synthetic transfers."""
from argparse import ArgumentParser
from copy import deepcopy
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from xml.etree import ElementTree as ET

from interlis_tools import ensure

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / 'models'
TOPICS = ('Organization', 'Catalog', 'Access', 'Delivery')
NAMES = {topic: f'SO_AGI_DataInfrastructure_{topic}_20261112' for topic in TOPICS}
XTF = 'http://www.interlis.ch/xtf/2.4/'
ILI = '{' + XTF + 'INTERLIS}'
ORG, CAT, ACC, DELIVERY = ('{' + XTF + name + '}' for name in NAMES.values())
BOOTSTRAP = ROOT / 'tests/fixtures/management-bootstrap.xtf'


def run(command, expected_diagnosis=None):
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT)
    if expected_diagnosis is None:
        if result.returncode or re.search(r'^Error:', result.stdout, re.M):
            raise RuntimeError(result.stdout)
    elif result.returncode != 1 or not re.search(r'^Error:.*' + expected_diagnosis,
                                                  result.stdout, re.M | re.I):
        raise RuntimeError(f'Expected semantic rejection: {expected_diagnosis}\n{result.stdout}')
    return result.stdout


def required(parent, tag):
    child = parent.find(tag)
    if child is None:
        raise ValueError(f'Missing {tag}')
    return child


def negative_transfers(document):
    office = document.find('.//' + ORG + 'Office')
    first, second = document.findall('.//' + ORG + 'Office')
    authority, next_authority, *_ = document.findall('.//' + ACC + 'AuthorityAssignment')
    membership = document.find('.//' + ACC + 'GroupMembership')
    pending, accepted = document.findall('.//' + DELIVERY + 'Submission')
    issue, other_issue = document.findall('.//' + CAT + 'DatasetIssue')
    first_unique, second_unique = document.findall('.//' + CAT + 'UniqueField')
    distribution = document.find('.//' + CAT + 'Distribution')
    grant = document.find('.//' + ACC + 'AccessGrant')
    attribute = document.find('.//' + CAT + 'AttributeDefinition')
    schema = document.find('.//' + CAT + 'DataSchema')
    return [
        ('office-oid', r'Office:.*is not a valid OID', lambda: office.set(ILI + 'tid', 'x' * 1025)),
        ('office-unique', r'Unique constraint.*Office',
         lambda: setattr(required(second, ORG + 'identifier'), 'text', required(first, ORG + 'identifier').text)),
        ('authority-scope', r'AuthorityScopeExclusive',
         lambda: authority.append(deepcopy(required(next_authority, ACC + 'dataset')))),
        ('authority-revocation', r'AuthorityRevocationComplete',
         lambda: setattr(ET.SubElement(authority, ACC + 'revokedAt'), 'text', '2026-11-12T12:00:00.000')),
        ('nested-group', r'GroupCannotBeMember',
         lambda: required(membership, ACC + 'member').set(ILI + 'ref', required(membership, ACC + 'group').get(ILI + 'ref'))),
        ('processing-without-start', r'StartedStatus',
         lambda: setattr(required(pending, DELIVERY + 'status'), 'text', 'processing')),
        ('submission-issue', r'IssueBelongsToDataset',
         lambda: required(pending, DELIVERY + 'issue').set(ILI + 'ref', other_issue.get(ILI + 'tid'))),
        ('accepted-without-version', r'AcceptedVersionPresent',
         lambda: accepted.remove(required(accepted, DELIVERY + 'acceptedVersion'))),
        ('unique-field-position', r'Unique constraint.*UniqueField',
         lambda: setattr(required(second_unique, CAT + 'ordinal'), 'text', required(first_unique, CAT + 'ordinal').text)),
        ('standalone-issue-key', r'StandaloneIssueKey',
         lambda: setattr(required(issue, CAT + 'issueKey'), 'text', 'unexpected')),
        ('available-distribution-without-hash', r'AvailableFileMeasured',
         lambda: distribution.remove(required(distribution, CAT + 'sha256'))),
        ('grant-incomplete-revocation', r'Mandatory Constraint.*AccessGrant',
         lambda: setattr(ET.SubElement(grant, ACC + 'revokedAt'), 'text', '2026-11-12T12:00:00.000')),
        ('text-attribute-without-length', r'TextLengthRequired',
         lambda: attribute.remove(required(attribute, CAT + 'maxLength'))),
        ('interlis-schema-with-minimum', r'GeneratedMinimumOnly',
         lambda: (setattr(required(schema, CAT + 'kind'), 'text', 'interlis'),
                  setattr(ET.SubElement(schema, CAT + 'minimumObjectCount'), 'text', '1'))),
    ]


def vocabulary(source, domain):
    match = re.search(r'^    ' + domain + r' = \((.*?)^    \);', source, re.S | re.M)
    if match is None:
        raise AssertionError(f'Missing vocabulary: {domain}')
    return match.group(1)


def test_management(compiler, validator, directory):
    files = [MODELS / (name + '.ili') for name in NAMES.values()]
    assert set(MODELS.glob('*.ili')) == set(files)
    print(run([*compiler, '--no-auto', *map(str, files)]).strip())
    command = [*validator, '--modeldir', str(MODELS), '--forceTypeValidation',
               '--mandatoryBaskets', ';'.join(f'{name}.{topic}' for topic, name in NAMES.items())]
    print(run([*command, str(BOOTSTRAP)]).splitlines()[-2])
    cases = negative_transfers(ET.parse(BOOTSTRAP))
    for index, (name, expected, _) in enumerate(cases):
        modified = ET.parse(BOOTSTRAP)
        negative_transfers(modified)[index][2]()
        transfer = directory / (name + '.xtf')
        modified.write(transfer, encoding='utf-8', xml_declaration=True)
        log = run([*command, str(transfer)], expected)
        print(name + ': ' + next(line for line in log.splitlines()
                                 if re.match(r'Error:.*' + expected, line, re.I))[:240])
    baseline = (MODELS / 'vendor/SO_AGI_DataCatalog_Base_20260529.ili').read_text()
    catalog = files[1].read_text()
    for domain in ('Origin', 'Theme', 'MaintenanceFrequencyCode'):
        assert vocabulary(baseline, domain) == vocabulary(catalog, domain), domain
    for file in files:
        assert not re.search(r'!!@\s+di\.audit|\b(rowVersion|statusVersion|commandReceipt|executionSlot|'
                             r'checkpoint|automaticPublication|definitionKey|definitionSha256|'
                             r'modelSetSha256|modelSha256|configurationDigest|allowEmptyReplacement)\b',
                             file.read_text(), re.I), file
    print(f'ASSERTIONS={2 + len(cases) + 3 + len(files)} SKIP=0')


def fixture_csv_model(document):
    """Read the test schema and ordered compound key from the management XTF."""
    schema = document.find('.//' + CAT + 'DataSchema')
    schema_tid = schema.get(ILI + 'tid')
    assert required(schema, CAT + 'kind').text == 'simple_csv'
    name = required(schema, CAT + 'modelName').text
    attributes = sorted(
        (entry for entry in document.iter(CAT + 'AttributeDefinition')
         if required(entry, CAT + 'schema').get(ILI + 'ref') == schema_tid),
        key=lambda entry: int(required(entry, CAT + 'ordinal').text),
    )
    names = {entry.get(ILI + 'tid'): required(entry, CAT + 'name').text for entry in attributes}
    declarations = []
    for entry in attributes:
        # This fixture exercises two TEXT attributes. Type mapping for the editor
        # belongs to the application generator.
        assert required(entry, CAT + 'dataType').text == 'text'
        length = int(required(entry, CAT + 'maxLength').text)
        mandatory = 'MANDATORY ' if required(entry, CAT + 'mandatory').text == 'true' else ''
        declarations.append(f"      {names[entry.get(ILI + 'tid')]} : {mandatory}TEXT*{length};")
    rules = []
    for rule in document.iter(CAT + 'UniqueDefinition'):
        if required(rule, CAT + 'schema').get(ILI + 'ref') != schema_tid:
            continue
        fields = sorted(
            (entry for entry in document.iter(CAT + 'UniqueField')
             if required(entry, CAT + 'uniqueDefinition').get(ILI + 'ref') == rule.get(ILI + 'tid')),
            key=lambda entry: int(required(entry, CAT + 'ordinal').text),
        )
        columns = [names[required(field, CAT + 'attribute').get(ILI + 'ref')] for field in fields]
        rules.append('      UNIQUE ' + ', '.join(columns) + ';')
    minimum = schema.findtext(CAT + 'minimumObjectCount')
    if minimum is not None:
        rules.append(f'      SET CONSTRAINT (BASKET) Minimum: INTERLIS.objectCount(ALL) >= {int(minimum)};')
    return name, list(names.values()), '\n'.join([
        'INTERLIS 2.4;',
        f'MODEL {name} (en) AT "https://test.example" VERSION "2026-11-12" =',
        '  TOPIC Snapshot =',
        '    BASKET OID AS INTERLIS.UUIDOID;',
        '    OID AS INTERLIS.UUIDOID;',
        '    CLASS Row =',
        *declarations,
        *rules,
        '    END Row;',
        '  END Snapshot;',
        f'END {name}.',
        '',
    ])


def snapshot(path, name, fields, rows):
    namespace = '{' + XTF + name + '}'
    transfer = ET.Element(ILI + 'transfer')
    header = ET.SubElement(transfer, ILI + 'headersection')
    ET.SubElement(ET.SubElement(header, ILI + 'models'), ILI + 'model').text = name
    ET.SubElement(header, ILI + 'sender').text = 'Synthetic snapshot'
    data = ET.SubElement(transfer, ILI + 'datasection')
    basket = ET.SubElement(data, namespace + 'Snapshot',
                           {ILI + 'bid': '10000000-0000-4000-8000-000000000000'})
    for index, values in enumerate(rows, 1):
        row = ET.SubElement(basket, namespace + 'Row',
                            {ILI + 'tid': f'20000000-0000-4000-8000-{index:012d}'})
        assert len(fields) == len(values)
        for field, value in zip(fields, values):
            ET.SubElement(row, namespace + field).text = value
    ET.ElementTree(transfer).write(path, encoding='utf-8', xml_declaration=True)


def test_csv_model(compiler, validator, directory):
    # A full snapshot is what GRETL's CsvValidator checks after append. Here
    # the pinned validator evaluates the very same INTERLIS rules on XTF rows.
    document = ET.parse(BOOTSTRAP)
    schema = document.find('.//' + CAT + 'DataSchema')
    assertions = 0
    for minimum in (None, 1, 2):
        if minimum is None:
            assert schema.find(CAT + 'minimumObjectCount') is None
        elif minimum == 1:
            ET.SubElement(schema, CAT + 'minimumObjectCount').text = str(minimum)
        else:
            required(schema, CAT + 'minimumObjectCount').text = str(minimum)
        name, fields, source = fixture_csv_model(document)
        folder = directory / ('minimum-' + str(minimum))
        folder.mkdir()
        model = folder / (name + '.ili')
        model.write_text(source)
        print(run([*compiler, '--no-auto', str(model)]).strip())
        assertions += 1
        command = [*validator, '--modeldir', str(folder),
                   '--mandatoryBaskets', name + '.Snapshot']
        for label, rows, diagnosis in (
            ('empty', [], 'Minimum' if minimum else None),
            ('one', [('A', '1')], 'Minimum' if minimum == 2 else None),
            ('same-zone-different-code', [('A', '1'), ('A', '2')], None),
            ('same-key-across-append', [('A', '1'), ('A', '1')], 'Unique constraint'),
        ):
            transfer = folder / (label + '.xtf')
            snapshot(transfer, name, fields, rows)
            run([*command, str(transfer)], diagnosis)
            print(f'minimum={minimum} {label}: ' + ('rejected: ' + diagnosis if diagnosis else 'valid'))
            assertions += 1
    print(f'ASSERTIONS={assertions} SKIP=0')


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('check', choices=['management', 'csv'])
    args = parser.parse_args()
    if not __debug__:
        raise RuntimeError('Assertions must be enabled')
    ensure()
    tools = ROOT / '.runtime/tools'
    compiler = ['java', '-jar', str(tools / 'ili2c/ili2c.jar')]
    validator = ['java', '-jar', str(next((tools / 'ilivalidator').glob('ilivalidator-*.jar')))]
    with TemporaryDirectory(prefix='di-model-check-') as work:
        check = test_management if args.check == 'management' else test_csv_model
        check(compiler, validator, Path(work))


if __name__ == '__main__':
    main()
