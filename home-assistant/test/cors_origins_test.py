"""A Terrazzo web install's origin and the HTTP config that allows it (cors_origins.py imports
nothing from HA)."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'terrazzo_cors_origins',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/cors_origins.py')
co = importlib.util.module_from_spec(spec)
spec.loader.exec_module(co)

STABLE = {
    'cors_allowed_origins': ['https://cast.home-assistant.io'],
    'created_at': '2026-08-10T18:24:24+00:00',
    'error': None,
    'error_message': None,
    'ip_ban_enabled': True,
    'server_port': 8123,
}


class CorsOriginsTest(unittest.TestCase):
    def test_the_origin_is_the_client_ids_scheme_host_and_port(self):
        self.assertEqual('https://terrazzo-yuri.wasmo.coo.ee',
                         co.origin_of('https://terrazzo-yuri.wasmo.coo.ee/'))
        self.assertEqual('https://pages.example.test',
                         co.origin_of('https://Pages.Example.test/terrazzo/app.html?x=1'))
        self.assertEqual('https://web.example.test:8443', co.origin_of('https://web.example.test:8443/'))
        self.assertEqual('https://web.example.test', co.origin_of('https://web.example.test:443/'))
        self.assertEqual('https://[::1]:8443', co.origin_of('https://[::1]:8443/'))

    def test_only_an_https_client_id_has_an_origin(self):
        for client_id in [None, '', 'http://web.example.test/', 'ee.schimke.harc',
                          'https://user:pw@web.example.test/', 'https://web.example.test:99999/',
                          'https:///path']:
            self.assertIsNone(co.origin_of(client_id), client_id)

    def test_the_new_config_keeps_the_others_and_drops_bookkeeping(self):
        config = co.with_origin(STABLE, 'https://terrazzo-yuri.wasmo.coo.ee')
        self.assertEqual({
            'cors_allowed_origins': ['https://cast.home-assistant.io', 'https://terrazzo-yuri.wasmo.coo.ee'],
            'ip_ban_enabled': True,
            'server_port': 8123,
        }, config)
        self.assertEqual(['https://cast.home-assistant.io'], STABLE['cors_allowed_origins'])
        self.assertEqual(config, co.with_origin(config, 'https://terrazzo-yuri.wasmo.coo.ee'))

    def test_allows_reads_the_config_list(self):
        self.assertTrue(co.allows(STABLE, 'https://cast.home-assistant.io'))
        self.assertFalse(co.allows(STABLE, 'https://terrazzo-yuri.wasmo.coo.ee'))
        self.assertFalse(co.allows(None, 'https://cast.home-assistant.io'))

    def test_each_origin_has_its_own_stable_issue(self):
        first = co.issue_id('https://a.example.test')
        self.assertEqual(first, co.issue_id('https://a.example.test'))
        self.assertNotEqual(first, co.issue_id('https://b.example.test'))
        self.assertRegex(first, r'^web_origin_[0-9a-f]{12}$')


if __name__ == '__main__':
    unittest.main()
