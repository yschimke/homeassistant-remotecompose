#!/usr/bin/env python3
"""Authenticated section packing and MeshCore chat, with synthetic transport data only in tests."""
import hashlib
import http.server
import json
import os
import re
from pathlib import Path
import sys
import threading
import time
from playwright.sync_api import expect, sync_playwright

root = Path(sys.argv[1]).resolve()
before = '--before' in sys.argv
heading = lambda name: {'type': 'heading', 'heading': name}
tile = lambda entity, name: {'type': 'tile', 'entity': entity, 'name': name}
grid = lambda cards: {'type': 'grid', 'columns': 2, 'cards': cards}
printer = 'p2s_fixture'
metric = lambda suffix, name: tile('sensor.' + printer + '_' + suffix, name)
dashboard = {'title': '3D Printing', 'views': [{'title': 'P2s', 'cards': [
    {'type': 'markdown', 'content': '# 🖨️ P2s\n**Status:** {{ states("sensor.p2s_fixture_print_status") }} · **Stage:** {{ states("sensor.p2s_fixture_current_stage") }}'},
    heading('Current print'),
    {'type': 'custom:ha-bambulab-print_status-card', 'printer': 'printer-device'},
    grid([{'type': 'picture-entity', 'entity': 'image.p2s_cover', 'name': 'Cover preview', 'show_state': False},
          {'type': 'entity', 'entity': 'image.p2s_pick', 'name': 'Pick image'}]),
    grid([metric('print_progress', 'Progress'), metric('current_layer', 'Current layer'),
          metric('total_layer_count', 'Total layers'), metric('remaining_time', 'Remaining'),
          metric('start_time', 'Start'), metric('end_time', 'ETA')]),
    {'type': 'conditional', 'conditions': [{'entity': 'sensor.p2s_fixture_print_status', 'state': 'finish'}],
     'card': {'type': 'markdown', 'content': 'Print is complete'}},
    {'type': 'conditional', 'conditions': [{'entity': 'sensor.p2s_fixture_print_status', 'state': 'running'}],
     'card': {'type': 'markdown', 'content': 'Print is running'}},
    {'type': 'conditional', 'conditions': [{'entity': 'sensor.p2s_fixture_print_status', 'state': 'never'}],
     'card': {'type': 'markdown', 'content': 'Hidden conditional content'}},
    grid([tile('light.p2s_chamber', 'Chamber light'), tile('switch.p2s_camera', 'Camera')]),
    heading('Temperatures'), grid([metric('nozzle_temperature', 'Nozzle'), metric('bed_temperature', 'Bed')]),
    {'type': 'markdown', 'content': '[Open P2s detail →](/dashboard-bambu/p2s)'},
    heading('More readings'), *[metric('current_layer', f'Layer reading {i}') for i in range(12)],
]}]}
values = [('print_status', 'finish', None), ('current_stage', 'idle', None),
          ('print_progress', '100', '%'), ('current_layer', '128', None),
          ('total_layer_count', '128', None), ('remaining_time', '0', 's'),
          ('start_time', 'unavailable', None), ('end_time', 'unknown', None),
          ('nozzle_temperature', '22', '°C'), ('bed_temperature', '18', '°C')]
states = [{'entity_id': 'sensor.' + printer + '_' + suffix, 'state': value,
           'attributes': {'friendly_name': 'P2S serial ' + suffix,
            **({'unit_of_measurement': unit} if unit else {}),
            **({'device_class': 'temperature'} if unit == '°C' else {'device_class': 'timestamp'} if suffix in ('start_time', 'end_time') else {})}}
          for suffix, value, unit in values]
states += [{'entity_id': 'image.p2s_' + suffix, 'state': '2026-10-18T17:23:17.432840',
            'attributes': {'entity_picture': '/api/image_proxy/' + suffix + '?token=fixture-image'}}
           for suffix in ['cover', 'pick']]
states += [{'entity_id': 'light.p2s_chamber', 'state': 'on', 'attributes': {'friendly_name': 'P2S_SERIAL_LONG_Chamber light'}},
           {'entity_id': 'switch.p2s_camera', 'state': 'on', 'attributes': {'friendly_name': 'P2S_SERIAL_LONG_Camera'}}]
commands, errors, resolutions, image_reads = [], [], [], []
latest_socket = latest_subscription = None

class Handler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        return str(root / ('index.html' if path.split('?')[0] == '/' else path.lstrip('/').split('?')[0]))
    def log_message(self, *args): pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()

def settled_capture(page, path):
    previous, stable = None, 0
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        current = page.screenshot()
        stable = stable + 1 if current == previous else 0
        if stable == 3:
            Path(path).write_bytes(current)
            return
        previous = current
        page.wait_for_timeout(100)
    raise AssertionError('The dashboard did not finish laying out before a real pointer press')


def socket_route(socket):
    global latest_socket
    latest_socket = socket
    socket.send(json.dumps({'type': 'auth_required'}))
    def receive(raw):
        global latest_subscription
        msg = json.loads(raw)
        if msg['type'] == 'auth':
            assert msg['access_token'] == 'fixture-token'
            socket.send(json.dumps({'type': 'auth_ok'})); return
        commands.append(msg)
        kind = msg['type']
        if kind == 'subscribe_events': latest_subscription = msg['id']
        result = {'get_states': states, 'lovelace/dashboards/list': [{'url_path': 'printing', 'title': '3D Printing'}],
                  'lovelace/config': dashboard, 'subscribe_events': None,
                  'config/entity_registry/list_for_display': {'entities': [
                      {'ei': item['entity_id'], 'di': 'printer-device', 'pl': 'bambu_lab'} for item in states
                  ]}, 'config/device_registry/list': [{'id': 'printer-device', 'manufacturer': 'Bambu Lab', 'model': 'P2S'}],
                  'call_service': {},
                  'get_config': {'unit_system': {'temperature': '°C', 'length': 'km', 'mass': 'kg', 'volume': 'L'}}}
        assert kind in result, kind
        socket.send(json.dumps({'id': msg['id'], 'type': 'result', 'success': True, 'result': result[kind]}))
    socket.on_message(receive)

Path('_wasm-test').mkdir(exist_ok=True)
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH'), args=['--no-sandbox', '--disable-gpu'])
        for name, width, height in [('phone', 390, 844), ('narrow', 320, 800), ('wall', 1280, 900)]:
            print('Printing dashboard:', name, 'before' if before else 'after', flush=True)
            page = browser.new_page(viewport={'width': width, 'height': height}, device_scale_factor=1)
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.route_web_socket('wss://ha.example.test/api/websocket', socket_route)
            sensor = Path('scripts/fixtures/printing/ambiguous-sensor.rc').read_bytes()
            sha = hashlib.sha256(sensor).hexdigest()
            def resolve(route):
                data = json.loads(route.request.post_data)
                assert 'states' not in data and 'fixture-token' not in route.request.post_data
                resolutions.append(data)
                route.fulfill(json={'sha256': sha, 'exact': False,
                    'key': {'shape': 'device_class=temperature;domain=sensor'}},
                    headers={'Access-Control-Allow-Origin': '*'})
            page.route('https://ha.coo.ee/api/cards/resolve', resolve)
            page.route('https://ha.coo.ee/designs/v1/blobs/*.rc', lambda route: route.fulfill(body=sensor,
                content_type='application/octet-stream', headers={'Access-Control-Allow-Origin': '*'}))
            from PIL import Image
            import io
            def picture(route):
                image_reads.append(route.request.url)
                assert route.request.headers.get('authorization') == 'Bearer fixture-token'
                buf = io.BytesIO()
                from urllib.parse import unquote
                color = '#a42d34' if '17:30:00' in unquote(route.request.url) else '#2474a4' if 'cover' in route.request.url else '#56a467'
                Image.new('RGB', (160, 120), color).save(buf, format='PNG')
                route.fulfill(body=buf.getvalue(), content_type='image/png',
                    headers={'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization'})
            page.route('https://ha.example.test/api/image_proxy/**', picture)
            page.goto(f'http://127.0.0.1:{server.server_port}/', wait_until='networkidle')
            for label, value in [('Home Assistant URL', 'https://ha.example.test'), ('Long-lived access token', 'fixture-token')]:
                page.get_by_role('textbox', name=label, exact=True).click(force=True, delay=100)
                page.evaluate('() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))')
                page.keyboard.insert_text(value)
                page.evaluate('() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))')
            page.get_by_role('button', name='Connect', exact=True).click(force=True, delay=100)
            expect(page.get_by_text('Current print', exact=True)).to_be_visible(timeout=30000)
            page.wait_for_timeout(1000)
            page.screenshot(path=f'_wasm-test/printing-{"before" if before else "after"}-{name}.png')
            if before:
                if name == 'phone':
                    page.mouse.move(190, 650); page.mouse.wheel(0, 550); page.wait_for_timeout(300)
                    page.screenshot(path='_wasm-test/printing-before-readings-phone.png')
                    page.get_by_role('button', name='Card style: Classic', exact=True).click(force=True, delay=100)
                    page.get_by_text('Lcd', exact=True).click(force=True, delay=100)
                    page.wait_for_timeout(700)
                    page.screenshot(path='_wasm-test/printing-before-lcd-phone.png')
                page.close(); continue
            expect(page.get_by_text('2026-10-18T17:23:17.432840', exact=True)).to_have_count(0)
            expect(page.get_by_role('button', name='Cover preview', exact=True)).to_be_visible(timeout=15000)
            expect(page.get_by_text('Print is complete', exact=True)).to_have_count(1)
            expect(page.get_by_text('Print is running', exact=True)).to_have_count(0)
            expect(page.get_by_text('Hidden conditional content', exact=True)).to_have_count(0)
            expect(page.get_by_text('Conditional', exact=True)).to_have_count(0)
            expect(page.get_by_role('button', name='Start Unavailable', exact=True)).to_have_count(1)
            expect(page.get_by_role('button', name='ETA Unknown', exact=True)).to_have_count(1)
            # Live values, condition changes, and a new image with the same entity_picture URL.
            def update(id, value):
                old = next(item for item in states if item['entity_id'] == id)
                new = {**old, 'state': value}
                latest_socket.send(json.dumps({'id': latest_subscription, 'type': 'event', 'event': {
                    'event_type': 'state_changed', 'data': {'entity_id': id, 'old_state': old, 'new_state': new}}}))
            update('sensor.p2s_fixture_print_status', 'running')
            expect(page.get_by_text('Print is running', exact=True)).to_have_count(1)
            expect(page.get_by_text('Print is complete', exact=True)).to_have_count(0)
            update('sensor.p2s_fixture_print_progress', '62')
            expect(page.get_by_role('button', name='Progress 62 %', exact=True)).to_have_count(1)
            expect(page.get_by_text('Progress: 62 %', exact=True)).to_have_count(1)
            update('sensor.p2s_fixture_current_layer', '129')
            expect(page.get_by_role('button', name='Current layer 129', exact=True)).to_have_count(1)
            expect(page.get_by_text('Current layer: 129', exact=True)).to_have_count(1)
            def painted_color(rgb):
                picture = Image.open(io.BytesIO(page.screenshot())).convert('RGB')
                pixels = picture.get_flattened_data() if hasattr(picture, 'get_flattened_data') else picture.getdata()
                return sum(1 for pixel in pixels if all(abs(a-b) < 4 for a, b in zip(pixel, rgb)))
            assert painted_color((36, 116, 164)) > 500, 'Cover must paint its fetched pixels'
            old_reads = len(image_reads)
            update('image.p2s_cover', '2026-10-18T17:30:00')
            expect(page.get_by_role('button', name='Cover preview', exact=True)).to_be_visible()
            page.wait_for_timeout(500)
            assert len(image_reads) > old_reads, 'New image state must refetch even when URL is unchanged'
            assert painted_color((164, 45, 52)) > 500, 'A fresh frame must replace the displayed pixels'
            # Lazy disposal must not resolve/download every design again on return.
            old_resolutions = len(resolutions)
            page.mouse.move(width // 2, height - 160)
            page.mouse.wheel(0, 2500); page.wait_for_timeout(700)
            page.mouse.wheel(0, -3500); page.wait_for_timeout(700)
            assert len(resolutions) == old_resolutions, (old_resolutions, len(resolutions))
            if name == 'phone':
                page.mouse.move(190, 650); page.mouse.wheel(0, 550); page.wait_for_timeout(300)
                def light_states(style):
                    calls_before = len([c for c in commands if c['type'] == 'call_service'])
                    update('light.p2s_chamber', 'on')
                    lit = page.get_by_role('img', name='Light on', exact=True)
                    expect(lit).to_be_visible()
                    expect(page.get_by_role('switch').first).to_be_checked()
                    page.evaluate('() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))')
                    lit_pixels = lit.screenshot()
                    assert painted_color((255, 200, 87) if style == 'console' else (156, 87, 0)) > 10, 'A lit bulb must use visible amber ink'
                    page.screenshot(path=f'_wasm-test/printing-light-{style}-on-phone.png')
                    update('light.p2s_chamber', 'off')
                    unlit = page.get_by_role('img', name='Light off', exact=True)
                    expect(unlit).to_be_visible()
                    expect(page.get_by_role('switch').first).not_to_be_checked()
                    expect(lit).to_have_count(0)
                    page.evaluate('() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))')
                    assert unlit.screenshot() != lit_pixels, 'On/off must change the actual glyph, not just its label'
                    page.screenshot(path=f'_wasm-test/printing-light-{style}-off-phone.png')
                    assert len([c for c in commands if c['type'] == 'call_service']) == calls_before, 'Incoming light updates must not send actions'
                    update('light.p2s_chamber', 'on')
                    expect(page.get_by_role('img', name='Light on', exact=True)).to_be_visible()

                light_states('classic')
                page.screenshot(path='_wasm-test/printing-after-readings-phone.png')
                page.get_by_role('button', name='Card style: Classic', exact=True).click(force=True, delay=100)
                page.get_by_text('Lcd', exact=True).click(force=True, delay=100)
                page.wait_for_timeout(700)
                page.screenshot(path='_wasm-test/printing-after-lcd-phone.png')
                assert painted_color((185, 195, 162)) > 1000, 'LCD fallback cards must use LCD chrome'
                # Check the value's painted lower half, where the old unavailable overlay hid it.
                for label in ('Start Unavailable', 'ETA Unknown'):
                    card_box = page.get_by_role('button', name=label, exact=True).bounding_box()
                    assert card_box and card_box['y'] >= 0 and card_box['y'] + card_box['height'] < height - 60
                    value_box = {**card_box, 'y': card_box['y'] + card_box['height'] / 2, 'height': card_box['height'] / 2}
                    value = Image.open(io.BytesIO(page.screenshot(clip=value_box))).convert('RGB')
                    pixels = value.get_flattened_data() if hasattr(value, 'get_flattened_data') else value.getdata()
                    ink = sum(1 for pixel in pixels if all(abs(a-b) < 4 for a, b in zip(pixel, (28, 36, 22))))
                    assert ink > 20, label + ' must paint full-contrast value text rather than a faded overlay'
                light_states('lcd')
                for previous, style, rgb in [('Lcd', 'Console', (47, 74, 87)), ('Console', 'Braun', (248, 246, 240))]:
                    page.get_by_role('button', name='Card style: ' + previous, exact=True).click(force=True, delay=100)
                    page.get_by_text(style, exact=True).click(force=True, delay=100)
                    page.wait_for_timeout(500)
                    assert painted_color(rgb) > 1000, style + ' fallback cards must use their selected palette'
                    light_states(style.lower())
                    page.screenshot(path=f'_wasm-test/printing-after-{style.lower()}-phone.png')
                expect(page.get_by_role('switch')).to_have_count(2)
                expect(page.get_by_role('switch').first).to_be_checked()
                calls = len([c for c in commands if c['type'] == 'call_service'])
                page.get_by_role('switch').first.click(force=True, delay=100)
                page.wait_for_timeout(200)
                changes = [c for c in commands if c['type'] == 'call_service']
                assert len(changes) == calls + 1, changes
                assert changes[-1]['domain'] == 'homeassistant' and changes[-1]['service'] == 'toggle'
                assert changes[-1]['target']['entity_id'] == 'light.p2s_chamber'
                update('light.p2s_chamber', 'off')
                expect(page.get_by_role('switch').first).not_to_be_checked()
            update('sensor.p2s_fixture_start_time', '2026-10-18T17:00:00Z')
            expect(page.get_by_role('button', name='Start Unavailable', exact=True)).to_have_count(0)
            expect(page.get_by_text('2026-10-18T17:00:00Z', exact=True)).to_have_count(0)
            expect(page.get_by_role('button', name=re.compile(r'^Start (?:[0-9]|just now|in )'))).to_have_count(1)
            assert not errors, errors
            page.close()
        browser.close()
        print('Printing dashboard browser checks passed' if not before else 'Before captures saved', flush=True)
finally: server.shutdown()
