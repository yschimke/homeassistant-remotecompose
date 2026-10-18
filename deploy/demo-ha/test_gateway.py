import asyncio
import json
from pathlib import Path
import time
import unittest

from aiohttp import ClientSession, WSMsgType, web
from aiohttp.test_utils import TestClient, TestServer

from gateway import Gateway, create_app
from verify_public import verify


def state(value='21.5'):
    return {'entity_id': 'sensor.demo_temperature', 'state': value,
            'attributes': {'friendly_name': 'Demo temperature', 'unit_of_measurement': '°C',
                           'access_token': 'private-value', 'entity_picture': '/api/camera_proxy/private'},
            'last_changed': '2026-10-17T12:00:00Z', 'last_updated': '2026-10-17T12:00:00Z',
            'context': {'user_id': 'private-user'}}


class GatewayTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.requests = []
        entities = json.loads((Path(__file__).parent / 'entities.json').read_text())
        self.rows = [dict(state(), entity_id=entity) for entity in entities]
        next(s for s in self.rows if s['entity_id'] == 'sensor.demo_battery')['state'] = '78'
        for row in self.rows:
            if row['entity_id'] in ('light.demo_lamp', 'switch.demo_coffee'):
                row['state'] = 'off'
        self.rows.append(dict(state(), entity_id='sensor.private_device'))
        async def upstream(request):
            self.requests.append((request.method, request.path, request.headers.get('Authorization')))
            if request.method == 'POST':
                data = await request.json()
                self.service_data = data
                entity = data['entity_id']
                service = request.path.rsplit('/', 1)[-1]
                row = next(row for row in self.rows if row['entity_id'] == entity)
                row['state'] = ('off' if row['state'] == 'on' else 'on') if service == 'toggle' else ('on' if service == 'turn_on' else 'off')
                return web.json_response([{'private': 'upstream-response-secret'}])
            return web.json_response(self.rows)
        app = web.Application()
        app.router.add_route('*', '/{path:.*}', upstream)
        self.ha = TestServer(app)
        await self.ha.start_server()
        self.g = Gateway(str(self.ha.make_url('')).rstrip('/'), 'secret-token')
        self.session = ClientSession()
        await self.g.poll_once(self.session)
        self.client = TestClient(TestServer(create_app(self.g, poll=False)))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        await self.session.close()
        await self.ha.close()

    async def connect(self):
        ws = await self.client.ws_connect('/api/websocket')
        self.assertEqual((await ws.receive_json())['type'], 'auth_required')
        await ws.send_json({'type': 'auth', 'access_token': 'public-demo'})
        self.assertEqual((await ws.receive_json())['type'], 'auth_ok')
        return ws

    async def test_rest_reads_are_sanitized_and_do_not_leak_private_metadata(self):
        for path in ['/api/', '/api/config', '/api/states', '/api/states/sensor.demo_temperature', '/dashboard', '/healthz']:
            response = await self.client.get(path)
            self.assertEqual(response.status, 200, path)
            body = await response.text()
            for private in ['secret-token', 'private-value', 'private_device', 'private-user', 'camera_proxy']:
                self.assertNotIn(private, body)
            self.assertEqual(response.headers['Cache-Control'], 'no-store')
        self.assertEqual(len(await (await self.client.get('/api/states')).json()), 6)
        self.assertEqual((await self.client.get('/api/states/sensor.private_device')).status, 404)

    async def test_rest_denies_all_mutation_and_unlisted_read_routes(self):
        for method in ['POST', 'PUT', 'PATCH', 'DELETE']:
            for path in ['/api/states/sensor.demo_temperature', '/api/services/light/turn_on', '/api/events/test', '/auth/token', '/api/config', '/api/websocket']:
                response = await self.client.request(method, path, json={'state': 'MUTATED'})
                self.assertEqual(response.status, 403, (method, path))
        for path in ['/api/template', '/auth/', '/api/history/period', '/api/config/core/check_config', '/api/states/../services', '/api//states']:
            self.assertEqual((await self.client.get(path)).status, 404, path)
        self.assertEqual(self.g.states['sensor.demo_temperature']['state'], '21.5')
        self.assertEqual(self.requests, [('GET', '/api/states', 'Bearer secret-token')])

    async def test_websocket_reads_streaming_unsubscribe_and_deny_by_default(self):
        ws = await self.connect()
        for ident, kind in enumerate(['get_states', 'get_config', 'lovelace/config', 'lovelace/dashboards/list', 'config/entity_registry/list_for_display', 'config/device_registry/list'], 1):
            await ws.send_json({'id': ident, 'type': kind})
            self.assertTrue((await ws.receive_json())['success'], kind)
        for ident, kind in enumerate(['call_service', 'fire_event', 'lovelace/config/save', 'config/core/update', 'auth/long_lived_access_token', 'render_template', 'subscribe_entities', 'future_write', ['call_service']], 10):
            await ws.send_json({'id': ident, 'type': kind, 'domain': 'light', 'service': 'turn_on'})
            response = await ws.receive_json()
            self.assertFalse(response['success'], str(kind))
            self.assertEqual(response['error']['code'], 'forbidden')
        await ws.send_json({'id': 30, 'type': 'subscribe_events'})
        self.assertFalse((await ws.receive_json())['success'])
        await ws.send_json({'id': 31, 'type': 'subscribe_events', 'event_type': 'state_changed'})
        self.assertTrue((await ws.receive_json())['success'])
        self.rows[0] = state('22.1')
        await self.g.poll_once(self.session)
        event = await asyncio.wait_for(ws.receive_json(), 2)
        self.assertEqual(event['id'], 31)
        self.assertEqual(event['event']['data']['old_state']['state'], '21.5')
        self.assertEqual(event['event']['data']['new_state']['state'], '22.1')
        self.assertNotIn('private', json.dumps(event))
        await ws.send_json({'id': 32, 'type': 'unsubscribe_events', 'subscription': 31})
        self.assertTrue((await ws.receive_json())['success'])
        await ws.send_json({'id': 33, 'type': 'ping'})
        self.assertEqual((await ws.receive_json())['type'], 'pong')
        self.assertTrue(all(m == 'GET' and p == '/api/states' for m, p, _ in self.requests))
        await ws.close()

    async def test_rest_controls_only_named_demo_entities_and_returns_sanitized_updates(self):
        for domain, entity in [('light', 'light.demo_lamp'), ('switch', 'switch.demo_coffee')]:
            for service, expected in [('turn_on', 'on'), ('toggle', 'off'), ('turn_off', 'off')]:
                response = await self.client.post(f'/api/services/{domain}/{service}', json={'entity_id': entity})
                self.assertEqual(response.status, 200)
                body = await response.text()
                self.assertNotIn('secret', body)
                rows = json.loads(body)
                self.assertEqual(next(r for r in rows if r['entity_id'] == entity)['state'], expected)
                self.assertEqual(self.service_data, {'entity_id': entity})
        self.assertEqual(sum(m == 'POST' for m, _, _ in self.requests), 6)

    async def test_invalid_service_targets_and_admin_actions_never_reach_upstream(self):
        before = list(self.requests)
        for path in ['/api/services/light/turn_on', '/api/services/homeassistant/turn_off',
                     '/api/services/homeassistant/restart', '/api/services/light/reload',
                     '/api/services/script/turn_on', '/api/services/light/turn_on/extra']:
            for data in [None, [], {}, {'entity_id': 'all'}, {'entity_id': 'light.private'},
                         {'entity_id': ['light.demo_lamp', 'light.private']},
                         {'entity_id': 'switch.demo_coffee'},
                         {'entity_id': 'light.demo_lamp', 'area_id': 'all'},
                         {'entity_id': 'light.demo_lamp', 'brightness': 255}]:
                response = await self.client.post(path, json=data)
                self.assertIn(response.status, (400, 403), (path, data))
        self.assertEqual(self.requests, before)
        ws = await self.connect()
        for ident, payload in enumerate([
            {'domain': 'homeassistant', 'service': 'restart'},
            {'domain': 'light', 'service': 'toggle', 'target': {'area_id': 'all'}},
            {'domain': 'light', 'service': 'toggle', 'target': {'entity_id': 'light.private'}},
            {'domain': 'light', 'service': 'toggle', 'service_data': {'entity_id': 'light.demo_lamp'}, 'return_response': True},
            {'domain': 'light', 'service': 'toggle', 'service_data': {'entity_id': 'light.demo_lamp'}, 'extra': 'value'},
            {'domain': [], 'service': []},
        ], 1):
            await ws.send_json({'id': ident, 'type': 'call_service', **payload})
            self.assertFalse((await ws.receive_json())['success'])
        self.assertEqual(self.requests, before)
        await ws.close()

    async def test_websocket_control_publishes_real_changed_state_to_other_visitors(self):
        subscriber = await self.connect()
        await subscriber.send_json({'id': 1, 'type': 'subscribe_events', 'event_type': 'state_changed'})
        self.assertTrue((await subscriber.receive_json())['success'])
        ws = await self.connect()
        await ws.send_json({'id': 2, 'type': 'call_service', 'domain': 'light', 'service': 'turn_on',
                            'target': {'entity_id': ['light.demo_lamp']}})
        self.assertTrue((await ws.receive_json())['success'])
        event = await asyncio.wait_for(subscriber.receive_json(), 2)
        self.assertEqual(event['event']['data']['entity_id'], 'light.demo_lamp')
        self.assertEqual(event['event']['data']['new_state']['state'], 'on')
        self.assertNotIn('secret', json.dumps(event))
        await subscriber.close()
        await ws.close()

    async def test_stale_controls_fail_closed_and_upstream_errors_do_not_leak(self):
        before = list(self.requests)
        self.g.updated = time.monotonic() - 60
        response = await self.client.post('/api/services/light/toggle', json={'entity_id': 'light.demo_lamp'})
        self.assertEqual(response.status, 503)
        self.assertEqual(self.requests, before)
        self.g.updated = time.monotonic()
        await self.ha.close()
        response = await self.client.post('/api/services/light/toggle', json={'entity_id': 'light.demo_lamp'})
        self.assertEqual(response.status, 503)
        self.assertNotIn('secret', await response.text())

    async def test_partial_startup_or_reload_does_not_mark_incomplete_data_fresh(self):
        previous_states, previous_updated = self.g.states, self.g.updated
        self.rows = [state('99')]
        with self.assertRaises(ValueError):
            await self.g.poll_once(self.session)
        self.assertEqual(self.g.states, previous_states)
        self.assertEqual(self.g.updated, previous_updated)
        self.g.updated, self.g.states = 0, {}
        with self.assertRaises(ValueError):
            await self.g.poll_once(self.session)
        self.assertFalse(self.g.fresh())
        self.assertEqual((await self.client.get('/healthz')).status, 503)

    async def test_public_verifier_runs_without_private_credentials(self):
        await verify(str(self.client.make_url('')).rstrip('/'), stream=False, wait_ready=1)
        writes = [(m, p) for m, p, _ in self.requests if m == 'POST']
        self.assertEqual(writes, [('POST', f'/api/services/{domain}/{service}')
                                 for domain in ('light', 'switch')
                                 for service in ('turn_on', 'turn_off')])
        for entity in ('light.demo_lamp', 'switch.demo_coffee'):
            self.assertEqual(self.g.states[entity]['state'], 'off')

    async def test_public_verifier_accepts_ha_numeric_format_and_rejects_wrong_battery(self):
        battery = next(s for s in self.rows if s['entity_id'] == 'sensor.demo_battery')
        battery['state'] = '78.0'
        await self.g.poll_once(self.session)
        await verify(str(self.client.make_url('')).rstrip('/'), stream=False)
        for value in ['77.0', 'unknown', 'unavailable', 'NaN', 'Infinity']:
            with self.subTest(value=value):
                battery['state'] = value
                await self.g.poll_once(self.session)
                with self.assertRaisesRegex(AssertionError, 'battery'):
                    await verify(str(self.client.make_url('')).rstrip('/'), stream=False)

    async def test_initial_unknown_entities_do_not_make_gateway_ready(self):
        self.g.states, self.g.updated = {}, 0
        battery = next(s for s in self.rows if s['entity_id'] == 'sensor.demo_battery')
        for value in ['unknown', 'unavailable']:
            battery['state'] = value
            with self.assertRaisesRegex(ValueError, 'initializ'):
                await self.g.poll_once(self.session)
            self.assertEqual((await self.client.get('/healthz')).status, 503)
            self.assertEqual(self.g.states, {})
        battery['state'] = '78.0'
        await self.g.poll_once(self.session)
        self.assertEqual((await self.client.get('/healthz')).status, 200)
        # Once initialized, genuine availability changes still reach clients.
        battery['state'] = 'unavailable'
        await self.g.poll_once(self.session)
        self.assertEqual(self.g.states['sensor.demo_battery']['state'], 'unavailable')

    async def test_readiness_deadline_includes_slow_request_time(self):
        async def slow_health(request):
            await asyncio.sleep(2)
            return web.json_response({'read_only': True})
        app = web.Application()
        app.router.add_get('/healthz', slow_health)
        server = TestServer(app)
        await server.start_server()
        started = time.monotonic()
        try:
            with self.assertRaisesRegex(RuntimeError, 'readiness window'):
                await verify(str(server.make_url('')).rstrip('/'), stream=False, wait_ready=1)
            self.assertLess(time.monotonic() - started, 1.7)
        finally:
            await server.close()

    async def test_stale_backend_is_not_reported_healthy(self):
        self.g.updated = time.monotonic() - 60
        for path in ['/healthz', '/api/states', '/dashboard', '/api/config', '/api/websocket']:
            self.assertEqual((await self.client.get(path)).status, 503, path)
        self.assertEqual((await self.client.post('/api/states')).status, 403)

    async def test_malformed_frames_cannot_crash_gateway(self):
        for frame in ['null', '[]', 'false', '{"id": [], "type": "get_states"}', '{"id": true}', '{broken']:
            ws = await self.connect()
            await ws.send_str(frame)
            self.assertEqual((await ws.receive()).type, WSMsgType.CLOSE)
            await ws.close()
        self.assertEqual((await self.client.get('/healthz')).status, 200)


if __name__ == '__main__':
    unittest.main()
