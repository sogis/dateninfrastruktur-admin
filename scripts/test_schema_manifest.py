"""Regression checks for the package's current schema inputs."""
import json
from pathlib import Path
import shutil
import tempfile
import unittest

from persistence_contract import MANIFEST, PHYSICAL, PROPERTIES, R, calculate, load_manifest


class SchemaManifestTests(unittest.TestCase):
    def test_canonical_properties(self):
        body, digest = calculate()
        self.assertEqual((R/PROPERTIES).read_text(), body)
        self.assertEqual(body, '\n'.join(sorted(body.splitlines()))+'\n')
        self.assertEqual(len(digest),64)
        self.assertNotIn('migration.',body)

    def test_manifest_rejects_missing_duplicate_and_escaping_paths(self):
        manifest=load_manifest()
        names=set(manifest['models']+list(manifest['artifacts'].values())+[MANIFEST,PHYSICAL])
        names.update(s for s in manifest['steps'] if '/' in s)
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp).resolve()
            for name in names:
                target=root/name
                target.parent.mkdir(parents=True,exist_ok=True)
                shutil.copyfile(R/name,target)
            m=root/MANIFEST
            self.assertEqual(calculate(root),calculate())
            for path in ('../secret',str(R/PHYSICAL),manifest['models'][0],PROPERTIES,'db/schema/missing.sql'):
                with self.subTest(path=path):
                    mutated={**manifest,'artifacts':{**manifest['artifacts'],'bad.sha256':path}}
                    m.write_text(json.dumps(mutated))
                    with self.assertRaises(ValueError):
                        load_manifest(root)
            m.write_text(json.dumps({**manifest,'steps':manifest['steps']+[manifest['steps'][0]]}))
            with self.assertRaises(ValueError):
                load_manifest(root)
            m.write_text(json.dumps(manifest))
            target=root/PHYSICAL
            target.unlink()
            target.symlink_to(R/PHYSICAL)
            with self.assertRaises(ValueError):
                calculate(root)


if __name__=='__main__':
    suite=unittest.TestLoader().loadTestsFromTestCase(SchemaManifestTests)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    if not result.wasSuccessful() or result.testsRun!=2 or result.skipped:
        raise SystemExit(1)
    print('ASSERTIONS=2 SKIP=0')
