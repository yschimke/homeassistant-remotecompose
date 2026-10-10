"""Packaging must reject corrupt or escaping inputs even with Python assertions disabled."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from PIL import Image


class PackageTest(unittest.TestCase):
    def fixture(self, root):
        script = Path(__file__).resolve().parent.parent / 'package.py'
        shutil.copyfile(script, root / 'package.py')
        out = root / 'build/pilot'
        (out / 'previews').mkdir(parents=True)
        (out / 'references').mkdir()
        Image.new('RGB', (2, 2)).save(out / 'previews/example.png')
        shutil.copyfile(out / 'previews/example.png', out / 'references/example.png')
        (out / 'references/example.uid').write_text('{}')
        plan = {'captures': [{'previewId': 'example', 'widthDp': 2, 'heightDp': 2, 'density': 1}]}
        ref = {'previewId': 'example', 'raster': {'path': 'references/example.png', 'sha256': hashlib.sha256((out / 'references/example.png').read_bytes()).hexdigest()}, 'artifact': {'path': 'references/example.uid'}, 'source': {'attributes': {'documentSha256': hashlib.sha256(b'{}').hexdigest()}}}
        return plan, {'references': [ref]}

    def run_package(self, root, plan, manifest):
        (root / 'references.json').write_text(json.dumps(plan))
        (root / 'build/pilot/references/index.json').write_text(json.dumps(manifest))
        return subprocess.run([sys.executable, '-O', str(root / 'package.py')], capture_output=True, text=True)

    def test_optimized_python_packages_valid_inputs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            plan, manifest = self.fixture(root)
            result = self.run_package(root, plan, manifest)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(len(list((root / 'build').glob('*.zip'))), 1)

    def test_optimized_python_rejects_invalid_inputs_before_creating_archive(self):
        for corruption in ['raster-hash', 'uid-hash', 'dimensions', 'duplicate', 'raster-escape', 'uid-escape', 'size-cap']:
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                plan, manifest = self.fixture(root)
                ref = manifest['references'][0]
                if corruption == 'raster-hash': ref['raster']['sha256'] = '0' * 64
                if corruption == 'uid-hash': ref['source']['attributes']['documentSha256'] = '0' * 64
                if corruption == 'dimensions': plan['captures'][0]['widthDp'] = 3
                if corruption == 'duplicate': manifest['references'].append(ref.copy())
                if corruption == 'raster-escape': ref['raster']['path'] = '../outside.png'
                if corruption == 'uid-escape': ref['artifact']['path'] = '../outside.uid'
                if corruption == 'size-cap': plan['captures'][0]['widthDp'] = 1801
                result = self.run_package(root, plan, manifest)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertEqual(list((root / 'build').glob('*.zip')), [])


if __name__ == '__main__':
    unittest.main()
