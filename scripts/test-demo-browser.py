#!/usr/bin/env python3
"""Exercise credential-free demo routes, inline designs and HA-only outage recovery at two sizes."""
import datetime
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
templates = {style: Path(f'scripts/fixtures/demo/tile-{style}.rc').read_bytes() for style in ('classic', 'console')}
blobs = {hashlib.sha256(data).hexdigest(): data for data in templates.values()}


class Handler(http.server.SimpleHTTPRequestHandler):
    live = True
    lamp = 'off'
    reject_controls = False
    mutations = []
    temperature = '21.5'
    gateway_requests = []
    showcase = False
    target_temperature = 22.0
    coffee = 'off'
    power = '1100'

    def do_GET(self):
        if self.path.startswith('/demo-ha/'):
            Handler.gateway_requests.append(self.path)
            self.send_response(200 if Handler.live else 503)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            if self.path == '/demo-ha/dashboard':
                payload = {'title': 'Public demo', 'views': [{'title': 'Home', 'cards': [
                    {'type': 'tile', 'entity': 'light.demo_lamp', 'name': 'Demo lamp'},
                    {'type': 'entities', 'title': 'Live comfort', 'entities': ['light.demo_lamp', 'sensor.demo_temperature', 'sensor.demo_last_seen']}
                ]}]}
            else:
                payload = [{'entity_id': 'light.demo_lamp', 'state': Handler.lamp, 'attributes': {'friendly_name': 'Demo lamp'}},
                           {'entity_id': 'sensor.demo_temperature', 'state': Handler.temperature,
                            'attributes': {'friendly_name': 'Demo temperature', 'unit_of_measurement': '°C'},
                            'last_changed': '2026-10-17T12:00:00Z', 'last_updated': '2026-10-17T12:01:00Z'},
                           {'entity_id': 'sensor.demo_last_seen', 'state': '2026-10-17T12:01:00Z',
                            'attributes': {'friendly_name': 'Last seen', 'device_class': 'timestamp'}}]
            if Handler.showcase:
                if self.path == '/demo-ha/dashboard':
                    payload = json.loads(Path('deploy/demo-ha/dashboard.json').read_text())
                else:
                    payload = [
                        {'entity_id':'climate.demo_thermostat','state':'heat','attributes':{
                            'friendly_name':'Demo thermostat','current_temperature':float(Handler.temperature),
                            'temperature':Handler.target_temperature,'min_temp':16,'max_temp':28,
                            'target_temp_step':0.5,'hvac_action':'heating','temperature_unit':'°C'}},
                        {'entity_id':'light.demo_lamp','state':Handler.lamp,'attributes':{'friendly_name':'Demo lamp'}},
                        {'entity_id':'switch.demo_coffee','state':Handler.coffee,'attributes':{'friendly_name':'Demo coffee'}},
                        {'entity_id':'binary_sensor.demo_motion','state':'on','attributes':{'device_class':'motion'}},
                    ] + [{'entity_id':'sensor.demo_'+name,'state':value,'attributes':{
                        'friendly_name':name,'unit_of_measurement':unit}} for name,value,unit in [
                        ('temperature',Handler.temperature,'°C'),('humidity','47','%'),('battery','78','%'),
                        ('power',Handler.power,'W'),('solar','2600','W')]]
            self.wfile.write(json.dumps(payload).encode())
            return
        super().do_GET()

    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        Handler.mutations.append((self.path, data))
        if self.path == '/demo-ha/api/services/climate/set_temperature':
            assert data['entity_id'] == 'climate.demo_thermostat'
            assert 16 <= data['temperature'] <= 28
            if Handler.live and not Handler.reject_controls:
                Handler.target_temperature = data['temperature']
        else:
            entity = data['entity_id']
            assert (self.path, entity) in [('/demo-ha/api/services/light/toggle','light.demo_lamp'),
                                          ('/demo-ha/api/services/switch/toggle','switch.demo_coffee')]
            assert set(data) == {'entity_id'}
            if Handler.live and not Handler.reject_controls:
                if entity == 'light.demo_lamp': Handler.lamp = 'on' if Handler.lamp == 'off' else 'off'
                else: Handler.coffee = 'on' if Handler.coffee == 'off' else 'off'
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


def click_control(switch):
    # Compose's canvas handles pointers; its accessibility DOM sits underneath it.
    # Wait for the current layout before sending a real press/release through the canvas.
    switch.wait_for(state='visible')
    switch.scroll_into_view_if_needed()
    switch.click(force=True, delay=100)

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
            page.add_init_script("""document.addEventListener('contextmenu', event => {
                setTimeout(() => { window.entityMenuPrevented = event.defaultPrevented; }, 0);
            }, true);""")
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
            page.route(DESIGN_BLOB, lambda route: route.fulfill(
                body=blobs[route.request.url.rsplit('/', 1)[-1].removesuffix('.rc')], content_type='application/octet-stream', headers={'Access-Control-Allow-Origin': '*'}))
            page.goto(f'http://127.0.0.1:{server.server_port}/demo/?data=fake', wait_until='networkidle')
            # Legacy fake URLs must still fetch and display server-owned state.
            expect(page.get_by_text('Live · try the switches', exact=True)).to_be_visible(timeout=30000)
            expect(page.get_by_text('Fake data', exact=True)).to_have_count(0)
            expect(page.get_by_text('Live when available', exact=True)).to_have_count(0)
            expect(page.get_by_text('21.5 °C')).to_be_visible()
            page.wait_for_load_state('networkidle')
            expect(page.get_by_role('switch')).to_have_count(2)
            switch = page.get_by_role('switch').first
            expect(switch).not_to_be_checked()
            count = len(Handler.mutations)
            # The real RC fixture tile sits immediately above its host switch row.
            # Press inside its canvas, exercising interception before the RC player.
            expect(switch).to_be_visible()
            capture(page, f'_wasm-test/demo-{name}-ready.png')
            box = switch.bounding_box()
            assert box, 'The live lamp switch must have a painted layout'
            page.mouse.click(box['x'] + box['width'] / 2, box['y'] - 70, button='right', delay=100)
            page.wait_for_function('window.entityMenuPrevented === true')
            details = page.get_by_role('dialog')
            expect(details.get_by_text('light.demo_lamp', exact=True)).to_be_visible()
            expect(details.get_by_text('Off', exact=True)).to_be_visible()
            assert len(Handler.mutations) == count, 'Right-click must not toggle the entity'
            Handler.lamp = 'on'
            expect(details.get_by_text('On', exact=True)).to_be_visible(timeout=10000)
            Handler.lamp = 'off'
            expect(details.get_by_text('Off', exact=True)).to_be_visible(timeout=10000)
            page.screenshot(path=f'_wasm-test/demo-{name}-entity-details.png')
            click_control(details.get_by_role('button', name='Close', exact=True))
            expect(page.get_by_role('button', name='Close', exact=True)).to_have_count(0)
            # An entities-card row must open that row's entity, not its first sibling.
            page.get_by_text('21.5 °C').click(button='right', force=True, delay=100)
            expect(details.get_by_text('sensor.demo_temperature', exact=True)).to_be_visible()
            expect(details.get_by_text('21.5 °C', exact=True)).to_be_visible()
            click_control(details.get_by_role('button', name='Close', exact=True))
            expect(page.get_by_role('button', name='Close', exact=True)).to_have_count(0)
            assert len(Handler.mutations) == count
            expect(switch).to_be_visible()
            capture(page, f'_wasm-test/demo-{name}-ready.png')
            box = switch.bounding_box()
            assert box, 'The live lamp switch must have a painted layout'
            page.mouse.click(box['x'] + box['width'] / 2, box['y'] - 70, delay=100)
            expect(switch).to_be_checked()
            assert len(Handler.mutations) == count + 1
            page.mouse.click(box['x'] + box['width'] / 2, box['y'] - 70, delay=100)
            expect(switch).not_to_be_checked()
            assert len(Handler.mutations) == count + 2
            count += 2
            click_control(switch)
            expect(switch).to_be_checked()
            assert len(Handler.mutations) == count + 1
            click_control(switch)
            expect(switch).not_to_be_checked()
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
            heading = page.get_by_text('Live comfort', exact=True).bounding_box()
            assert heading and heading['y'] < 350, heading
            page.get_by_role('button', name='Card style: Classic').click(force=True, delay=100)
            page.get_by_text('Braun', exact=True).click(force=True, delay=100)
            expect(page.get_by_text('Console', exact=True)).not_to_be_attached()
            expect(page.get_by_role('button', name='Card style: Braun')).to_be_visible()
            page.get_by_role('button', name='Card style: Braun').click(force=True, delay=100)
            page.get_by_text('LCD', exact=True).click(force=True, delay=100)
            expect(page.get_by_text('Console', exact=True)).not_to_be_attached()
            page.get_by_role('button', name='Card style: LCD').click(force=True, delay=100)
            capture(page, f'_wasm-test/demo-{name}-styles.png')
            page.get_by_text('Console', exact=True).click(force=True, delay=100)
            expect(page.get_by_text('Braun', exact=True)).not_to_be_attached()
            deadline = time.monotonic() + 5
            while not any(r['cardStyle'] == 'console' for r in requests) and time.monotonic() < deadline:
                page.wait_for_timeout(100)
            page.wait_for_timeout(500)
            assert any(r['cardStyle'] == 'console' for r in requests)
            page.get_by_role('button', name='Demo options').click(force=True, delay=100)
            page.get_by_text('Use dark theme', exact=True).click(force=True, delay=100)
            expect(page.get_by_text('Fake data', exact=True)).not_to_be_attached()
            capture(page, f'_wasm-test/demo-{name}-theme.png')
            page.get_by_role('button', name='Demo options').click(force=True, delay=100)
            expect(page.get_by_text('Use light theme', exact=True)).to_be_visible()
            capture(page, f'_wasm-test/demo-{name}-options.png')
            page.keyboard.press('Escape')
            expect(page.get_by_text('Use light theme', exact=True)).not_to_be_attached()
            capture(page, f'_wasm-test/demo-{name}-console.png')
            assert all('states' not in r for r in requests)
            assert not sockets, sockets
            assert not errors, errors
            page.close()

        Handler.live = False
        page = browser.new_page(viewport={'width': 390, 'height': 844})
        offline_errors = []
        page.on('pageerror', lambda error: offline_errors.append(str(error)))
        page.route('https://ha.coo.ee/**', lambda route: route.abort())
        page.goto(f'http://127.0.0.1:{server.server_port}/demo/', wait_until='networkidle')
        expect(page.get_by_text('Demo HA unavailable · retrying', exact=True)).to_be_visible(timeout=30000)
        expect(page.get_by_role('switch')).to_have_count(0)
        expect(page.get_by_text('21.5 °C')).to_have_count(0)
        expect(page.get_by_text('Fake data', exact=True)).to_have_count(0)
        page.get_by_role('button', name='Demo options').click(force=True, delay=100)
        page.get_by_text('Use dark theme', exact=True).click(force=True, delay=100)
        capture(page, '_wasm-test/demo-mobile-offline-dark.png')
        page.get_by_role('button', name='Demo options').click(force=True, delay=100)
        expect(page.get_by_text('Use light theme', exact=True)).to_be_visible()
        page.keyboard.press('Escape')
        assert not offline_errors, offline_errors
        page.screenshot(path='_wasm-test/demo-mobile-offline.png')
        page.close()
        # Real WASM/Ktor HTTP reads, control requests, refresh and automatic reconnection.
        Handler.live = True
        Handler.mutations.clear()
        Handler.gateway_requests.clear()
        page = browser.new_page(viewport={'width': 1440, 'height': 900})
        page.clock.install(time=datetime.datetime(2026, 10, 17, 12, 2, tzinfo=datetime.timezone.utc))
        live_errors = []
        page.on('pageerror', lambda error: live_errors.append(str(error)))
        page.route('https://ha.coo.ee/**', lambda route: route.abort())
        page.goto(f'http://127.0.0.1:{server.server_port}/demo/', wait_until='networkidle')
        expect(page.get_by_text('Live · try the switches', exact=True)).to_be_visible(timeout=30000)
        expect(page.get_by_text('21.5 °C')).to_be_visible()
        switch = page.get_by_role('switch').first
        expect(switch).not_to_be_checked()
        click_control(switch)
        expect(switch).to_be_checked()
        assert Handler.mutations == [('/demo-ha/api/services/light/toggle', {'entity_id': 'light.demo_lamp'})]
        Handler.reject_controls = True
        click_control(switch)
        expect(page.get_by_text('Could not update demo state · try again', exact=True)).to_be_visible()
        expect(switch).to_be_checked()
        Handler.reject_controls = False
        click_control(switch)
        expect(switch).not_to_be_checked()
        click_control(switch)
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
        expect(page.get_by_text('Demo HA unavailable · retrying', exact=True)).to_be_visible(timeout=10000)
        # Deliver the removal frame after changing grids under the test clock.
        page.clock.run_for(100)
        expect(page.get_by_text('Live comfort', exact=True)).to_have_count(0)
        expect(page.get_by_role('switch')).to_have_count(0)
        expect(page.get_by_text('22.8 °C')).to_have_count(0)
        page.screenshot(path='_wasm-test/demo-wall-unavailable.png')
        Handler.live = True
        # Keep the same page: only the automatic retry can restore the HA cards.
        page.clock.run_for(15000)
        expect(page.get_by_text('Live · try the switches', exact=True)).to_be_visible(timeout=10000)
        expect(page.get_by_text('Live comfort', exact=True)).to_be_visible()
        expect(page.get_by_text('22.8 °C')).to_be_visible()
        expect(switch).to_be_checked()
        for cycle in range(2):
            Handler.live = False
            expect(page.get_by_text('Demo HA unavailable · retrying', exact=True)).to_be_visible(timeout=10000)
            expect(page.get_by_text('Live comfort', exact=True)).to_have_count(0)
            expect(page.get_by_role('switch')).to_have_count(0)
            Handler.live = True
            page.clock.run_for(15000)
            expect(page.get_by_text('Live · try the switches', exact=True)).to_be_visible(timeout=10000)
            expect(page.get_by_text('Live comfort', exact=True)).to_be_visible()
            expect(switch).to_be_checked()
        assert not live_errors, live_errors
        assert not target_errors, target_errors
        page.close()
        Handler.showcase = True
        Handler.live = True
        Handler.temperature = '21.5'
        instruments = {kind: Path(f'scripts/fixtures/demo/{kind}-console.rc').read_bytes()
                       for kind in ('gauge','thermostat')}
        instrument_blobs = {hashlib.sha256(data).hexdigest():data for data in instruments.values()}
        for name, width, height in [('phone',390,844),('wall',1440,900)]:
            page = browser.new_page(viewport={'width':width,'height':height},device_scale_factor=1)
            Handler.temperature = '21.5'
            Handler.power = '1100'
            instrument_errors, instrument_requests = [], []
            page.on('pageerror',lambda error:instrument_errors.append(str(error)))
            def resolve_instrument(route):
                request=json.loads(route.request.post_data)
                instrument_requests.append(request)
                kind=request['card']['type']
                if request['cardStyle'] == 'console' and kind in instruments:
                    route.fulfill(json={'sha256':hashlib.sha256(instruments[kind]).hexdigest()},
                                  headers={'Access-Control-Allow-Origin':'*'})
                else: route.fulfill(status=404,headers={'Access-Control-Allow-Origin':'*'})
            page.route('https://ha.coo.ee/api/cards/resolve',resolve_instrument)
            page.route(DESIGN_BLOB,lambda route:route.fulfill(
                body=instrument_blobs[route.request.url.rsplit('/',1)[-1].removesuffix('.rc')],
                content_type='application/octet-stream',headers={'Access-Control-Allow-Origin':'*'}))
            page.clock.install(time=datetime.datetime(2026,10,17,12,2,tzinfo=datetime.timezone.utc))
            page.goto(f'http://127.0.0.1:{server.server_port}/demo/',wait_until='networkidle')
            expect(page.get_by_text('Live · try the switches',exact=True)).to_be_visible(timeout=30000)
            page.get_by_role('button',name='Card style: Classic').click(force=True,delay=100)
            page.get_by_text('Console',exact=True).click(force=True,delay=100)
            page.wait_for_load_state('networkidle')
            # SCH-13: inactive RC layouts also export text; the active full dial is first.
            expect(page.get_by_text('21.5°C',exact=True).filter(visible=True).first).to_be_visible()
            expect(page.get_by_text('↑ 22°C',exact=True).filter(visible=True).first).to_be_visible()
            # The fixture resolves only gauge/thermostat designs. The two other cards
            # visible in the phone viewport deliberately return 404; the wall composes
            # all six non-instrument cards. Each must disclose its built-in fallback.
            # The instrument reading assertions above still require actual RC playback.
            expect(page.get_by_text('Design unavailable · showing built-in card',exact=True)).to_have_count(2 if name == 'phone' else 6)
            expect(page.get_by_text(f'{Handler.target_temperature} °C target',exact=True)).to_be_visible()
            count=len(Handler.mutations)
            before=Handler.target_temperature
            resolved_before=len([r for r in instrument_requests if r['card']['type']=='thermostat'])
            # Physical keys in the pinned Console RC fixture sit above the accessible host row.
            key=page.get_by_role('button',name='Warmer',exact=True).bounding_box()
            reading = page.get_by_text('21.5°C',exact=True).filter(visible=True).first.bounding_box()
            center = reading['x'] + reading['width']/2
            page.mouse.click(center+42,key['y']-46,delay=100)
            expect(page.get_by_text(f'{before+0.5} °C target',exact=True)).to_be_visible()
            assert len(Handler.mutations) == count+1
            page.mouse.click(center-42,key['y']-46,delay=100)
            expect(page.get_by_text(f'{before} °C target',exact=True)).to_be_visible()
            assert len(Handler.mutations) == count+2
            count += 2
            click_control(page.get_by_role('button',name='Warmer',exact=True))
            expect(page.get_by_text(f'{before+0.5} °C target',exact=True)).to_be_visible()
            expect(page.get_by_text('↑ 22.5°C',exact=True).filter(visible=True).first).to_be_visible()
            assert len(Handler.mutations) == count+1
            assert Handler.mutations[-1] == ('/demo-ha/api/services/climate/set_temperature',
                                             {'entity_id':'climate.demo_thermostat','temperature':before+0.5})
            click_control(page.get_by_role('button',name='Cooler',exact=True))
            expect(page.get_by_text(f'{before} °C target',exact=True)).to_be_visible()
            Handler.temperature = '22.0'
            expect(page.get_by_text('22°C',exact=True).filter(visible=True).first).to_be_visible(timeout=10000)
            assert len([r for r in instrument_requests if r['card']['type']=='thermostat']) == resolved_before
            capture(page,f'_wasm-test/demo-console-{name}-climate.png')
            if name == 'phone':
                page.mouse.move(180,550)
                page.mouse.wheel(0,600)
            expect(page.get_by_text('Power station',exact=True)).to_be_visible()
            page.wait_for_load_state('networkidle')
            expect(page.get_by_role('button',name='1100 W Power draw 0 – 3000 W',exact=True).filter(visible=True).first).to_be_visible()
            expect(page.get_by_role('button',name='2600 W Solar output 0 – 4000 W',exact=True).filter(visible=True).first).to_be_visible()
            Handler.power = '900'
            expect(page.get_by_role('button',name='900 W Power draw 0 – 3000 W',exact=True).filter(visible=True).first).to_be_visible(timeout=10000)
            assert {'thermostat','gauge'} <= {r['card']['type'] for r in instrument_requests if r['cardStyle']=='console'}
            capture(page,f'_wasm-test/demo-console-{name}-power.png')
            page.mouse.move(180, min(550,height-30))
            if name == 'phone': page.mouse.wheel(0,1000)
            capture(page,f'_wasm-test/demo-console-{name}-controls.png')
            coffee=page.get_by_role('button').filter(has_text='Coffee machine')
            expect(coffee).to_be_in_viewport(ratio=1)
            count=len(Handler.mutations)
            previous=Handler.coffee
            click_control(coffee)
            expect(coffee).to_contain_text('On' if previous == 'off' else 'Off')
            assert len(Handler.mutations) == count+1
            assert Handler.mutations[-1] == ('/demo-ha/api/services/switch/toggle',{'entity_id':'switch.demo_coffee'})
            click_control(coffee)
            expect(coffee).to_contain_text('Off' if previous == 'off' else 'On')
            assert len(Handler.mutations) == count+2, 'One card press must dispatch exactly one action'
            if name == 'wall':
                page.mouse.move(5,5)
                pause=datetime.datetime.fromtimestamp((page.evaluate('Date.now()')+1000)/1000,
                                                      tz=datetime.timezone.utc)
                page.clock.pause_at(pause)
                # Resizing clears the WebGL backing buffer before Compose paints it. Observe
                # the first actual draw at the new size, not a blank compositor frame. We
                # still compare that first rendered frame with the later one below.
                page.evaluate('''(() => {
                    // ComposeViewport draws on a canvas inside a shadow root under #app, out of
                    // reach of document.querySelector; search every open shadow root for it.
                    const findCanvas = root => {
                        const direct = root.querySelector('canvas');
                        if (direct) return direct;
                        for (const el of root.querySelectorAll('*'))
                            if (el.shadowRoot) {
                                const found = findCanvas(el.shadowRoot);
                                if (found) return found;
                            }
                        return null;
                    };
                    const canvas = findCanvas(document);
                    if (!canvas) throw new Error('Expected the Compose canvas');
                    const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
                    if (!gl) throw new Error('Expected the Compose WebGL canvas');
                    window.resizedCanvasDrawn = false;
                    for (const name of ['drawArrays', 'drawElements',
                                        'drawArraysInstanced', 'drawElementsInstanced']) {
                        if (typeof gl[name] !== 'function') continue;
                        const draw = gl[name].bind(gl);
                        gl[name] = (...args) => {
                            const result = draw(...args);
                            if (canvas.width === 1280 && canvas.height === 900)
                                window.resizedCanvasDrawn = true;
                            return result;
                        };
                    }
                })()''')
                page.set_viewport_size({'width':1280,'height':900})
                for _ in range(120):
                    page.clock.run_for(16)
                    if page.evaluate('window.resizedCanvasDrawn'):
                        break
                assert page.evaluate('window.resizedCanvasDrawn'), 'Resized canvas never painted'
                first=page.screenshot(path='_wasm-test/demo-console-resize-first.png')
                page.clock.run_for(500)
                settled=page.screenshot(path='_wasm-test/demo-console-resize-settled.png')
                assert first == settled, 'Responsive layout must land immediately without text/bounds tweens'
            assert not instrument_errors, instrument_errors
            page.close()
        browser.close()
        print('Demo: mobile/wall layouts, real RC playback, inline style/theme, credential-free, HA-only states and controls, RC/native primary clicks, live thermostat/gauge playback and controls, right-click details with live updates, live refresh, outage clearing and automatic reconnection passed')
finally:
    server.shutdown()
