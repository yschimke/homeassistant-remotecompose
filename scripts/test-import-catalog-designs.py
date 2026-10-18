import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("backup", Path(__file__).with_name("import-catalog-designs.py"))
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class CatalogBackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name)
        self.blob = b"a reproducible derived artifact"
        self.sha = hashlib.sha256(self.blob).hexdigest()
        self.revision = dict(id="new-style", revision=1, compilerVersion="3.144.0", source={"catalogPin": {"systemId": "ha-cards"}}, entry={"sha256": self.sha, "bytes": len(self.blob)})
        self.index = dict(schema=1, serial=1, revisions=[self.revision])

    def download(self, url, limit):
        return json.dumps(self.index).encode() if url.endswith("publications") else self.blob

    def run_backup(self):
        return backup.snapshot("https://ha.example", self.output, self.download)

    def test_idempotent_and_keeps_source_pin(self):
        self.assertEqual(1, self.run_backup())
        files = {p: p.stat().st_mtime_ns for p in self.output.rglob("*") if p.is_file()}
        self.run_backup()
        self.assertEqual(files, {p: p.stat().st_mtime_ns for p in files})
        self.assertEqual(self.revision["source"], json.loads((self.output / "sources/new-style/1.uid").read_bytes()))

    def test_corrupt_blob_leaves_backup_unchanged(self):
        self.blob = b"corrupt"
        with self.assertRaisesRegex(ValueError, "mismatch"):
            self.run_backup()
        self.assertEqual([], list(self.output.rglob("*")))

    def test_rollback_and_history_loss_refused(self):
        self.run_backup()
        self.index["serial"] = 0
        with self.assertRaisesRegex(ValueError, "rolled back"):
            self.run_backup()
        self.index.update(serial=2, revisions=[])
        with self.assertRaisesRegex(ValueError, "history"):
            self.run_backup()

    def test_path_traversal_and_external_assets_refused(self):
        self.revision["id"] = "../../outside"
        with self.assertRaisesRegex(ValueError, "identity"):
            self.run_backup()
        self.revision["id"] = "new-style"
        self.revision["source"]["assets"] = {"external": {"uri": "https://elsewhere/asset.png"}}
        with self.assertRaisesRegex(ValueError, "unbundled"):
            self.run_backup()

    def test_rewrite_existing_revision_refused(self):
        self.run_backup()
        self.revision["source"]["title"] = "rewritten"
        with self.assertRaisesRegex(ValueError, "history"):
            self.run_backup()


if __name__ == "__main__":
    unittest.main()
