"""The integration installer includes local executable assets, and refuses partial builds."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('package_ha', ROOT / 'scripts/package-ha-integration.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PackageTest(unittest.TestCase):
    def test_complete_distribution(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wasm, embed = root / 'wasm', root / 'embed'
            wasm.mkdir()
            embed.mkdir()
            for name in ('index.html', 'terrazzo.js', 'terrazzo.wasm'):
                (wasm / name).write_text('fixture')
            for name in ('ha-card.js', 'rc-player.js'):
                (embed / name).write_text('fixture')
            output = root / 'integration.zip'
            module.package(wasm, embed, output)
            with zipfile.ZipFile(output) as archive:
                names = archive.namelist()
                for name in ('manifest.json', '__init__.py', 'frontend/terrazzo.js',
                             'frontend/wasm/terrazzo.wasm', 'frontend/embed/rc-player.js'):
                    self.assertIn(f'custom_components/terrazzo/{name}', names)
                self.assertFalse(any('__pycache__' in name for name in names))

    def test_missing_build_fails_before_creating_installer(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(FileNotFoundError):
                module.package(root / 'missing', root / 'missing', root / 'integration.zip')
            self.assertFalse((root / 'integration.zip').exists())
