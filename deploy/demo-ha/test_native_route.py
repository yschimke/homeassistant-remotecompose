"""Exercise route persistence and failure handling without a Docker host."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "native_route", Path(__file__).with_name("enable-native-route.py"))
route = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(route)


class NativeRouteTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="native route ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stack = self.root / "preview stack"
        self.stack.mkdir()
        self.env = self.stack / ".env"
        self.original = "# Existing deployment\nCOMPOSE_FILE=base.yml:deploy.yml:demo-ha.yml\nSECRET=private\n"
        self.env.write_text(self.original)
        self.env.chmod(0o600)
        self.calls = []
        self.files = "base.yml:deploy.yml:demo-ha.yml"
        self.fail = None

    def docker(self, command, **kwargs):
        self.calls.append((command, kwargs))
        self.assertEqual(kwargs["cwd"], self.stack)
        args = command[4:]
        if args == ["config", "--environment"]:
            return subprocess.CompletedProcess(command, 0,
                f"COMPOSE_FILE={self.files}\nSECRET=private\n")
        if self.fail and self.fail in args:
            raise subprocess.CalledProcessError(42, command)
        return subprocess.CompletedProcess(command, 0)

    def run_route(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(
                route.subprocess, "run", side_effect=self.docker), contextlib.redirect_stdout(io.StringIO()) as output:
            route.enable_route(self.stack)
        self.assertNotIn("SECRET", output.getvalue())
        return output.getvalue()

    def test_preserves_existing_services_settings_and_private_backup(self):
        self.run_route()
        content = self.env.read_text()
        self.assertIn("SECRET=private", content)
        self.assertIn("base.yml:deploy.yml:demo-ha.yml:", content)
        self.assertIn(str(Path(route.__file__).parent / "caddy.native.yaml"), content)
        backups = list(self.stack.glob(".env.backup-*"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_text(), self.original)
        self.assertEqual(backups[0].stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.calls[-1][0][4:],
                         ["up", "-d", "--no-deps", "--no-build", "--pull", "never", "caddy"])
        self.assertEqual(len(self.calls), 4)
        self.assertEqual(list(self.stack.glob(".native-route-*")), [])

    def test_validation_failure_leaves_environment_and_services_untouched(self):
        self.fail = "validate"
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_route()
        self.assertEqual(self.env.read_text(), self.original)
        self.assertFalse(list(self.stack.glob(".env.backup-*")))
        self.assertFalse(any("up" in cmd for cmd, _ in self.calls))
        self.assertEqual(list(self.stack.glob(".native-route-*")), [])

    def test_rejects_unknown_compose_list(self):
        self.files = ""
        with self.assertRaisesRegex(ValueError, "complete existing COMPOSE_FILE"):
            self.run_route()
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.env.read_text(), self.original)

    def test_rerun_does_not_duplicate_overlay(self):
        self.files += ":" + str(Path(route.__file__).parent / "caddy.native.yaml")
        self.run_route()
        self.assertEqual(self.env.read_text().count("caddy.native.yaml"), 1)

    def test_failed_recreation_retains_saved_config_and_backup(self):
        self.fail = "up"
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_route()
        self.assertIn("caddy.native.yaml", self.env.read_text())
        self.assertEqual(len(list(self.stack.glob(".env.backup-*"))), 1)


if __name__ == "__main__":
    unittest.main()
