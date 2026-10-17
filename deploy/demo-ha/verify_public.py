"""Probe a local or deployed public facade. No administrative credential required."""
import argparse
import asyncio
import json

import aiohttp


async def wait_until_ready(session, base, seconds):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + seconds
    while (remaining := deadline - loop.time()) > 0:
        try:
            timeout = aiohttp.ClientTimeout(total=min(2, remaining))
            async with session.get(base + '/healthz', timeout=timeout) as response:
                if response.status == 200:
                    return
        except (aiohttp.ClientError, asyncio.TimeoutError):
            pass
        await asyncio.sleep(min(1, max(0, deadline - loop.time())))
    raise RuntimeError('Demo did not become healthy within the readiness window')


async def verify(base, stream, wait_ready=0):
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=15)) as session:
        if wait_ready > 0:
            await wait_until_ready(session, base, wait_ready)
        async with session.get(base + '/healthz') as r:
            assert r.status == 200, f'healthz: HTTP {r.status}'
            assert (await r.json())['read_only'] is True
        async with session.get(base + '/api/states') as r:
            assert r.status == 200, f'states: HTTP {r.status}'
            rows = await r.json()
            assert len(rows) == 6, f'Expected six generic entities, got {len(rows)}'
            battery = next(s for s in rows if s['entity_id'] == 'sensor.demo_battery')
            assert battery['state'] == '78'
            assert all(s['entity_id'].split('.')[1].startswith('demo_') for s in rows)
        async with session.get(base + '/dashboard') as r:
            assert r.status == 200
            dashboard = await r.json()
            assert dashboard['title'] == 'Demo Home'
        async with session.get(base + '/api/config') as r:
            assert r.status == 200
            assert (await r.json())['read_only'] is True
        print('PASS: health, six generic HA entities, dashboard and safe config')
        attempts = [
            ('POST', '/api/states/sensor.demo_battery', {'state': 'MUTATED'}),
            ('DELETE', '/api/states/sensor.demo_battery', {}),
            ('PUT', '/api/config', {'location_name': 'MUTATED'}),
            ('PATCH', '/api/config', {'location_name': 'MUTATED'}),
            ('POST', '/api/services/switch/turn_on', {'entity_id': 'switch.demo_coffee'}),
            ('POST', '/api/events/demo_write_probe', {}),
            ('POST', '/api/template', {'template': '{{ 1 + 1 }}'}),
            ('POST', '/auth/token', {}),
        ]
        for method, path, data in attempts:
            async with session.request(method, base + path, json=data) as r:
                assert r.status == 403, f'{method} {path}: expected 403, got {r.status}'
                assert (await r.json())['code'] == 'read_only'
        async with session.get(base + '/api/services') as r:
            assert r.status == 404
        print('PASS: REST mutation attempts rejected; unlisted reads blocked')
        async with session.ws_connect(base + '/api/websocket') as ws:
            assert (await ws.receive_json())['type'] == 'auth_required'
            await ws.send_json({'type': 'auth', 'access_token': 'public-demo'})
            assert (await ws.receive_json())['type'] == 'auth_ok'
            for ident, kind in enumerate(['get_states', 'get_config', 'lovelace/config', 'lovelace/dashboards/list'], 1):
                await ws.send_json({'id': ident, 'type': kind})
                assert (await ws.receive_json())['success'], kind
            for ident, kind in enumerate(['call_service', 'fire_event', 'lovelace/config/save', 'config/core/update', 'auth/long_lived_access_token', 'unknown_future_command'], 10):
                await ws.send_json({'id': ident, 'type': kind, 'domain': 'switch', 'service': 'turn_on',
                                    'service_data': {'entity_id': 'switch.demo_coffee'}, 'config': {}})
                reply = await ws.receive_json()
                assert reply['success'] is False and reply['error']['code'] == 'read_only', kind
            await ws.send_json({'id': 20, 'type': 'get_states'})
            states = (await ws.receive_json())['result']
            assert next(s for s in states if s['entity_id'] == 'sensor.demo_battery')['state'] == battery['state']
            await ws.send_json({'id': 21, 'type': 'lovelace/config'})
            assert (await ws.receive_json())['result'] == dashboard
            print('PASS: WebSocket reads allowed, mutations rejected, battery/dashboard unchanged')
            if stream:
                await ws.send_json({'id': 22, 'type': 'subscribe_events', 'event_type': 'state_changed'})
                assert (await ws.receive_json())['success']
                event = await asyncio.wait_for(ws.receive_json(), timeout=80)
                assert event['id'] == 22 and event['event']['event_type'] == 'state_changed'
                data = event['event']['data']
                assert data['entity_id'].split('.')[1].startswith('demo_')
                assert data['old_state'] != data['new_state']
                print('PASS: live state_changed event observed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('base', help='e.g. https://ha.coo.ee/demo-ha')
    parser.add_argument('--stream', action='store_true', help='Wait up to 80 seconds for a real update')
    parser.add_argument('--wait-ready', type=int, default=0, help='Wait this many seconds for healthy startup')
    args = parser.parse_args()
    asyncio.run(verify(args.base.rstrip('/'), args.stream, args.wait_ready))
