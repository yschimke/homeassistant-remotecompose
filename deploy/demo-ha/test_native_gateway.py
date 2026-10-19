"""Prove native UI reads work and denied requests never reach upstream HA."""
import json
import unittest
from unittest.mock import patch

from aiohttp import web
from aiohttp import FormData
from aiohttp.test_utils import TestClient, TestServer

from native_gateway import NativeGateway, command_allowed, create_app, ENTITIES, STATIC_MARKDOWN, literal_markdown


class MarkdownTests(unittest.TestCase):
    def test_nested_sections_only_allow_exact_literal_markdown(self):
        dashboard = {'views': [{'sections': [{'cards': [
            {'type': 'markdown', 'content': 'Shared safe notice'},
            {'type': 'markdown', 'content': '{{ states }}'},
            {'type': 'markdown', 'content': '{% dangerous %}'},
            {'type': 'markdown', 'content': {'unexpected': 'object'}},
            {'type': 'button', 'content': 'not markdown'}]}]}]}
        self.assertEqual(set(literal_markdown(dashboard)), {'Shared safe notice'})


class NativeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.calls = []

        async def upstream(request):
            if request.path != '/api/websocket':
                self.calls.append((request.method, request.path))
                if request.path == '/auth/token':
                    return web.json_response(dict(await request.post()))
                return web.json_response({'path': request.path})
            ws = web.WebSocketResponse()
            await ws.prepare(request)
            await ws.send_json({'type': 'auth_required', 'ha_version': '2026.10.2'})
            auth = await ws.receive_json()
            token = auth.get('access_token')
            if token not in ('guest-fixture', 'operator-fixture'):
                await ws.send_json({'type': 'auth_invalid'})
                return ws
            await ws.send_json({'type': 'auth_ok', 'ha_version': '2026.10.2'})
            async for frame in ws:
                message = json.loads(frame.data)
                self.calls.append(message)
                result = {'id': 'readonly-id' if token == 'guest-fixture' else 'owner-id',
                          'is_admin': token == 'operator-fixture'} if message['type'] == 'auth/current_user' else {}
                await ws.send_json({'id': message['id'], 'type': 'result', 'success': True, 'result': result})
                if message['type'] == 'subscribe_entities':
                    await ws.send_json([{'id': message['id'], 'type': 'event', 'event': {'a': {}}}])
            return ws

        app = web.Application()
        app.router.add_route('*', '/{path:.*}', upstream)
        self.upstream = TestServer(app)
        await self.upstream.start_server()
        self.gateway = NativeGateway(str(self.upstream.make_url('')), 'readonly-id', 'https://ha-demo.test')
        self.client = TestClient(TestServer(create_app(self.gateway)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.upstream.close()

    async def connect(self, token='guest-fixture'):
        ws = await self.client.ws_connect('/api/websocket')
        self.assertEqual((await ws.receive_json())['type'], 'auth_required')
        await ws.send_json({'type': 'auth', 'access_token': token})
        return ws, await ws.receive_json()

    async def test_native_assets_and_authenticated_reads(self):
        for path in ['/', '/lovelace/home', '/frontend_latest/app.js', '/auth/authorize']:
            response = await self.client.get(path)
            self.assertEqual(response.status, 200)
        self.assertEqual((await self.client.get('/api/states')).status, 401)
        response = await self.client.get('/api/states', headers={'Authorization': 'Bearer guest-fixture'})
        self.assertEqual(response.status, 200)
        self.assertEqual(response.headers['Cache-Control'], 'no-store')
        self.assertEqual((await self.client.get('/api/config', headers={'Authorization': 'Bearer operator-fixture'})).status, 403)

    async def test_http_writes_and_sensitive_reads_never_forwarded(self):
        for method, path in [('POST', '/api/services/light/turn_on'), ('POST', '/api/states/sensor.demo_battery'),
                             ('DELETE', '/api/states/sensor.demo_battery'), ('POST', '/api/events/test'),
                             ('POST', '/api/onboarding/users'), ('GET', '/api/error_log'),
                             ('GET', '/api/config/core/config'), ('GET', '/api/states/sensor.private'),
                             ('GET', '/.storage/auth'), ('GET', '/api/onboarding')]:
            response = await self.client.request(method, path)
            self.assertIn(response.status, (403, 404))
        self.assertEqual(self.calls, [])

    async def test_guest_login_only(self):
        data = {'client_id': 'https://ha-demo.test/', 'handler': ['homeassistant', None],
                'redirect_uri': 'https://ha-demo.test/?auth_callback=1'}
        self.assertEqual((await self.client.post('/auth/login_flow', json=data)).status, 200)
        self.calls.clear()
        for change in [{'client_id': 'https://other.test/'}, {'handler': ['trusted_networks', None]},
                       {'redirect_uri': 'https://ha-demo.test.evil/'}]:
            self.assertEqual((await self.client.post('/auth/login_flow', json={**data, **change})).status, 403)
        self.assertEqual((await self.client.post('/auth/login_flow/abc', json={
            'client_id': 'https://ha-demo.test/', 'username': 'demo-operator', 'password': 'wrong'})).status, 403)
        self.assertEqual(self.calls, [])

    async def test_websocket_rejects_non_guest_identity(self):
        for token in ['operator-fixture', 'bad-fixture']:
            ws, reply = await self.connect(token)
            self.assertEqual(reply['type'], 'auth_invalid')
            await ws.close()

    async def test_browser_multipart_token_exchange_is_forwarded_as_valid_form(self):
        fields = {'client_id': 'https://ha-demo.test/', 'grant_type': 'authorization_code', 'code': 'fixture-code'}
        form = FormData(fields, default_to_multipart=True)
        response = await self.client.post('/auth/token', data=form)
        self.assertEqual(response.status, 200)
        self.assertEqual(await response.json(), fields)
        logout = {'action': 'revoke', 'token': 'fixture-refresh-token'}
        response = await self.client.post('/auth/token', data=logout)
        self.assertEqual(response.status, 200)
        self.assertEqual(await response.json(), logout)

    async def test_native_live_subscription_and_write_denial(self):
        ws, auth = await self.connect()
        self.assertEqual(auth['type'], 'auth_ok')
        await ws.send_json({'id': 1, 'type': 'subscribe_entities'})
        self.assertEqual((await ws.receive_json())['id'], 1)
        self.assertEqual((await ws.receive_json())[0]['id'], 1, 'coalesced event IDs must reach the native client unchanged')
        forwarded = next(call for call in self.calls if isinstance(call, dict) and call['type'] == 'subscribe_entities')
        self.assertEqual(forwarded['entity_ids'], sorted(ENTITIES))
        before = len(self.calls)
        for ident, kind in enumerate(['call_service', 'fire_event', 'lovelace/config/save', 'frontend/set_user_data',
                                      'auth/long_lived_access_token', 'config/auth/create', 'future/command'], 2):
            await ws.send_json({'id': ident, 'type': kind})
            reply = await ws.receive_json()
            self.assertEqual(reply['error']['code'], 'read_only')
        self.assertEqual(len(self.calls), before)
        await ws.send_json({'id': 20, 'type': 'get_config'})
        self.assertEqual((await ws.receive_json())['id'], 20)
        await ws.close()


class CommandTests(unittest.TestCase):
    def test_global_event_subscriptions_and_other_dashboards_are_denied(self):
        for message in [{'type': 'subscribe_events'}, {'type': 'subscribe_events', 'event_type': 'call_service'},
                        {'type': 'lovelace/config', 'url_path': 'private'}, {'type': {}}, {}]:
            self.assertFalse(command_allowed(message))

    def test_markdown_only_accepts_the_reviewed_literal(self):
        for literal in STATIC_MARKDOWN:
            self.assertTrue(command_allowed({'type': 'render_template', 'template': literal}))
        # The richer dashboard has no Markdown cards. Exercise the positive policy
        # with an explicit reviewed fixture as well as the current empty allowlist.
        with patch('native_gateway.STATIC_MARKDOWN', frozenset({'reviewed literal'})):
            self.assertTrue(command_allowed({'type': 'render_template', 'template': 'reviewed literal'}))
            for template in ['{{ states }}', '{% for state in states %}{{ state }}{% endfor %}', 'unlisted literal']:
                self.assertFalse(command_allowed({'type': 'render_template', 'template': template}))

    def test_dashboard_without_markdown_rejects_all_template_requests(self):
        with patch('native_gateway.STATIC_MARKDOWN', frozenset()):
            for template in ['reviewed literal', '{{ states }}', 'unlisted literal']:
                self.assertFalse(command_allowed({'type': 'render_template', 'template': template}))



if __name__ == '__main__':
    unittest.main()
