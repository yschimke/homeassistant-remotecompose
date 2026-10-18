"""Packaging must include both renderers, no credentials or generated Python files."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('package_ha', Path(__file__).resolve().parents[2] / 'scripts/package-home-assistant.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PackageTest(unittest.TestCase):
    def test_installable_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wasm = root / 'wasm'
            wasm.mkdir()
            for name in ['index.html', 'terrazzo.js', 'app.wasm']:
                (wasm / name).write_text('fixture')
            player = root / 'player.js'
            player.write_text('fixture')
            archive = module.package(wasm, player, root / 'output')
            with zipfile.ZipFile(archive) as bundle:
                names = bundle.namelist()
                for name in ['manifest.json', '__init__.py', 'frontend/wasm/app.wasm',
                             'frontend/ha-card.js', 'frontend/rc-player.js',
                             'frontend/remote-compose-card.js', 'frontend/terrazzo-panel.js']:
                    self.assertIn('custom_components/terrazzo/' + name, names)
                self.assertFalse(any('__pycache__' in name for name in names))
            # HACS unpacks directly into the integration directory. Installing
            # either archive must produce byte-for-byte identical runnable files.
            with zipfile.ZipFile(root / 'output/terrazzo.zip') as hacs, zipfile.ZipFile(archive) as manual:
                self.assertIn('manifest.json', hacs.namelist())
                self.assertFalse(any(name.startswith('custom_components/') for name in hacs.namelist()))
                self.assertEqual(set(manual.namelist()),
                                 {'custom_components/terrazzo/' + name for name in hacs.namelist()})
                for name in hacs.namelist():
                    self.assertEqual(hacs.read(name), manual.read('custom_components/terrazzo/' + name))
                install = root / 'config/custom_components/terrazzo'
                hacs.extractall(install)
                self.assertEqual((install / 'frontend/wasm/app.wasm').read_text(), 'fixture')
            for line in (root / 'output/SHA256SUMS-home-assistant.txt').read_text().splitlines():
                digest, name = line.split()
                self.assertEqual(digest, hashlib.sha256((root / 'output' / name).read_bytes()).hexdigest())
            stale = root / 'output/custom_components/terrazzo/frontend/wasm/removed.js'
            stale.write_text('obsolete executable')
            module.package(wasm, player, root / 'output')
            self.assertFalse(stale.exists())
            with zipfile.ZipFile(archive) as bundle:
                self.assertFalse(any(name.endswith('/removed.js') for name in bundle.namelist()))

    def test_release_version_must_match_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wasm = root / 'wasm'
            wasm.mkdir()
            for name in ['index.html', 'terrazzo.js']:
                (wasm / name).write_text('fixture')
            (root / 'player.js').write_text('fixture')
            source = module.ROOT / 'custom_components/terrazzo/manifest.json'
            original = source.read_bytes()
            version = json.loads(original)['version']
            archive = module.package(wasm, root / 'player.js', root / 'output', 'v' + version)
            with zipfile.ZipFile(archive) as bundle:
                manifest = json.loads(bundle.read('custom_components/terrazzo/manifest.json'))
                self.assertEqual(manifest['version'], version)
            self.assertEqual(source.read_bytes(), original)
            for invalid in ['v999.0.0', 'main', '', 'v1.2.3-rc1']:
                with self.subTest(version=invalid), self.assertRaises(ValueError):
                    module.package(wasm, root / 'player.js', root / 'unused', invalid)
                self.assertFalse((root / 'unused').exists())

    def test_hacs_metadata_selects_built_release_only(self):
        metadata = json.loads((module.ROOT / 'hacs.json').read_text())
        self.assertTrue(metadata['zip_release'])
        self.assertTrue(metadata['hide_default_branch'])
        self.assertEqual(metadata['filename'], 'terrazzo.zip')
        # __init__.py registers its frontend with StaticPathConfig / async_register_static_paths,
        # which arrived in Home Assistant 2024.7; HACS must not offer it to older cores.
        init = (module.ROOT / 'custom_components/terrazzo/__init__.py').read_text()
        if 'async_register_static_paths' in init:
            floor = tuple(int(part) for part in metadata['homeassistant'].split('.')[:2])
            self.assertGreaterEqual(floor, (2024, 7))
        config = json.loads((module.ROOT / '.github/release-please-config.json').read_text())
        self.assertIn({'type': 'json', 'path': 'custom_components/terrazzo/manifest.json',
                       'jsonpath': '$.version'}, config['packages']['.']['extra-files'])

    def test_missing_build_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(FileNotFoundError):
                module.package(root, root / 'absent', root / 'output')


if __name__ == '__main__':
    unittest.main()
