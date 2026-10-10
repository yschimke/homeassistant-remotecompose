import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image

spec = importlib.util.spec_from_file_location('evidence', Path(__file__).with_name('evidence.py'))
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


class EvidenceTest(unittest.TestCase):
    def test_audit_stages_only_expected_bounded_images(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'previews').mkdir()
            captures = []
            for i in range(8):
                Image.new('RGB', (2, 2)).save(root / f'previews/capture-{i}.png')
                captures.append({'previewId': f'capture-{i}', 'widthDp': 1, 'heightDp': 1, 'density': 2})
            plan = root / 'plan.json'
            plan.write_text(json.dumps({'captures': captures}))
            (root / 'untrusted.sh').write_text('do not execute')
            evidence.run(plan, root, root / 'staged')
            self.assertEqual(len(list((root / 'staged').iterdir())), 9)
            (root / 'previews/capture-0.png').unlink()
            with self.assertRaises(FileNotFoundError):
                evidence.run(plan, root, root / 'missing')

    def test_rejects_wrong_dimensions_and_invalid_png(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            Image.new('RGB', (2, 2)).save(root / 'image.png')
            with self.assertRaises(ValueError):
                evidence.png(root, 'image.png', (4, 4))
            (root / 'image.png').write_text('not a png')
            with self.assertRaises(Exception):
                evidence.png(root, 'image.png', (2, 2))

    def test_rejects_paths_and_symlinks_outside_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'evidence'
            root.mkdir()
            outside = Path(tmp) / 'secret'
            outside.write_text('must not be staged')
            (root / 'link').symlink_to(outside)
            for path in ['../secret', 'link', str(outside)]:
                with self.assertRaises(ValueError):
                    evidence.read(root, path)


if __name__ == '__main__':
    unittest.main()
