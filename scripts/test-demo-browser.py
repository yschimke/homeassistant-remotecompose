#!/usr/bin/env python3
"""Exercise credential-free demo routes, inline designs and offline fallback at two sizes."""
import datetime
import hashlib
import http.server
import json
import os
from pathlib import Path
import sys
import threading
import time

from playwright.sync_api import expect, sync_playwright

root = Path(sys.argv[1]).resolve()
templates = {style: Path(f'scripts/fixtures/demo/tile-{style}.rc').read_bytes() for style in ('classic', 'console')}
blobs = {hashlib.sha256(data).hexdigest(): data for data in templates.values()}


class Handler(http.server.SimpleHTTPRequestHandler):
    live = False
    temperature = '21.5'
    gateway_requests = []

    def do_GET(self):
        if self.path.startswith('/demo-ha/'):
            Handler.gateway_requests.append(self.path)
            self.send_response(200 if Handler.live else 503)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            if self.path == '/demo-ha/dashboard':
                payload = {'title': 'Public demo', 'views': [{'title': 'Home', 'cards': [
                    {'type': 'entities', 'title': 'Live comfort', 'entities': ['sensor.demo_temperature', 'sensor.demo_last_seen']}
                ]}]}
            else:
                payload = [{'entity_id': 'sensor.demo_temperature', 'state': Handler.temperature,
                            'attributes': {'friendly_name': 'Demo temperature', 'unit_of_measurement': '°C'},
                            'last_changed': '2026-10-17T12:00:00Z', 'last_updated': '2026-10-17T12:01:00Z'},
                           {'entity_id': 'sensor.demo_last_seen', 'state': '2026-10-17T12:01:00Z',
                            'attributes': {'friendly_name': 'Last seen', 'device_class': 'timestamp'}}]
            self.wfile.write(json.dumps(payload).encode())
            return
        super().do_GET()

    def translate_path(self, path):
        name = path.split('?')[0]
        if name in ('/demo', '/demo/'):
            return str(root / 'index.html')
        return str(root / name.removeprefix('/demo/'))

    def log_message(self, *args):
        pass


def capture(page, path):
    # Compose draws on canvas; wait for text and menu animations to finish.
    previous, unchanged = None, 0
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        current = page.screenshot()
        unchanged = unchanged + 1 if current == previous else 0
        if unchanged == 3:
            Path(path).write_bytes(current)
            return
        previous = current
        page.wait_for_timeout(100)
    raise AssertionError(f'Canvas did not settle before capturing {path}')


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
Path('_wasm-test').mkdir(exist_ok=True)
target_errors = []
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH'), args=['--no-sandbox', '--disable-gpu'])
        for name, width, height in [('mobile', 390, 844), ('landscape', 844, 390), ('small', 360, 740), ('wall', 1440, 900)]:
            print(f'Demo viewport: {name}', flush=True)
            page = browser.new_page(viewport={'width': width, 'height': height}, device_scale_factor=1)
            errors, sockets, requests = [], [], []
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.on('websocket', lambda socket: sockets.append(socket.url))

            def resolve(route):
                request = json.loads(route.request.post_data)
                requests.append(request)
                # A real template, with host bindings, for the fixture's light cards only.
                if request['card'].get('entity', '').startswith('light.'):
                    sha = hashlib.sha256(templates.get(request['cardStyle'], templates['classic'])).hexdigest()
                    route.fulfill(json={'sha256': sha}, headers={'Access-Control-Allow-Origin': '*'})
                else:
                    route.fulfill(status=404, headers={'Access-Control-Allow-Origin': '*'})

            page.route('https://ha.coo.ee/api/cards/resolve', resolve)
            page.route('https://ha.coo.ee/designs/v1/blobs/*.rc', lambda route: route.fulfill(
                body=blobs[route.request.url.rsplit('/', 1)[-1].removesuffix('.rc')], content_type='application/octet-stream', headers={'Access-Control-Allow-Origin': '*'}))
            page.goto(f'http://127.0.0.1:{server.server_port}/demo/?data=fake', wait_until='networkidle')
            expect(page.get_by_text('Fake · read only', exact=True)).to_be_visible(timeout=30000)
            page.get_by_text('Rooms', exact=True).click(force=True)
            if os.environ.get('DEMO_DEBUG'):
                print(page.locator('body').inner_text())
                capture(page, f'_wasm-test/demo-{name}-debug.png')
            expect(page.get_by_text('21.5 °C')).to_be_visible()
            page.get_by_text('Home', exact=True).click(force=True)
            expect(page.get_by_text('Living spaces', exact=True)).to_be_visible()
            page.wait_for_load_state('networkidle')
            # The built-in light fallback has switches; the real RC tile has its own content.
            expect(page.get_by_role('switch')).to_have_count(0)
            expect(page.get_by_role('textbox', name='Long-lived access token')).to_have_count(0)
            capture(page, f'_wasm-test/demo-{name}-classic.png')
            # The title, style selector and options occupy the same single app bar.
            title = page.get_by_text('Demo home', exact=True).bounding_box()
            style = page.get_by_role('button', name='Card style: Classic').bounding_box()
            options = page.get_by_role('button', name='Demo options').bounding_box()
            assert title and style and options, (title, style, options)
            assert title['y'] < 64 and style['y'] < 64 and options['y'] < 64
            assert options['x'] + options['width'] <= width
            if style['height'] < 48 or options['height'] < 48:
                target_errors.append((name, style, options))
            heading = page.get_by_text('Living spaces', exact=True).bounding_box()
            assert heading and heading['y'] < 160, heading
            page.get_by_role('button', name='Card style: Classic').click(force=True)
            page.get_by_text('Braun', exact=True).click(force=True)
            expect(page.get_by_text('Console', exact=True)).not_to_be_attached()
            expect(page.get_by_role('button', name='Card style: Braun')).to_be_visible()
            page.get_by_role('button', name='Card style: Braun').click(force=True)
            page.get_by_text('LCD', exact=True).click(force=True)
            expect(page.get_by_text('Console', exact=True)).not_to_be_attached()
            page.get_by_role('button', name='Card style: LCD').click(force=True)
            capture(page, f'_wasm-test/demo-{name}-styles.png')
            page.get_by_text('Console', exact=True).click(force=True)
            expect(page.get_by_text('Braun', exact=True)).not_to_be_attached()
            deadline = time.monotonic() + 5
            while not any(r['cardStyle'] == 'console' for r in requests) and time.monotonic() < deadline:
                page.wait_for_timeout(100)
            page.wait_for_timeout(500)
            assert any(r['cardStyle'] == 'console' for r in requests)
            page.get_by_role('button', name='Demo options').click(force=True)
            page.get_by_text('Use dark theme', exact=True).click(force=True)
            expect(page.get_by_text('Fake data', exact=True)).not_to_be_attached()
            capture(page, f'_wasm-test/demo-{name}-theme.png')
            page.get_by_role('button', name='Demo options').click(force=True)
            expect(page.get_by_text('Use light theme', exact=True)).to_be_visible()
            capture(page, f'_wasm-test/demo-{name}-options.png')
            page.keyboard.press('Escape')
            expect(page.get_by_text('Use light theme', exact=True)).not_to_be_attached()
            capture(page, f'_wasm-test/demo-{name}-console.png')
            assert all('states' not in r for r in requests)
            assert not sockets, sockets
            assert not errors, errors
            page.close()

        page = browser.new_page(viewport={'width': 390, 'height': 844})
        offline_errors = []
        page.on('pageerror', lambda error: offline_errors.append(str(error)))
        page.route('https://ha.coo.ee/**', lambda route: route.abort())
        page.goto(f'http://127.0.0.1:{server.server_port}/demo/', wait_until='networkidle')
        page.get_by_text('Rooms', exact=True).click(force=True)
        expect(page.get_by_text('21.5 °C')).to_be_visible(timeout=30000)
        for switch in page.get_by_role('switch').all():
            expect(switch).to_be_disabled()
        page.get_by_role('button', name='Demo options').click(force=True)
        page.get_by_text('Use dark theme', exact=True).click(force=True)
        expect(page.get_by_text('Fake data', exact=True)).not_to_be_attached()
        capture(page, '_wasm-test/demo-offline-theme.png')
        page.get_by_role('button', name='Demo options').click(force=True)
        expect(page.get_by_text('Use light theme', exact=True)).to_be_visible()
        page.keyboard.press('Escape')
        assert not offline_errors, offline_errors
        capture(page, '_wasm-test/demo-mobile-offline.png')
        # Real WASM/Ktor HTTP reads, refresh, stale-data removal and explicit fake-mode isolation.
        page.close()
        Handler.live = True
        Handler.gateway_requests.clear()
        page = browser.new_page(viewport={'width': 1440, 'height': 900})
        page.clock.install(time=datetime.datetime(2026, 10, 17, 12, 2, tzinfo=datetime.timezone.utc))
        live_errors = []
        page.on('pageerror', lambda error: live_errors.append(str(error)))
        page.route('https://ha.coo.ee/**', lambda route: route.abort())
        page.goto(f'http://127.0.0.1:{server.server_port}/demo/', wait_until='networkidle')
        expect(page.get_by_text('Live · read only', exact=True)).to_be_visible(timeout=30000)
        expect(page.get_by_text('21.5 °C')).to_be_visible()
        expect(page.get_by_text('1 min ago')).to_be_visible()
        page.clock.fast_forward(60000)
        expect(page.get_by_text('2 min ago')).to_be_visible()
        Handler.temperature = '22.8'
        expect(page.get_by_text('22.8 °C')).to_be_visible(timeout=10000)
        capture(page, '_wasm-test/demo-wall-live.png')
        Handler.live = False
        expect(page.get_by_text('Offline · fake data', exact=True)).to_be_visible(timeout=10000)
        page.get_by_role('button', name='Demo options').click(force=True)
        expect(page.get_by_text('Live demo unavailable · using fake data · retrying', exact=True)).to_be_visible()
        expect(page.get_by_text('Live comfort', exact=True)).to_have_count(0)
        capture(page, '_wasm-test/demo-wall-live-fallback.png')
        page.get_by_text('Fake data', exact=True).click(force=True)
        expect(page.get_by_text('Live when available', exact=True)).not_to_be_attached()
        count = len(Handler.gateway_requests)
        page.wait_for_timeout(6000)
        assert len(Handler.gateway_requests) == count, Handler.gateway_requests
        Handler.live = True
        page.get_by_role('button', name='Demo options').click(force=True)
        page.get_by_text('Live when available', exact=True).click(force=True)
        expect(page.get_by_text('Live · read only', exact=True)).to_be_visible(timeout=10000)
        assert not live_errors, live_errors
        assert not target_errors, target_errors
        browser.close()
        print('Demo: mobile/wall layouts, real RC playback, inline style/theme, credential-free, offline fallback, live refresh, outage recovery and fake-mode isolation passed')
finally:
    server.shutdown()
