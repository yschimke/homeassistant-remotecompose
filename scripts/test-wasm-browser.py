#!/usr/bin/env python3
"""Exercise the production Wasm app against an in-browser Home Assistant fixture."""
import http.server
import json
import os
import time
import sys
import threading
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

ROOT = Path(sys.argv[1]).resolve()
PREFIX = '/homeassistant-remotecompose/'


class Handler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        if not path.startswith(PREFIX):
            return str(ROOT / 'not-found')
        return str(ROOT / path.removeprefix(PREFIX).split('?')[0])

    def log_message(self, *args):
        pass


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
commands = []
sockets = []
subscriptions = []
entity = {'entity_id': 'switch.lamp', 'state': 'off', 'attributes': {'friendly_name': 'Lamp'}}
dashboard = {'views': [{'title': 'Home', 'cards': [{'type': 'entities', 'entities': ['switch.lamp']}]}]}


def route_socket(socket):
    sockets.append(socket)
    socket.send(json.dumps({'type': 'auth_required', 'ha_version': '2026.10.0'}))

    def receive(raw):
        message = json.loads(raw)
        kind = message['type']
        if kind == 'auth':
            assert message['access_token'] == 'browser-test-token'
            socket.send(json.dumps({'type': 'auth_ok', 'ha_version': '2026.10.0'}))
            return
        commands.append(message)
        results = {'lovelace/dashboards/list': [], 'get_states': [entity], 'lovelace/config': dashboard,
                   'subscribe_events': None, 'unsubscribe_events': None, 'call_service': {}}
        assert kind in results, f'Unexpected Home Assistant command: {kind}'
        if kind == 'subscribe_events':
            assert message['event_type'] == 'state_changed'
            subscriptions.append(message['id'])
        socket.send(json.dumps({'id': message['id'], 'type': 'result', 'success': True, 'result': results[kind]}))

    socket.on_message(receive)


def state_changed(state):
    sockets[-1].send(json.dumps({'id': subscriptions[-1], 'type': 'event', 'event': {
        'event_type': 'state_changed', 'data': {'entity_id': 'switch.lamp',
        'new_state': {**entity, 'state': state}}}}))


try:
    with sync_playwright() as p:
        executable = os.environ.get('CHROMIUM_PATH')
        browser = p.chromium.launch(executable_path=executable, args=['--no-sandbox'])
        page = browser.new_page(viewport={'width': 1200, 'height': 800})
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.route_web_socket('wss://ha.example.test/api/websocket', route_socket)
        page.goto(f'http://127.0.0.1:{server.server_port}{PREFIX}', wait_until='networkidle')
        expect(page.get_by_role('button', name='Connect', exact=True)).to_be_disabled()
        for label, text in [('Home Assistant URL', 'https://ha.example.test'),
                            ('Long-lived access token', 'browser-test-token')]:
            page.get_by_role('textbox', name=label, exact=True).click(force=True)
            # Let the canvas focus change reach Compose before inserting text.
            page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
            page.keyboard.insert_text(text)
        expect(page.get_by_role('button', name='Connect', exact=True)).to_be_enabled()
        page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
        page.get_by_role('button', name='Connect', exact=True).click(force=True)
        toggle = page.get_by_role('switch')
        expect(toggle).not_to_be_checked(timeout=15000)
        expect(page.get_by_role('list')).to_contain_text('Lamp')
        state_changed('on')
        expect(toggle).to_be_checked()
        state_changed('off')
        expect(toggle).not_to_be_checked()
        toggle.click(force=True)
        page.wait_for_timeout(500)
        calls = [command for command in commands if command['type'] == 'call_service']
        assert len(calls) == 1, f'One click dispatched {len(calls)} service calls'
        assert calls[0]['domain'] == 'switch' and calls[0]['service'] == 'toggle'
        assert calls[0]['target']['entity_id'] == 'switch.lamp'
        page.wait_for_timeout(4500)
        assert len([command for command in commands if command['type'] == 'get_states']) == 1
        assert len(subscriptions) == 1
        sockets[-1].close(code=1011, reason='Fixture reconnect')
        deadline = time.monotonic() + 15
        while len(subscriptions) < 2 and time.monotonic() < deadline:
            page.wait_for_timeout(100)
        assert len(subscriptions) == 2 and len(sockets) == 2
        state_changed('on')
        expect(toggle).to_be_checked()
        Path('_wasm-test').mkdir(exist_ok=True)
        page.screenshot(path='_wasm-test/homeassistant-live.png')
        page.get_by_role('tab', name='Settings').dispatch_event('click')
        page.get_by_role('button', name='Sign out').dispatch_event('click')
        expect(page.get_by_role('button', name='Connect', exact=True)).to_be_disabled()
        assert not page.evaluate('Object.keys(localStorage).length || Object.keys(sessionStorage).length')
        assert not errors, errors
        browser.close()
        print('Wasm browser: project path, login, live on/off updates, single action, no polling, reconnect and sign-out passed')
finally:
    server.shutdown()
