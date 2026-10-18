#!/usr/bin/env python3
"""Exercise the production Wasm app against an in-browser Home Assistant fixture."""
import http.server
import json
import os
import time
import sys
import threading
from urllib.parse import parse_qs, urlparse
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

ROOT = Path(sys.argv[1]).resolve()
PREFIX = '/homeassistant-remotecompose/'


def wait_for_compose_frame(page):
    # Semantics can update before the canvas and its pointer handlers. Coordinate
    # clicks must wait for the rendered frame, especially when reopening a dialog.
    page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')


def open_feedback_review(page):
    page.get_by_role('button', name='Report a bug', exact=True).dispatch_event('click')
    expect(page.get_by_text('Report a PUBLIC issue', exact=True)).to_be_visible()
    expect(page.get_by_role('button', name='Review on GitHub', exact=True)).to_be_visible()
    wait_for_compose_frame(page)


class Handler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        if path == '/terrazzo-static/terrazzo-panel.js':
            return str(Path('custom_components/terrazzo/frontend/terrazzo-panel.js').resolve())
        if path.startswith('/terrazzo-static/wasm/'):
            return str(ROOT / path.removeprefix('/terrazzo-static/wasm/').split('?')[0])
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
                   'subscribe_events': None, 'unsubscribe_events': None, 'call_service': {},
                   'config/entity_registry/list_for_display': {'entity_categories': {}, 'entities': [
                       {'ei': 'switch.lamp', 'pl': 'demo', 'di': 'lamp-device'}]},
                   'config/device_registry/list': [{'id': 'lamp-device', 'manufacturer': 'Demo', 'model': 'Lamp'}]}
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
        page.route('https://ha.coo.ee/**', lambda route: route.fulfill(status=404, headers={'Access-Control-Allow-Origin': '*'}))
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
            page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
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
        page.context.route('https://ha.example.test/lovelace**', lambda route: route.fulfill(
            content_type='text/html', body='<p>Home Assistant entity details fixture</p>'))
        with page.expect_popup() as opened:
            toggle.click(button='right', force=True, delay=100)
        details = opened.value
        expect(details).to_have_url('https://ha.example.test/lovelace?more-info-entity-id=switch.lamp')
        assert not [command for command in commands if command['type'] == 'call_service']
        details.close()
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
        # Bug reports open a reviewable public draft, never an API submission.
        expect(page.get_by_role('button', name='Report a bug', exact=True)).to_be_visible()
        page.screenshot(path='_wasm-test/feedback-settings.png')
        open_feedback_review(page)
        expect(page.get_by_text('Report a PUBLIC issue', exact=True)).to_be_visible()
        expect(page.get_by_text('This issue and anything you include will be PUBLIC', exact=False)).to_be_visible()
        expect(page.get_by_text('Included: app version', exact=False)).to_contain_text('Web (Wasm)')
        page.screenshot(path='_wasm-test/feedback-public-warning.png')
        page.get_by_role('button', name='Cancel', exact=True).dispatch_event('click')
        expect(page.get_by_text('Report a PUBLIC issue', exact=True)).not_to_be_visible()
        assert len(page.context.pages) == 1
        open_feedback_review(page)
        github_requests = []

        def github_draft(route):
            github_requests.append(route.request)
            route.fulfill(content_type='text/html', body='<p>Editable GitHub draft fixture</p>')

        page.context.route('https://github.com/yschimke/homeassistant-remotecompose/issues/new**', github_draft)
        with page.expect_popup() as opened:
            page.get_by_role('button', name='Review on GitHub', exact=True).click(force=True)
        draft = opened.value
        draft.wait_for_url('https://github.com/**')
        parsed = urlparse(draft.url)
        assert parsed.path == '/yschimke/homeassistant-remotecompose/issues/new'
        query = parse_qs(parsed.query)
        assert set(query) == {'body'}
        body = query['body'][0]
        assert 'App version:' in body and 'Platform: Web (Wasm)' in body
        assert 'Steps to reproduce (optional)' in body and 'Expected behavior' in body and 'Actual behavior' in body
        assert 'PUBLIC' in body and 'credentials' in body
        for private in ['browser-test-token', 'ha.example.test', 'switch.lamp', 'Lamp', 'lamp-device']:
            assert private not in draft.url and private not in body
        assert draft.evaluate('window.opener === null')
        assert len(github_requests) == 1
        assert not github_requests[0].headers.get('referer')
        draft.close()
        page.bring_to_front()
        expect(page.get_by_text('Report a PUBLIC issue', exact=True)).not_to_be_visible()
        wait_for_compose_frame(page)
        # Popup blocking keeps the warning and a selectable manual recovery URL visible.
        page.evaluate('''() => {
            window.feedbackOriginalOpen = window.open;
            window.feedbackBlockedOpenCount = 0;
            window.open = () => { window.feedbackBlockedOpenCount++; return null; };
        }''')
        open_feedback_review(page)
        page.get_by_role('button', name='Review on GitHub', exact=True).click(force=True)
        page.wait_for_function('window.feedbackBlockedOpenCount === 1')
        expect(page.get_by_text('Could not open GitHub.', exact=False)).to_be_visible()
        expect(page.get_by_text('https://github.com/yschimke/homeassistant-remotecompose/issues', exact=True)).to_be_visible()
        expect(page.get_by_text('This issue and anything you include will be PUBLIC', exact=False)).to_be_visible()
        assert len(page.context.pages) == 1
        assert len(github_requests) == 1
        wait_for_compose_frame(page)
        page.screenshot(path='_wasm-test/feedback-popup-blocked.png')
        page.evaluate('''() => {
            window.open = window.feedbackOriginalOpen;
            delete window.feedbackOriginalOpen;
            delete window.feedbackBlockedOpenCount;
        }''')
        with page.expect_popup() as retried:
            page.get_by_role('button', name='Try GitHub again', exact=True).click(force=True)
        retried.value.wait_for_url('https://github.com/**')
        assert retried.value.url == draft.url
        retried.value.close()
        expect(page.get_by_text('Report a PUBLIC issue', exact=True)).not_to_be_visible()
        page.get_by_role('button', name='Sign out').dispatch_event('click')
        expect(page.get_by_role('button', name='Connect', exact=True)).to_be_disabled()
        page.set_viewport_size({'width': 360, 'height': 800})
        open_feedback_review(page)
        expect(page.get_by_text('Report a PUBLIC issue', exact=True)).to_be_visible()
        expect(page.get_by_role('button', name='Review on GitHub', exact=True)).to_be_visible()
        page.screenshot(path='_wasm-test/feedback-small-screen.png')
        page.get_by_role('button', name='Cancel', exact=True).dispatch_event('click')
        assert not page.evaluate('Object.keys(localStorage).length || Object.keys(sessionStorage).length')
        assert not errors, errors
        # Embedded HA uses its native dialog capability rather than opening another tab.
        hosted = browser.new_page(viewport={'width': 1200, 'height': 800})
        hosted.route('https://ha.coo.ee/**', lambda route: route.fulfill(status=404, headers={'Access-Control-Allow-Origin': '*'}))
        hosted.route_web_socket(f'ws://127.0.0.1:{server.server_port}/api/websocket', route_socket)
        hosted.route(f'http://127.0.0.1:{server.server_port}/hosted', lambda route: route.fulfill(
            content_type='text/html', body="""<script type="module">
            import '/terrazzo-static/terrazzo-panel.js';
            window.moreInfo = [];
            const panel = document.createElement('terrazzo-panel');
            panel.hass = {auth: {expired: false, accessToken: 'browser-test-token'}};
            panel.panel = {config: {dashboard: ''}};
            panel.addEventListener('hass-more-info', event => window.moreInfo.push(event.detail));
            document.body.append(panel);
            </script>"""))
        hosted.goto(f'http://127.0.0.1:{server.server_port}/hosted')
        hosted_frame = hosted.frame_locator('terrazzo-panel iframe')
        # An unconfigured panel now starts at main's Lovelace dashboard picker.
        dashboard_choice = hosted_frame.get_by_text('Overview (Lovelace)', exact=True)
        expect(dashboard_choice).to_be_visible(timeout=30000)
        dashboard_choice.click(force=True, delay=100)
        hosted_toggle = hosted_frame.get_by_role('switch')
        expect(hosted_toggle).to_be_visible(timeout=30000)
        count = len([command for command in commands if command['type'] == 'call_service'])
        hosted_toggle.scroll_into_view_if_needed()
        # Wait for the child canvas layout and pointer handlers, not just its semantics DOM.
        hosted.locator('terrazzo-panel iframe').evaluate("""frame => new Promise(resolve =>
            frame.contentWindow.requestAnimationFrame(() => frame.contentWindow.requestAnimationFrame(resolve)))""")
        hosted_toggle.click(button='right', force=True, delay=100)
        hosted.wait_for_function('window.moreInfo.length === 1')
        assert hosted.evaluate('window.moreInfo') == [{'entityId': 'switch.lamp'}]
        assert len([command for command in commands if command['type'] == 'call_service']) == count
        hosted.close()
        browser.close()
        print('Wasm browser: project path, login, live on/off updates, single action, right-click HA deep link/native panel dispatch, no polling, reconnect, public feedback review/popup recovery and sign-out passed')
finally:
    server.shutdown()
