"""Exercise the release workflow's checksum command after assets are downloaded flat."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class AppleChecksumTest(unittest.TestCase):
    def test_downloaded_assets_verify_without_staging_directory(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/release-build.yml').read_text()
        commands = [line.strip() for line in workflow.splitlines()
                    if 'shasum -a 256' in line and 'SHA256SUMS-apple.txt' in line]
        self.assertEqual(len(commands), 1)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staging = root / '_release-ios'
            staging.mkdir()
            (staging / 'TerrazzoKit-1.0.xcframework.zip').write_bytes(b'fixture archive')
            subprocess.run(['bash', '-ec', commands[0]], cwd=root, check=True)
            downloaded = root / 'downloaded-assets'
            staging.rename(downloaded)
            result = subprocess.run(['shasum', '-a', '256', '-c', 'SHA256SUMS-apple.txt'],
                                    cwd=downloaded, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('TerrazzoKit-1.0.xcframework.zip: OK', result.stdout)


if __name__ == '__main__':
    unittest.main()
