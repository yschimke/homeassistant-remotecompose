"""The private design server's routing rules, driven with plain data (design_route.py imports
nothing from HA)."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    'terrazzo_design_route',
    Path(__file__).resolve().parents[2] / 'custom_components/terrazzo/design_route.py')
route = importlib.util.module_from_spec(spec)
spec.loader.exec_module(route)


class FindAddonTest(unittest.TestCase):
    def test_a_repository_or_local_install_is_found_and_a_started_one_wins(self):
        addons = [
            {'slug': 'core_mosquitto', 'state': 'started'},
            {'slug': 'local_card_designs', 'state': 'stopped'},
            {'slug': 'a1b2c3d4_card_designs', 'state': 'started'},
        ]
        self.assertEqual('a1b2c3d4_card_designs', route.find_addon(addons)['slug'])

    def test_none_without_it(self):
        self.assertIsNone(route.find_addon([{'slug': 'a1b2c3d4_card_designs_extra'}]))
        self.assertIsNone(route.find_addon([]))


class AppPathTest(unittest.TestCase):
    def test_the_app_api_passes(self):
        for path in ('designs/v1/manifest.json', '/designs/v1/blobs/ab.rc', 'api/cards/resolve',
                     'api/studio/open', 'api/my/designs'):
            self.assertIsNotNone(route.app_path(path), path)

    def test_ui_builder_pages_and_escapes_are_refused(self):
        for path in ('', 'ui-builder/designs', 'api/ui-builder/v1/identity', 'studio/abc', 'my',
                     'designs', 'designs/v1/../../ui-builder/x', 'api/my/..%2f', 'api/my\\x',
                     'api/mystery'):
            self.assertIsNone(route.app_path(path), path)


class PagePathTest(unittest.TestCase):
    def test_design_pages_open_and_nothing_else(self):
        self.assertEqual('ui-builder/card-x', route.page_path('/ui-builder/card-x'))
        self.assertEqual('studio/abc', route.page_path('/studio/abc'))
        self.assertEqual('my', route.page_path('/my'))
        for path in ('/api/ui-builder/v1/identity', '/', '/designs/v1/manifest.json',
                     '/ui-builder/../api/x', '/ui-builder/x?token=t', '//evil.example/ui-builder/x'):
            self.assertIsNone(route.page_path(path), path)


class HeadersTest(unittest.TestCase):
    def test_the_apps_token_goes_on_and_identity_is_ours_to_set(self):
        sent = {'Authorization': 'Bearer t', 'Accept': 'application/json', 'Cookie': 'x=y',
                'X-Remote-User-Name': 'someone-else', 'X-Ingress-Path': '/elsewhere'}
        headers = route.upstream_headers(sent, 'yuri', 'home.example')
        self.assertEqual('Bearer t', headers['Authorization'])
        self.assertEqual('yuri', headers['X-Remote-User-Name'])
        self.assertEqual(route.DESIGNS_URL, headers['X-Ingress-Path'])
        self.assertEqual('home.example', headers['X-Forwarded-Host'])
        self.assertNotIn('Cookie', headers)
        self.assertNotIn('X-Remote-User-Name', route.upstream_headers(sent, None, 'h'))

    def test_only_the_responses_own_headers_come_back(self):
        self.assertEqual(
            {'Content-Type': 'application/json', 'ETag': '"1"'},
            route.downstream_headers({'Content-Type': 'application/json', 'ETag': '"1"',
                                      'Set-Cookie': 'a=b', 'Server': 'nginx'}))


if __name__ == '__main__':
    unittest.main()
