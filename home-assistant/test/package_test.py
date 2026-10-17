"""Packaging must include both renderers, no credentials or generated Python files."""
import importlib.util
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

    def test_missing_build_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(FileNotFoundError):
                module.package(root, root / 'absent', root / 'output')


if __name__ == '__main__':
    unittest.main()
