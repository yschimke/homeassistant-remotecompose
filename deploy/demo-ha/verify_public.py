"""Probe a local or deployed public facade. No administrative credential required."""
import argparse
import asyncio
import json
from pathlib import Path

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
            assert (await r.json())['read_only'] is False
        async with session.get(base + '/api/states') as r:
            assert r.status == 200, f'states: HTTP {r.status}'
            rows = await r.json()
            expected = set(json.loads(Path(__file__).with_name('entities.json').read_text()))
            assert {row['entity_id'] for row in rows} == expected
            battery = next(s for s in rows if s['entity_id'] == 'sensor.demo_battery')
            # HA numeric sensors can serialize the configured 78 as "78.0".
            try:
                battery_value = float(battery['state'])
            except (ValueError, TypeError):
                raise AssertionError(f"Expected bounded demo battery, got {battery['state']!r}") from None
            assert 45 <= battery_value <= 95, f"Expected bounded demo battery, got {battery['state']!r}"
            assert all(s['entity_id'].split('.')[1].startswith('demo_') for s in rows)
        async with session.get(base + '/dashboard') as r:
            assert r.status == 200
            dashboard = await r.json()
            assert dashboard['title'] == 'Demo Home'
        async with session.get(base + '/api/config') as r:
            assert r.status == 200
            assert (await r.json())['read_only'] is False
        print('PASS: health, allowlisted generic HA entities, dashboard and safe config')
        attempts = [
            ('POST', '/api/states/sensor.demo_battery', {'state': 'MUTATED'}),
            ('DELETE', '/api/states/sensor.demo_battery', {}),
            ('PUT', '/api/config', {'location_name': 'MUTATED'}),
            ('PATCH', '/api/config', {'location_name': 'MUTATED'}),
            ('POST', '/api/services/homeassistant/restart', {}),
            ('POST', '/api/services/light/turn_on', {'entity_id': 'all'}),
            ('POST', '/api/events/demo_write_probe', {}),
            ('POST', '/api/template', {'template': '{{ 1 + 1 }}'}),
            ('POST', '/auth/token', {}),
        ]
        for method, path, data in attempts:
            async with session.request(method, base + path, json=data) as r:
                assert r.status == 403, f'{method} {path}: expected 403, got {r.status}'
                assert (await r.json())['code'] == 'forbidden'
        async with session.get(base + '/api/services') as r:
            assert r.status == 404
        print('PASS: REST admin and unlisted targets rejected; unlisted reads blocked')
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
                assert reply['success'] is False and reply['error']['code'] == 'forbidden', kind
            await ws.send_json({'id': 20, 'type': 'get_states'})
            states = (await ws.receive_json())['result']
            assert 45 <= float(next(s for s in states if s['entity_id'] == 'sensor.demo_battery')['state']) <= 95
            await ws.send_json({'id': 21, 'type': 'lovelace/config'})
            assert (await ws.receive_json())['result'] == dashboard
            print('PASS: WebSocket admin commands rejected, bounded battery and unchanged dashboard')
            # Real service calls must update the named HA entities, not just return success.
            for domain, entity in [('light', 'light.demo_lamp'), ('switch', 'switch.demo_coffee')]:
                original = next(s for s in states if s['entity_id'] == entity)['state']
                assert original in ('on', 'off'), (entity, original)
                desired = 'off' if original == 'on' else 'on'
                try:
                    async with session.post(base + f'/api/services/{domain}/turn_{desired}', json={'entity_id': entity}) as r:
                        assert r.status == 200
                        changed = await r.json()
                        assert next(s for s in changed if s['entity_id'] == entity)['state'] == desired
                    await ws.send_json({'id': 40, 'type': 'get_states'})
                    changed = (await ws.receive_json())['result']
                    assert next(s for s in changed if s['entity_id'] == entity)['state'] == desired
                finally:
                    await ws.send_json({'id': 41, 'type': 'call_service', 'domain': domain,
                                        'service': 'turn_' + original, 'target': {'entity_id': entity}})
                    assert (await ws.receive_json())['success']
                await ws.send_json({'id': 42, 'type': 'get_states'})
                restored = (await ws.receive_json())['result']
                assert next(s for s in restored if s['entity_id'] == entity)['state'] == original
            climate = next(row for row in rows if row['entity_id'] == 'climate.demo_thermostat')
            original = climate['attributes']['temperature']
            desired = 22.5 if original != 22.5 else 22
            try:
                async with session.post(base + '/api/services/climate/set_temperature', json={
                    'entity_id': 'climate.demo_thermostat', 'temperature': desired}) as r:
                    assert r.status == 200
                    changed = next(row for row in await r.json() if row['entity_id'] == 'climate.demo_thermostat')
                    assert changed['attributes']['temperature'] == desired
            finally:
                await ws.send_json({'id': 50, 'type': 'call_service', 'domain': 'climate',
                                    'service': 'set_temperature',
                                    'target': {'entity_id': 'climate.demo_thermostat'},
                                    'service_data': {'temperature': original}})
                assert (await ws.receive_json())['success']
            await ws.send_json({'id': 51, 'type': 'get_states'})
            restored = (await ws.receive_json())['result']
            assert next(row for row in restored if row['entity_id'] == 'climate.demo_thermostat')['attributes']['temperature'] == original
            print('PASS: REST controls change real HA lamp, coffee and thermostat state; WebSocket restores it')
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
