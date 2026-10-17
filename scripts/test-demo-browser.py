#!/usr/bin/env python3
"""Exercise credential-free demo routes, inline designs and offline fallback at two sizes."""
import hashlib
import http.server
import json
import os
from pathlib import Path
import sys
import threading

from playwright.sync_api import expect, sync_playwright

root = Path(sys.argv[1]).resolve()
template = Path('design-server/src/test/resources/tile-light.rc').read_bytes()
sha = hashlib.sha256(template).hexdigest()


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
                    route.fulfill(json={'sha256': sha}, headers={'Access-Control-Allow-Origin': '*'})
                else:
                    route.fulfill(status=404, headers={'Access-Control-Allow-Origin': '*'})

            page.route('https://ha.coo.ee/api/cards/resolve', resolve)
            page.route('https://ha.coo.ee/designs/v1/blobs/*.rc', lambda route: route.fulfill(
                body=template, content_type='application/octet-stream', headers={'Access-Control-Allow-Origin': '*'}))
            page.goto(f'http://127.0.0.1:{server.server_port}/demo/', wait_until='networkidle')
            expect(page.get_by_text('Example home · fake data · read only', exact=True)).to_be_visible(timeout=30000)
            expect(page.get_by_text('21.5 °C', exact=True)).to_be_visible()
            expect(page.get_by_role('textbox', name='Long-lived access token')).to_have_count(0)
            page.screenshot(path=f'_wasm-test/demo-{name}-classic.png')
            page.get_by_text('Console', exact=True).click(force=True)
            page.wait_for_function('() => document.querySelector("canvas") !== null')
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
        page.route('https://ha.coo.ee/**', lambda route: route.abort())
        page.goto(f'http://127.0.0.1:{server.server_port}/demo/', wait_until='networkidle')
        expect(page.get_by_text('21.5 °C', exact=True)).to_be_visible(timeout=30000)
        for switch in page.get_by_role('switch').all():
            expect(switch).to_be_disabled()
        page.screenshot(path='_wasm-test/demo-mobile-offline.png')
        browser.close()
        print('Demo: mobile/wall layouts, real RC playback, inline style/theme, credential-free and offline fallback passed')
finally:
    server.shutdown()
