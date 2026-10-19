#!/usr/bin/env python3
"""Manual real-backend smoke; no admin credentials. Local gateway override is explicitly labeled."""
import argparse
from pathlib import Path
import time
from urllib.parse import urlsplit

from playwright.sync_api import expect, sync_playwright

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--site', default='https://ha.coo.ee/demo/')
parser.add_argument('--gateway', help='optional isolated backend override, e.g. http://127.0.0.1:8080')
parser.add_argument('--evidence', type=Path, default=Path('_wasm-test/live-demo'))
args = parser.parse_args()
args.evidence.mkdir(parents=True, exist_ok=True)

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(args=['--no-sandbox', '--disable-gpu',
                                               '--enable-unsafe-swiftshader'])
    context = browser.new_context(viewport={'width': 1440, 'height': 900})
    if args.gateway:
        def forward(route):
            path = urlsplit(route.request.url).path.split('/demo-ha/', 1)[1]
            response = context.request.fetch(args.gateway.rstrip('/') + '/' + path,
                                             method=route.request.method,
                                             data=route.request.post_data,
                                             headers={'Content-Type': 'application/json'})
            route.fulfill(response=response)
        context.route('**/demo-ha/**', forward)
    base = args.gateway.rstrip('/') if args.gateway else args.site.split('/demo', 1)[0] + '/demo-ha'
    config = context.request.get(base + '/api/config').json()
    assert config['version'] == 'demo-controls-v4' and config['read_only'] is False, config

    def states():
        return {row['entity_id']: row for row in context.request.get(base + '/api/states').json()}

    pages = [context.new_page(), context.new_page()]
    for page in pages:
        page.goto(args.site, wait_until='networkidle')
        expect(page.get_by_text('Live · try the switches', exact=True)).to_be_visible(timeout=60000)
    page, other = pages
    page.screenshot(path=str(args.evidence / 'before-controls.png'))

    def click(locator):
        locator.scroll_into_view_if_needed()
        locator.click(force=True, delay=100)

    initial = states()
    try:
        lamp = page.get_by_role('switch', name='Demo lamp', exact=True).first
        other_lamp = other.get_by_role('switch', name='Demo lamp', exact=True).first
        click(lamp)
        desired = initial['light.demo_lamp']['state'] != 'on'
        expect(lamp).to_be_checked(checked=desired)
        expect(other_lamp).to_be_checked(checked=desired, timeout=12000)
        coffee = page.get_by_role('button').filter(has_text='Coffee machine').first
        click(coffee)
        expected_coffee = initial['switch.demo_coffee']['state'] == 'off'
        expect(page.get_by_role('switch', name='Demo coffee', exact=True).first).to_be_checked(checked=expected_coffee)
        expect(other.get_by_role('switch', name='Demo coffee', exact=True).first).to_be_checked(checked=expected_coffee, timeout=12000)
        target = initial['climate.demo_thermostat']['attributes']['temperature']
        key = 'Warmer' if target < 28 else 'Cooler'
        desired_target = target + (0.5 if key == 'Warmer' else -0.5)
        click(page.get_by_role('button', name=key, exact=True))
        expect(page.get_by_text(f'{desired_target:.1f} °C target', exact=True)).to_be_visible()
        expect(other.get_by_text(f'{desired_target:.1f} °C target', exact=True)).to_be_visible(timeout=12000)
        page.screenshot(path=str(args.evidence / 'after-controls.png'))
        print('PASS: WASM lamp, coffee and thermostat controls; second visitor converges', flush=True)
        # A deterministic battery drift proves visible live updates without forcing an event.
        battery = states()['sensor.demo_battery']['state']
        solar = states()['sensor.demo_solar']['state']
        deadline = time.monotonic() + 75
        while time.monotonic() < deadline:
            current = states()
            changed = current['sensor.demo_battery']['state'] != battery or current['sensor.demo_solar']['state'] != solar
            if changed:
                value = current['sensor.demo_solar']['state']
                reading = page.get_by_role('button', name=f'{value} W Solar output 0 – 4000 W', exact=True).or_(
                    page.get_by_text(f'{float(value):g} W', exact=True)).filter(visible=True).first
                expect(reading).to_be_visible(timeout=12000)
                assert current['light.demo_lamp']['state'] == ('on' if desired else 'off')
                assert current['climate.demo_thermostat']['attributes']['temperature'] == desired_target
                page.screenshot(path=str(args.evidence / 'after-simulation.png'))
                print('PASS: visible simulator reading changed; user controls retained', flush=True)
                break
            page.wait_for_timeout(1000)
        else:
            raise AssertionError('No simulation change within 75 seconds')
    finally:
        # Use idempotent restoration, never blind toggles after a failed assertion.
        for domain, entity in [('light', 'light.demo_lamp'), ('switch', 'switch.demo_coffee')]:
            response = context.request.post(base + f'/api/services/{domain}/turn_' + initial[entity]['state'], data={'entity_id': entity})
            assert response.status == 200, response.status
        response = context.request.post(base + '/api/services/climate/set_temperature', data={
            'entity_id': 'climate.demo_thermostat', 'temperature': initial['climate.demo_thermostat']['attributes']['temperature']})
        assert response.status == 200, response.status
        print('PASS: initial shared controls restored', flush=True)
    print('Backend: ' + ('isolated override ' + args.gateway if args.gateway else 'public deployment'), flush=True)
    browser.close()
