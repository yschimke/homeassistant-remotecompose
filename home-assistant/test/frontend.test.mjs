// Real TS-player rendering with HA's native <ha-card> already registered. No real credentials.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? `${root}embed/node_modules/playwright/index.mjs`);
const playerPath = process.env.RC_PLAYER_JS ?? `${root}design-server/build/generated/embed/embed/v1/rc-player.js`;
if (!existsSync(playerPath)) throw new Error('Build the TS player or set RC_PLAYER_JS');
const template = readFileSync(`${root}design-server/src/test/resources/tile-light.rc`);
const sha = createHash('sha256').update(template).digest('hex');
const frontend = `${root}home-assistant/custom_components/terrazzo/frontend/`;

test('HA custom card uses signed-in state/actions, updates live and renders real RC bytes', async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 640, height: 360 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.route('https://ha.test/**', (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('.js')) {
        const name = path.split('/').pop();
        const file = name === 'ha-card.js' ? `${root}embed/ha-card.js` : name === 'rc-player.js' ? playerPath : `${frontend}${name}`;
        return route.fulfill({ contentType: 'text/javascript', body: readFileSync(file) });
      }
      if (path.endsWith('.rc')) return route.fulfill({ body: template, contentType: 'application/octet-stream' });
      return route.fulfill({ contentType: 'text/html', body: `<!doctype html><meta charset="utf-8"><style>body{font:16px system-ui;background:#f4f4f4;margin:24px}remote-compose-card{width:400px;background:white;border-radius:16px}</style>
        <p>Home Assistant · Remote Compose</p><script>window.NativeHaCard = class extends HTMLElement {}; customElements.define('ha-card', NativeHaCard);</script>
        <script type="module" src="/terrazzo-static/remote-compose-card.js"></script>` });
    });
    await page.goto('https://ha.test/lovelace/0');
    await page.evaluate(async ({sha}) => {
      await customElements.whenDefined('remote-compose-card');
      window.calls = [];
      window.moreInfo = [];
      window.hassFixture = {
        states: { 'light.kitchen': { state: 'off', attributes: { friendly_name: 'Kitchen' } } },
        themes: { darkMode: false }, user: { id: 'limited-user' },
        callService: async (...args) => window.calls.push(args),
      };
      const card = document.createElement('remote-compose-card');
      card.setConfig({ type: 'custom:remote-compose-card', library: 'https://ha.test/designs/v1/', design: sha,
        card: { type: 'tile', entity: 'light.kitchen', tap_action: { action: 'toggle' } } });
      card.hass = window.hassFixture;
      card.addEventListener('hass-more-info', (e) => moreInfo.push(e.detail.entityId));
      document.body.append(card);
      window.card = card;
    }, {sha});
    await page.waitForFunction(() => card.dataset.status === 'live' && card.player);
    assert.equal(await page.evaluate(() => customElements.get('ha-card') === customElements.get('remote-compose-card')), false);
    await page.waitForFunction(() => card.pushed.get('USER:light.s0.state') === 'Off');
    const canvas = page.locator('remote-compose-card canvas');
    const before = await canvas.screenshot();
    const evidence = process.env.HA_PLUGIN_EVIDENCE ?? `${root}build/ha-plugin-evidence`;
    mkdirSync(evidence, { recursive: true });
    await page.screenshot({ path: `${evidence}/card-before.png` });
    // A real document tap routes to hass.callService with the viewer's connection.
    await canvas.click({ position: { x: 100, y: 35 } });
    await page.waitForFunction(() => calls.length === 1);
    assert.deepEqual(await page.evaluate(() => calls[0]), ['homeassistant', 'toggle', {}, {entity_id: 'light.kitchen'}]);
    await page.evaluate(() => {
      hassFixture.states = { 'light.kitchen': { state: 'on', attributes: { friendly_name: 'Kitchen' } } };
      card.hass = { ...hassFixture };
    });
    await page.waitForFunction(() => card.pushed.get('USER:light.s0.state') === 'On');
    assert.notDeepEqual(await canvas.screenshot(), before);
    await page.screenshot({ path: `${evidence}/card-after.png` });
    await page.evaluate(() => card.tap({kind:'MoreInfo', entityId:'light.kitchen'}));
    assert.deepEqual(await page.evaluate(() => moreInfo), ['light.kitchen']);
    await page.evaluate(async () => {
      card.config.card.tap_action = { action: 'perform-action', perform_action: 'light.turn_on',
        target: {area_id:['kitchen','hall']}, confirmation: {text:'Confirm lights'} };
      const action = {kind:'CallService', domain:'light', service:'turn_on',serviceData:{brightness:128}};
      window.confirm = () => false;
      await card.tap(action);
      window.confirm = () => true;
      await card.tap(action);
    });
    assert.equal(await page.evaluate(() => calls.length), 2, 'cancelled confirmation must not call HA');
    assert.deepEqual(await page.evaluate(() => calls[1]), ['light','turn_on',{brightness:128},{area_id:['kitchen','hall']}]);
    await page.evaluate(async () => {
      card.config.card.tap_action = {action:'toggle'};
      card._hass.callService = async () => { throw new Error('Unauthorized'); };
      await card.tap({kind:'Toggle', entityId:'light.kitchen'});
    });
    assert.match(await page.locator('remote-compose-card .status').textContent(), /Unauthorized/);
    // Removing an entity must clear it, not retain the old live value.
    await page.evaluate(() => { card.hass = {...hassFixture, states:{}}; });
    assert.equal(await page.evaluate(() => card.pushed.get('USER:light.s0.state')), 'Unavailable');
    assert.deepEqual(errors, []);
    assert.deepEqual(await page.evaluate(() => Object.keys(localStorage)), []);
    // HA can also lazy-load its chrome after the resource; do not claim its tag first.
    const early = await browser.newPage();
    await early.route('https://early.test/**', (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('.js')) return route.fulfill({contentType:'text/javascript',body:readFileSync(
        path.endsWith('/ha-card.js') ? `${root}embed/ha-card.js` : `${frontend}remote-compose-card.js`)});
      return route.fulfill({contentType:'text/html',body:'<script type="module" src="/remote-compose-card.js"></script>'});
    });
    await early.goto('https://early.test/');
    await early.evaluate(() => customElements.whenDefined('remote-compose-card'));
    assert.equal(await early.evaluate(() => !!customElements.get('ha-card')), false);
    await early.close();
  } finally { await browser.close(); }
});

test('panel passes only same-origin session capability, refreshes auth and cleans up iframe', async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    await page.route('https://ha.test/**', (route) => {
      if (route.request().url().endsWith('terrazzo-panel.js')) return route.fulfill({
        contentType: 'text/javascript', body: readFileSync(`${frontend}terrazzo-panel.js`) });
      return route.fulfill({ contentType: 'text/html', body: '<script type="module" src="/terrazzo-static/terrazzo-panel.js"></script>' });
    });
    await page.goto('https://ha.test/terrazzo');
    const result = await page.evaluate(async () => {
      await customElements.whenDefined('terrazzo-panel');
      const panel = document.createElement('terrazzo-panel');
      const auth = { expired: true, accessToken: 'expired-fixture', async refreshAccessToken() { this.accessToken = 'fresh-fixture'; this.expired = false; } };
      panel.hass = { auth }; panel.panel = {config:{dashboard:'wall-panel'}};
      document.body.append(panel);
      const token = await panel.frame.terrazzoHost.getAccessToken();
      const path = panel.frame.src;
      const dashboard = panel.frame.terrazzoHost.getDashboard();
      const height = panel.frame.getBoundingClientRect().height;
      const viewportHeight = window.innerHeight;
      panel.remove();
      let denied = false;
      try { await panel.frame.terrazzoHost.getAccessToken(); } catch { denied = true; }
      document.body.append(panel);
      return {height, viewportHeight, fresh: token === 'fresh-fixture', path, dashboard, denied, reattached: panel.frame.src};
    });
    assert.equal(result.height, result.viewportHeight, 'panel must fill HA height without a percentage-height ancestor');
    assert.equal(result.fresh, true);
    assert.equal(result.path, 'https://ha.test/terrazzo-static/wasm/index.html?ha-panel');
    assert.equal(result.dashboard, 'wall-panel');
    assert.equal(result.denied, true);
    assert.equal(result.reattached, result.path);
  } finally { await browser.close(); }
});
