"""Run with: python3 -m unittest discover -s scripts/appfunctions -p 'test_*.py'."""

import contextlib
import io
import json
import shlex
import tempfile
import unittest
from unittest.mock import patch

import testing


class TestingSetupTest(unittest.TestCase):
    def test_install_refuses_an_apk_without_successful_build_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            agent = testing.Path(directory)
            apk = testing.agent_apk(agent)
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"stale unpatched APK")
            with patch.object(testing, "prepare"), patch.object(testing, "device", return_value=["adb"]):
                with patch.object(testing.sys, "argv", ["testing.py", "install-agent", "--serial",
                                                       "test-device", "--agent-dir", directory]):
                    with self.assertRaisesRegex(RuntimeError, "Build the patched"):
                        testing.main()

    def test_install_refuses_apk_changed_after_build(self):
        with tempfile.TemporaryDirectory() as directory:
            agent = testing.Path(directory)
            apk = testing.agent_apk(agent)
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"tested APK")
            (agent / ".terrazzo-build.json").write_text(json.dumps(testing.build_receipt(agent)))
            apk.write_bytes(b"different APK")
            with patch.object(testing, "prepare"), patch.object(testing, "device", return_value=["adb"]):
                with patch.object(testing.sys, "argv", ["testing.py", "install-agent", "--serial",
                                                       "test-device", "--agent-dir", directory]):
                    with self.assertRaisesRegex(RuntimeError, "changed; rebuild"):
                        testing.main()

    def test_device_is_never_selected_implicitly(self):
        with patch.object(testing, "adb_path") as adb:
            with self.assertRaisesRegex(RuntimeError, "--serial explicitly"):
                testing.device(None)
            adb.assert_not_called()

    def test_empty_sdk_stub_is_rejected(self):
        with patch.object(testing.shutil, "which", return_value="/stub/adb"):
            with patch.object(testing, "run", return_value=""):
                with self.assertRaisesRegex(RuntimeError, "not a stub"):
                    testing.adb_path()

    def test_api_35_device_is_rejected(self):
        with patch.object(testing, "adb_path", return_value="adb"):
            with patch.object(testing, "run", side_effect=["device", "35"]):
                with self.assertRaisesRegex(RuntimeError, "API 36"):
                    testing.device("test-device")

    def test_device_shell_preserves_json_and_function_identifier(self):
        args = ("cmd", "app_function", "execute-app-function", "--function",
                testing.FUNCTION_PREFIX + "findEntities", "--parameters",
                json.dumps({"query": "garage light; echo unsafe", "domain": "light"}))
        with patch.object(testing, "run", return_value="ok") as run:
            testing.shell(["adb", "-s", "test-device"], *args)
        actual = run.call_args.args[0]
        self.assertEqual(actual[:4], ["adb", "-s", "test-device", "shell"])
        self.assertEqual(shlex.split(actual[4]), list(args))

    def test_discovery_checks_ids_instead_of_counting_runtime_duplicates(self):
        ids = sorted(testing.FUNCTION_PREFIX + name for name in testing.FUNCTIONS)
        metadata = {testing.PACKAGE: [
            {"static": {"functionId": [fid]}, "runtime": {"functionId": [fid]}}
            for fid in ids
        ]}
        with patch.object(testing, "shell", side_effect=[json.dumps(metadata)] + ["false"] * 5):
            with contextlib.redirect_stdout(io.StringIO()) as output:
                testing.discover(["adb"])
        self.assertEqual(output.getvalue().count("enabled=false"), 5)
        metadata[testing.PACKAGE].pop()
        with patch.object(testing, "shell", return_value=json.dumps(metadata)):
            with self.assertRaisesRegex(RuntimeError, "Expected five"):
                testing.discover(["adb"])


if __name__ == "__main__":
    unittest.main()
