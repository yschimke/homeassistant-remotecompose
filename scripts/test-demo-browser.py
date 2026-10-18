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
    lamp = 'off'
    reject_controls = False
    mutations = []
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
                    {'type': 'entities', 'title': 'Live comfort', 'entities': ['light.demo_lamp', 'sensor.demo_temperature', 'sensor.demo_last_seen']}
                ]}]}
            else:
                payload = [{'entity_id': 'light.demo_lamp', 'state': Handler.lamp, 'attributes': {'friendly_name': 'Demo lamp'}},
                           {'entity_id': 'sensor.demo_temperature', 'state': Handler.temperature,
                            'attributes': {'friendly_name': 'Demo temperature', 'unit_of_measurement': '°C'},
                            'last_changed': '2026-10-17T12:00:00Z', 'last_updated': '2026-10-17T12:01:00Z'},
                           {'entity_id': 'sensor.demo_last_seen', 'state': '2026-10-17T12:01:00Z',
                            'attributes': {'friendly_name': 'Last seen', 'device_class': 'timestamp'}}]
            self.wfile.write(json.dumps(payload).encode())
            return
        super().do_GET()

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        Handler.mutations.append((self.path, data))
        assert self.path == '/demo-ha/api/services/light/toggle'
        assert data == {'entity_id': 'light.demo_lamp'}
        if Handler.live and not Handler.reject_controls:
            Handler.lamp = 'on' if Handler.lamp == 'off' else 'off'
        self.send_response(200 if Handler.live and not Handler.reject_controls else 503)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(b'[]')

    def translate_path(self, path):
        name = path.split('?')[0]
        if name in ('/demo', '/demo/'):
            return str(root / 'index.html')
        return str(root / name.removeprefix('/demo/'))

    def log_message(self, *args):
        pass


def click_switch(switch):
    # Compose's canvas handles pointers; its accessibility DOM sits underneath it.
    # Wait for the current layout before sending a real press/release through the canvas.
    switch.wait_for(state='visible')
    switch.scroll_into_view_if_needed()
    switch.click(force=True, delay=100)


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
Path('_wasm-test').mkdir(exist_ok=True)
try:
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH'), args=['--no-sandbox'])
        for name, width, height in [('mobile', 390, 844), ('wall', 1440, 900)]:
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
            expect(page.get_by_text('Example home · fake data · try the switches', exact=True)).to_be_visible(timeout=30000)
            page.get_by_text('Rooms', exact=True).click(force=True)
            if os.environ.get('DEMO_DEBUG'):
                print(page.locator('body').inner_text())
                page.screenshot(path=f'_wasm-test/demo-{name}-debug.png')
            expect(page.get_by_text('21.5 °C')).to_be_visible()
            page.get_by_text('Home', exact=True).click(force=True)
            expect(page.get_by_text('Living spaces', exact=True)).to_be_visible()
            page.wait_for_load_state('networkidle')
            # The built-in light fallback has switches; the real RC tile has its own content.
            expect(page.get_by_role('switch')).to_have_count(2)
            switch = page.get_by_role('switch', name='Living room', exact=True)
            expect(switch).to_be_checked()
            click_switch(switch)
            expect(switch).not_to_be_checked()
            assert not Handler.mutations
            expect(page.get_by_role('textbox', name='Long-lived access token')).to_have_count(0)
            page.screenshot(path=f'_wasm-test/demo-{name}-classic.png')
            page.get_by_text('Console', exact=True).click(force=True)
            deadline = time.monotonic() + 5
            while not any(r['cardStyle'] == 'console' for r in requests) and time.monotonic() < deadline:
                page.wait_for_timeout(100)
            page.wait_for_timeout(500)
            assert any(r['cardStyle'] == 'console' for r in requests)
            page.get_by_role('button', name='Dark', exact=True).click(force=True)
            expect(page.get_by_role('button', name='Light', exact=True)).to_be_visible()
            page.screenshot(path=f'_wasm-test/demo-{name}-console.png')
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
            expect(switch).to_be_enabled()
        page.get_by_role('button', name='Dark', exact=True).click(force=True)
        expect(page.get_by_role('button', name='Light', exact=True)).to_be_visible()
        assert not offline_errors, offline_errors
        page.screenshot(path='_wasm-test/demo-mobile-offline.png')
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
        expect(page.get_by_text('Example home · live demo data · try the switches', exact=True)).to_be_visible(timeout=30000)
        expect(page.get_by_text('21.5 °C')).to_be_visible()
        switch = page.get_by_role('switch').first
        expect(switch).not_to_be_checked()
        click_switch(switch)
        expect(switch).to_be_checked()
        assert Handler.mutations == [('/demo-ha/api/services/light/toggle', {'entity_id': 'light.demo_lamp'})]
        Handler.reject_controls = True
        click_switch(switch)
        expect(page.get_by_text('Could not update demo state · try again', exact=True)).to_be_visible()
        expect(switch).to_be_checked()
        Handler.reject_controls = False
        click_switch(switch)
        expect(switch).not_to_be_checked()
        click_switch(switch)
        expect(switch).to_be_checked()
        expect(page.get_by_text('Could not update demo state · try again', exact=True)).to_have_count(0)
        expect(page.get_by_text('1 min ago')).to_be_visible()
        page.clock.fast_forward(60000)
        expect(page.get_by_text('2 min ago')).to_be_visible()
        Handler.temperature = '22.8'
        expect(page.get_by_text('22.8 °C')).to_be_visible(timeout=10000)
        expect(switch).to_be_checked()
        page.screenshot(path='_wasm-test/demo-wall-live.png')
        Handler.live = False
        expect(page.get_by_text('Live demo unavailable · using fake data · retrying', exact=True)).to_be_visible(timeout=10000)
        # Deliver the removal frame after changing grids under the test clock.
        page.clock.run_for(100)
        expect(page.get_by_text('Live comfort', exact=True)).to_have_count(0)
        page.screenshot(path='_wasm-test/demo-wall-live-fallback.png')
        page.get_by_text('Fake data', exact=True).click(force=True)
        count = len(Handler.gateway_requests)
        page.wait_for_timeout(6000)
        assert len(Handler.gateway_requests) == count, Handler.gateway_requests
        Handler.live = True
        page.get_by_text('Live when available', exact=True).click(force=True)
        expect(page.get_by_text('Example home · live demo data · try the switches', exact=True)).to_be_visible(timeout=10000)
        assert not live_errors, live_errors
        browser.close()
        print('Demo: mobile/wall layouts, real RC playback, inline style/theme, credential-free, offline fallback, live refresh, outage recovery and fake-mode isolation passed')
finally:
    server.shutdown()
