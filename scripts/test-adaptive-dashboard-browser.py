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

# A design blob, with or without the app's `?client=` release tag (template usage, phase 1).
DESIGN_BLOB = re.compile(r'^https://ha\.coo\.ee/designs/v1/blobs/[^/?]+\.rc(\?.*)?$')

root = Path(sys.argv[1]).resolve()
before = '--before' in sys.argv
heading = lambda name: {'type': 'heading', 'heading': name}
tile = lambda entity, name: {'type': 'tile', 'entity': entity, 'name': name}
grid = lambda cards: {'type': 'grid', 'columns': 4, 'cards': cards}
conversation = 'binary_sensor.meshcore_radio_ch_0_messages'
dashboard = {'title': 'Mesh network', 'views': [{'title': 'Overview', 'type': 'sections', 'max_columns': 4,
    'header': {'card': {'type': 'markdown', 'content': '# Mesh network\n**Hub:** {{ states("sensor.meshcore_radio_node_status_hub") }}'}},
    'sections': [{'column_span': 4, 'cards': [
        heading('Hub health'), grid([tile('sensor.meshcore_radio_node_status_hub', 'Radio'), tile('sensor.nodes', 'Nodes'),
            tile('sensor.rssi', 'Signal'), {'type': 'tile', 'entity': 'light.room', 'name': 'Room lamp', 'tap_action': {'action': 'toggle'}}]),
        heading('Power station'), {'type': 'gauge', 'entity': 'sensor.battery', 'name': 'Battery reserve', 'min': 0, 'max': 100},
        grid([tile('sensor.voltage', 'Voltage'), tile('sensor.temperature', 'Temperature')]),
        heading('Public conversation'), {'type': 'tile', 'entity': conversation, 'name': 'Public channel'},
        heading('Contacts'), grid([tile('sensor.contact_a', 'Room node'), tile('sensor.contact_b', 'Solar relay')]),
        heading('Operations'), {'type': 'markdown', 'content': '## Network notes\n**Route:** {{ states("sensor.route") }}\nAll local controls remain in Home Assistant.'},
        {'type': 'entities', 'title': 'Compose a message', 'entities': ['select.meshcore_recipient', 'text.meshcore_message']},
        {'type': 'button', 'name': 'Send configured message', 'tap_action': {'action': 'perform-action', 'perform_action': 'meshcore.send_ui_message', 'data': {'entry_id': 'fixture-companion'}}},
    ]}]}]}
values = [('sensor.meshcore_radio_node_status_hub', 'Connected', None), ('sensor.nodes', '12', None),
          ('sensor.rssi', '-68', 'dBm'), ('light.room', 'off', None), ('sensor.battery', '78', '%'),
          ('sensor.voltage', '4.1', 'V'), ('sensor.temperature', '22', '°C'), ('sensor.contact_a', 'Online', None),
          ('sensor.contact_b', 'Online', None), ('sensor.route', 'Direct', None)]
states = [{'entity_id': entity, 'state': value, 'attributes': {'friendly_name': entity.split('.')[1],
    **({'unit_of_measurement': unit} if unit else {})}} for entity, value, unit in values]
states += [{'entity_id': conversation, 'state': 'Active', 'attributes': {'channel_index': '0', 'friendly_name': 'Public channel'}}]
rows = [{'entity_id': conversation, 'when': 1792317600, 'message': '<public> Operator: Can someone check the room sensor?'},
        {'entity_id': conversation, 'when': 1792317660, 'message': '<public> Room node: Checking it now — battery is 78%.'}]
states += [{'entity_id': 'select.meshcore_recipient', 'state': 'Public', 'attributes': {'friendly_name': 'Recipient', 'options': ['Public', 'Private room']}},
           {'entity_id': 'text.meshcore_message', 'state': 'Ready', 'attributes': {'friendly_name': 'Message helper'}}]
commands, history_reads, errors = [], [], []
reject_send = False
latest_socket = None
latest_subscription = None

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
        result = {'get_states': states, 'lovelace/dashboards/list': [{'url_path': 'mesh', 'title': 'Mesh network'}],
                  'lovelace/config': dashboard, 'subscribe_events': None, 'config/entity_registry/list_for_display': {'entities': []},
                  'config/device_registry/list': [], 'call_service': {},
                  'get_config': {'unit_system': {'temperature': '°C', 'length': 'km', 'mass': 'kg', 'volume': 'L'}}}
        if kind == 'logbook/get_events':
            assert msg['entity_ids'] == [conversation]
            assert 'start_time' in msg
            history_reads.append(msg)
            payload = rows
        else:
            assert kind in result, kind
            payload = result[kind]
        if kind == 'call_service' and reject_send:
            socket.send(json.dumps({'id': msg['id'], 'type': 'result', 'success': False, 'error': {'message': 'Fixture refusal'}})); return
        socket.send(json.dumps({'id': msg['id'], 'type': 'result', 'success': True, 'result': payload}))
    socket.on_message(receive)

Path('_wasm-test').mkdir(exist_ok=True)
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH'), args=['--no-sandbox', '--disable-gpu'])
        for name, width, height in [('wall', 1440, 900), ('phone', 390, 844)]:
            print('Adaptive instance:', name, 'before' if before else 'after', flush=True)
            page = browser.new_page(viewport={'width': width, 'height': height}, device_scale_factor=1)
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.route_web_socket('wss://ha.example.test/api/websocket', socket_route)
            gauge = Path('scripts/fixtures/demo/gauge-console.rc').read_bytes()
            sha = hashlib.sha256(gauge).hexdigest()
            def resolve(route):
                data = json.loads(route.request.post_data)
                assert 'states' not in data and 'fixture-token' not in route.request.post_data
                assert not any(value in route.request.post_data for value in ['meshcore_radio', 'Room node', 'sensor.battery'])
                if data['card']['type'] == 'gauge' and data['cardStyle'] == 'console':
                    route.fulfill(json={'sha256': sha, 'exact': True}, headers={'Access-Control-Allow-Origin': '*'})
                else: route.fulfill(status=404, headers={'Access-Control-Allow-Origin': '*'})
            page.route('https://ha.coo.ee/api/cards/resolve', resolve)
            page.route(DESIGN_BLOB, lambda route: route.fulfill(body=gauge, content_type='application/octet-stream', headers={'Access-Control-Allow-Origin': '*'}))
            page.goto(f'http://127.0.0.1:{server.server_port}/', wait_until='networkidle')
            for label, value in [('Home Assistant URL', 'https://ha.example.test'), ('Long-lived access token', 'fixture-token')]:
                page.get_by_role('textbox', name=label, exact=True).click(force=True, delay=100)
                page.evaluate('() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))')
                page.keyboard.insert_text(value)
                page.evaluate('() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))')
            expect(page.get_by_role('button', name='Connect', exact=True)).to_be_enabled()
            page.get_by_role('button', name='Connect', exact=True).click(force=True, delay=100)
            expect(page.get_by_text('Hub health', exact=True)).to_be_visible(timeout=30000)
            if before:
                page.wait_for_timeout(800)
                page.screenshot(path=f'_wasm-test/adaptive-instance-before-{name}.png')
                page.close(); continue
            expect(page.get_by_role('button', name='Design or fix this card')).to_have_count(0)
            expect(page.get_by_role('button', name='Radio Connected', exact=True)).to_be_visible()
            expect(page.get_by_text('Hub: Connected', exact=True)).to_be_visible()
            expect(page.get_by_text(re.compile(r'\{\{|\{%'))).to_have_count(0)
            if name == 'wall':
                page.get_by_role('button', name='Customize', exact=True).click(force=True, delay=100)
                expect(page.get_by_role('button', name='Design or fix this card').first).to_be_visible()
                page.get_by_role('button', name='Done customizing', exact=True).click(force=True, delay=100)
                expect(page.get_by_role('button', name='Design or fix this card')).to_have_count(0)
                settled_capture(page, '_wasm-test/adaptive-instance-normal-mode-wall.png')
            expect(page.get_by_text('Heading', exact=True)).to_have_count(0)
            if name == 'wall':
                bounds = [page.get_by_text(title, exact=True).bounding_box() for title in ['Hub health', 'Power station', 'Public conversation']]
                assert all(bounds), bounds
                assert len({round(box['x']) for box in bounds}) == 3, bounds
                assert max(box['y'] for box in bounds) - min(box['y'] for box in bounds) < 8, bounds
                # A short cluster packs below Hub health, alongside the taller conversation.
                contact = page.get_by_text('Contacts', exact=True).bounding_box()
                assert contact and contact['y'] < 700, contact
                expect(page.get_by_text('Can someone check the room sensor?', exact=True)).to_be_visible()
                expect(page.get_by_text('Checking it now — battery is 78%.', exact=True)).to_be_visible()
                page.screenshot(path='_wasm-test/adaptive-instance-after-wall.png')
                message = page.get_by_role('textbox', name=re.compile(r'^Message'))
                message.click(force=True, delay=100)
                page.keyboard.insert_text('Confirm room temperature')
                calls = len([c for c in commands if c['type'] == 'call_service'])
                page.get_by_role('button', name='Send message', exact=True).click(force=True, delay=100)
                expect(page.get_by_text('Accepted by Home Assistant · delivery unconfirmed', exact=True)).to_be_visible()
                sent = [c for c in commands if c['type'] == 'call_service'][calls:]
                assert len(sent) == 1 and sent[0]['domain'] == 'meshcore' and sent[0]['service'] == 'send_channel_message', sent
                assert sent[0]['service_data'] == {'channel_idx': 0, 'message': 'Confirm room temperature'}, sent
                assert sent[0].get('target') is None
                reject_send = True
                message.click(force=True, delay=100)
                page.keyboard.insert_text('Keep this draft')
                page.get_by_role('button', name='Send message', exact=True).click(force=True, delay=100)
                expect(page.get_by_text('Could not send · your draft is kept', exact=True)).to_be_visible()
                expect(message).to_contain_text('Keep this draft')
                reject_send = False
                rows.append({'entity_id': conversation, 'when': 1792317720, 'message': '<public> Room node: Temperature is 22°C.'})
                expect(page.get_by_text('Temperature is 22°C.', exact=True)).to_be_visible(timeout=20000)
                page.get_by_role('button', name='Card style: Classic', exact=True).click(force=True, delay=100)
                page.get_by_text('Console', exact=True).click(force=True, delay=100)
                expect(page.get_by_role('button', name='78 % Battery reserve 0 – 100 %', exact=True).filter(visible=True).first).to_be_visible(timeout=20000)
                settled_capture(page, '_wasm-test/adaptive-instance-console-wall.png')
                page.mouse.move(650, 700); page.mouse.wheel(0, 550)
                settled_capture(page, '_wasm-test/adaptive-instance-helpers-wall.png')
                choose = page.get_by_role('button', name='Choose', exact=True)
                expect(choose).to_be_in_viewport(ratio=1)
                choose.click(force=True, delay=100)
                page.get_by_text('Private room', exact=True).click(force=True, delay=100)
                page.wait_for_timeout(150)
                assert [c for c in commands if c['type'] == 'call_service'][-1]['service_data'] == {'entity_id': 'select.meshcore_recipient', 'option': 'Private room'}
                page.get_by_role('button', name='Edit', exact=True).click(force=True, delay=100)
                value = page.get_by_role('textbox', name='Value', exact=True)
                value.click(force=True, delay=100)
                page.keyboard.press('ControlOrMeta+A'); page.keyboard.insert_text('Message from helper')
                latest_socket.send(json.dumps({'id': latest_subscription, 'type': 'event', 'event': {'event_type': 'state_changed', 'data': {'entity_id': 'text.meshcore_message', 'new_state': {'entity_id': 'text.meshcore_message', 'state': 'Changed elsewhere', 'attributes': {'friendly_name': 'Message helper'}}}}}))
                expect(page.get_by_role('list')).to_contain_text('Message helper Changed elsewhere')
                expect(value).to_contain_text('Message from helper')
                page.get_by_role('button', name='Save', exact=True).click(force=True, delay=100)
                page.wait_for_timeout(150)
                assert [c for c in commands if c['type'] == 'call_service'][-1]['service_data'] == {'entity_id': 'text.meshcore_message', 'value': 'Message from helper'}
                page.get_by_role('button', name='Run action', exact=True).click(force=True, delay=100)
                page.wait_for_timeout(150)
                action = [c for c in commands if c['type'] == 'call_service'][-1]
                assert action['domain'] == 'meshcore' and action['service'] == 'send_ui_message' and action['service_data'] == {'entry_id': 'fixture-companion'}, action

            else:
                page.screenshot(path='_wasm-test/adaptive-instance-after-phone.png')
                page.mouse.move(190, 700); page.mouse.wheel(0, 600)
                expect(page.get_by_text('Public channel', exact=True)).to_be_visible(timeout=10000)
                page.screenshot(path='_wasm-test/adaptive-instance-chat-phone.png')
            assert not errors, errors
            page.close()
        browser.close()
        if not before: assert history_reads, 'Chat must read HA logbook, never the sensor activity flag'
        print('Adaptive dashboard: section clustering, compact nested metrics, live real-logbook chat, exact single sends and refusal recovery passed' if not before else 'Before captures saved', flush=True)
finally: server.shutdown()
