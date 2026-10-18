"""Operator entry-point tests; real Docker/HA coverage runs in demo-ha.yml."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name('provision.sh').resolve()


class ProvisionTests(unittest.TestCase):
    def run_script(self, *args, fail_on=''):
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
                ['bash', str(SCRIPT), *args], cwd=root, text=True, capture_output=True,
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


if __name__ == '__main__':
    unittest.main()
