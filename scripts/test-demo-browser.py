#!/usr/bin/env python3
"""Exercise credential-free demo routes, inline designs and offline fallback at two sizes."""
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
templates = {style: Path(f'home-assistant/test/fixtures/tile-{style}.rc').read_bytes() for style in ('classic', 'console')}
blobs = {hashlib.sha256(data).hexdigest(): data for data in templates.values()}


class Handler(http.server.SimpleHTTPRequestHandler):
    def translate_path(self, path):
        name = path.split('?')[0]
        if name in ('/demo', '/demo/'):
            return str(root / 'index.html')
        return str(root / name.removeprefix('/demo/'))

    def log_message(self, *args):
        pass


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
            page.goto(f'http://127.0.0.1:{server.server_port}/demo/', wait_until='networkidle')
            expect(page.get_by_text('Example home · fake data · read only', exact=True)).to_be_visible(timeout=30000)
            page.get_by_text('Rooms', exact=True).click(force=True)
            if os.environ.get('DEMO_DEBUG'):
                print(page.locator('body').inner_text())
                page.screenshot(path=f'_wasm-test/demo-{name}-debug.png')
            expect(page.get_by_text('21.5 °C')).to_be_visible()
            page.get_by_text('Home', exact=True).click(force=True)
            expect(page.get_by_text('Living spaces', exact=True)).to_be_visible()
            page.wait_for_load_state('networkidle')
            # The built-in light fallback has switches; the real RC tile has its own content.
            expect(page.get_by_role('switch')).to_have_count(0)
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
            expect(switch).to_be_disabled()
        page.get_by_role('button', name='Dark', exact=True).click(force=True)
        expect(page.get_by_role('button', name='Light', exact=True)).to_be_visible()
        assert not offline_errors, offline_errors
        page.screenshot(path='_wasm-test/demo-mobile-offline.png')
        browser.close()
        print('Demo: mobile/wall layouts, real RC playback, inline style/theme, credential-free and offline fallback passed')
finally:
    server.shutdown()
