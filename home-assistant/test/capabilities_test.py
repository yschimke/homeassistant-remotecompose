"""`terrazzo/info`'s answer, driven with plain data (capabilities.py imports nothing from HA)."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'terrazzo_capabilities',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/capabilities.py')
capabilities = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capabilities)

STARTED = {'slug': 'x_card_designs', 'state': 'started', 'version': '0.4.2'}


def reply(**overrides):
    values = {'user_id': 'person', 'is_admin': False, 'design_server': None, 'mcp_user': None}
    values.update(overrides)
    return capabilities.reply('0.1.98', **values)


class ReplyTest(unittest.TestCase):
    def test_a_person_gets_the_protocol_version_and_everyones_capabilities(self):
        answer = reply()
        self.assertEqual(answer['protocol'], 1)
        self.assertEqual(answer['min_client'], 1)
        self.assertEqual(answer['version'], '0.1.98')
        self.assertEqual(answer['capabilities'], {
            'edit': 1, 'push': 1, 'design_selections': 1,
            'design_server': {'available': False, 'version': None},
            'web_origin': 1, 'agent': 2, 'wall': 'v1',
        })

    def test_an_administrators_commands_are_left_out_for_everyone_else(self):
        person = reply(mcp_user='agent')['capabilities']
        for key in ('mcp', 'edit_apply', 'design_server_open'):
            self.assertNotIn(key, person)
        admin = reply(is_admin=True)['capabilities']
        self.assertEqual(admin['edit_apply'], 1)
        self.assertEqual(admin['design_server_open'], 1)
        self.assertEqual(admin['mcp'], {'enabled': False})

    def test_the_mcp_servers_own_user_is_told_it_is_on(self):
        self.assertEqual(reply(user_id='agent', mcp_user='agent')['capabilities']['mcp'], {'enabled': True})
        self.assertEqual(reply(is_admin=True, mcp_user='agent')['capabilities']['mcp'], {'enabled': True})

    def test_the_design_server_is_available_only_while_started(self):
        self.assertEqual(reply(design_server=STARTED)['capabilities']['design_server'],
                         {'available': True, 'version': '0.4.2'})
        stopped = {**STARTED, 'state': 'stopped'}
        self.assertEqual(reply(design_server=stopped)['capabilities']['design_server'],
                         {'available': False, 'version': None})


if __name__ == '__main__':
    unittest.main()
