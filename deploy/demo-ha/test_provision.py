"""Operator entry-point tests; real Docker/HA coverage runs in demo-ha.yml."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('provision.sh').resolve()


class ProvisionTests(unittest.TestCase):
    def run_script(self, *args, fail_on='', script=SCRIPT):
        with tempfile.TemporaryDirectory(prefix='demo host ') as directory:
            root = Path(directory)
            docker = root / 'docker'
            docker.write_text(
                '#!/usr/bin/env python3\n'
                'import json, os, sys\n'
                'with open(os.environ["DOCKER_CALLS"], "a") as f:\n'
                '    f.write(json.dumps(sys.argv[1:]) + "\\n")\n'
                'if os.environ.get("FAIL_ON") == " ".join(sys.argv[-4:]):\n'
                '    sys.exit(42)\n'
            )
            docker.chmod(0o755)
            calls = root / 'calls'
            result = subprocess.run(
                ['bash', str(script), *args], cwd=root, text=True, capture_output=True,
                env={**os.environ, 'PATH': f'{root}:{os.environ["PATH"]}',
                     'DOCKER_CALLS': str(calls), 'FAIL_ON': fail_on,
                     'COMPOSE_FILE': '/unrelated/preview.yaml',
                     'COMPOSE_PROJECT_NAME': 'existing-preview'},
            )
            commands = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
            return result, commands

    def test_runs_from_another_directory_and_pins_stack(self):
        result, commands = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        prefix = ['compose', '--project-directory', str(SCRIPT.parent),
                  '--file', str(SCRIPT.parent / 'compose.yaml'),
                  '--project-name', 'public-demo-ha']
        self.assertEqual(commands[:2], [['compose', 'version'], ['info']])
        self.assertEqual(commands[2:], [prefix + command for command in [
            ['config', '--quiet'],
            ['--profile', 'setup', '--profile', 'verify', 'build', 'gateway', 'bootstrap', 'verify'],
            ['up', '-d', '--force-recreate', 'homeassistant'],
            ['--profile', 'setup', 'run', '--rm', 'bootstrap'],
            ['up', '-d', 'gateway'],
            ['--profile', 'verify', 'run', '--rm', 'verify'],
        ]])
        self.assertIn('Public rollout is not verified', result.stdout)

    def test_failed_onboarding_does_not_start_gateway(self):
        result, commands = self.run_script(fail_on='setup run --rm bootstrap')
        self.assertEqual(result.returncode, 42)
        self.assertEqual(commands[-1][-4:], ['setup', 'run', '--rm', 'bootstrap'])
        self.assertNotIn('Backend provisioned', result.stdout)

    def test_failed_verification_is_not_reported_as_success(self):
        result, _ = self.run_script(fail_on='verify run --rm verify')
        self.assertEqual(result.returncode, 42)
        self.assertNotIn('Backend provisioned', result.stdout)

    def test_rejects_arguments_before_touching_docker(self):
        result, commands = self.run_script('--wrong')
        self.assertEqual(result.returncode, 2)
        self.assertEqual(commands, [])

    def test_native_setup_stops_ha_before_writing_auth_and_starts_it_afterwards(self):
        result, commands = self.run_script(script=SCRIPT.with_name('enable-native.sh'))
        self.assertEqual(result.returncode, 0, result.stderr)
        setup = next(index for index, command in enumerate(commands) if command[-4:] == ['native-setup', 'run', '--rm', 'native-setup'])
        self.assertEqual(commands[setup - 1][-2:], ['stop', 'homeassistant'])
        self.assertEqual(commands[setup + 1][-4:], ['up', '-d', 'homeassistant', 'native'])
        self.assertTrue(all(str(SCRIPT.parent / 'compose.native.yaml') in command for command in commands))

    def test_failed_native_setup_restarts_existing_ha_without_exposing_native(self):
        result, commands = self.run_script(script=SCRIPT.with_name('enable-native.sh'), fail_on='native-setup run --rm native-setup')
        self.assertEqual(result.returncode, 42)
        self.assertEqual(commands[-1][-3:], ['up', '-d', 'homeassistant'])
        self.assertFalse(any(command[-1:] == ['native'] and 'up' in command for command in commands))


if __name__ == '__main__':
    unittest.main()
