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
// Keep a real RC document but declare one string only its converter could provide.
const unsupportedTemplate = Buffer.from(template);
const variableOffset = unsupportedTemplate.indexOf(Buffer.from('light.s0.name'));
assert.ok(variableOffset >= 0);
Buffer.from('card.bad.name').copy(unsupportedTemplate, variableOffset);
const unsupportedSha = createHash('sha256').update(unsupportedTemplate).digest('hex');
const frontend = `${root}custom_components/terrazzo/frontend/`;

['library', 'local-url', 'remote-url'].forEach((source) => test(`HA custom card uses signed-in state/actions, updates live and renders real RC bytes (${source})`, async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 640, height: 360 }, deviceScaleFactor: 1 });
    const errors = [];
    const templateRequests = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.route('https://templates.test/**', (route) => {
      templateRequests.push(route.request().url());
      return route.fulfill({ body: template, contentType: 'application/octet-stream',
        headers: { 'Access-Control-Allow-Origin': '*' } });
    });
    await page.route('https://ha.test/**', (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('.js')) {
        const name = path.split('/').pop();
        const file = name === 'ha-card.js' ? `${root}embed/ha-card.js` : name === 'rc-player.js' ? playerPath : `${frontend}${name}`;
        return route.fulfill({ contentType: 'text/javascript', body: readFileSync(file) });
      }
      if (path.endsWith('.rc')) {
        templateRequests.push(route.request().url());
        return route.fulfill({ body: path.endsWith(`${unsupportedSha}.rc`) ? unsupportedTemplate : template, contentType: 'application/octet-stream' });
      }
      if (path === '/api/cards/resolve') throw new Error('explicit templates must bypass the resolver');
      return route.fulfill({ contentType: 'text/html', body: `<!doctype html><meta charset="utf-8"><style>body{font:16px system-ui;background:#f4f4f4;margin:24px}remote-compose-card{width:400px;background:white;border-radius:16px}</style>
        <p>Home Assistant · Remote Compose</p><script>window.NativeHaCard = class extends HTMLElement {}; customElements.define('ha-card', NativeHaCard);</script>
        <script type="module" src="/terrazzo-static/remote-compose-card.js"></script>` });
    });
    await page.goto('https://ha.test/lovelace/0');
    await page.evaluate(async ({sha, source}) => {
      await customElements.whenDefined('remote-compose-card');
      window.calls = [];
      window.moreInfo = [];
      window.hassFixture = {
        states: { 'light.kitchen': { state: 'off', attributes: { friendly_name: 'Kitchen' } } },
        themes: { darkMode: false }, user: { id: 'limited-user' },
        callService: async (...args) => window.calls.push(args),
      };
      const card = document.createElement('remote-compose-card');
      const templateConfig = source === 'library' ? { library: 'https://ha.test/designs/v1/', design: sha }
        : { template_url: source === 'local-url' ? '/local/cards/kitchen.rc' : 'https://templates.test/kitchen.rc' };
      card.setConfig({ type: 'custom:remote-compose-card', ...templateConfig,
        card: { type: 'tile', entity: 'light.kitchen', tap_action: { action: 'toggle' } } });
      card.hass = window.hassFixture;
      card.addEventListener('hass-more-info', (e) => moreInfo.push(e.detail.entityId));
      document.body.append(card);
      window.card = card;
    }, {sha, source});
    await page.waitForFunction(() => card.dataset.status === 'live' && card.player);
    assert.ok(templateRequests.includes(source === 'library' ? `https://ha.test/designs/v1/blobs/${sha}.rc`
      : source === 'local-url' ? 'https://ha.test/local/cards/kitchen.rc' : 'https://templates.test/kitchen.rc'));
    assert.equal(await page.evaluate(() => customElements.get('ha-card') === customElements.get('remote-compose-card')), false);
    await page.waitForFunction(() => card.pushed.get('USER:light.s0.state') === 'Off');
    const canvas = page.locator('remote-compose-card canvas');
    const before = await canvas.screenshot();
    const evidence = `${process.env.HA_PLUGIN_EVIDENCE ?? `${root}build/ha-plugin-evidence`}${source === 'library' ? '' : `/${source}`}`;
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
    await page.evaluate(({source, unsupportedSha}) => card.setConfig(source === 'library'
      ? {...card.config, design: unsupportedSha}
      : {...card.config, template_url: `/local/cards/${unsupportedSha}.rc`}), {source, unsupportedSha});
    await page.waitForFunction(() => card.dataset.status === 'error');
    assert.match(await page.locator('remote-compose-card .status').textContent(), /converter bindings unavailable/);
    assert.equal(await page.evaluate(() => card.player), null, 'never show fixture values from an incompatible template');
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
}));

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
    assert.deepEqual(await page.evaluate(() => {
      const panel = document.querySelector('terrazzo-panel');
      history.replaceState(null, '', '/terrazzo?dashboard=second-panel');
      const explicit = panel.frame.terrazzoHost.getDashboard();
      history.replaceState(null, '', '/terrazzo?dashboard=');
      const picker = panel.frame.terrazzoHost.getDashboard();
      history.replaceState(null, '', '/terrazzo');
      return {explicit, picker, configured: panel.frame.terrazzoHost.getDashboard()};
    }), {explicit:'second-panel', picker:'', configured:'wall-panel'});
    assert.equal(result.denied, true);
    assert.equal(result.reattached, result.path);
  } finally { await browser.close(); }
});

// A gauge's range, bands and range line are card-level bindings no slot provides: the template
// carries a binding recipe (`meta.binding_recipe`, CardBindingRecipe in ha-model) that computes
// them here. Captured by rc-converter's CardBindingRecipeTemplateTest; checked by design-server's
// RecipeFixtureTest.
const gaugeTemplate = readFileSync(`${root}design-server/src/test/resources/gauge-unit.rc`);
// The same document with its recipe renamed away: nothing then supplies `card.min` and friends.
const gaugeWithoutRecipe = Buffer.from(gaugeTemplate);
const recipeOffset = gaugeWithoutRecipe.indexOf(Buffer.from('meta.binding_recipe'));
assert.ok(recipeOffset >= 0, 'the gauge fixture carries a recipe');
Buffer.from('card.binding_recipe').copy(gaugeWithoutRecipe, recipeOffset);

test('HA custom card computes a gauge template\'s card-level bindings from its recipe', async () => {
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
      if (path.endsWith('.rc')) {
        return route.fulfill({ body: path.endsWith('no-recipe.rc') ? gaugeWithoutRecipe : gaugeTemplate, contentType: 'application/octet-stream' });
      }
      return route.fulfill({ contentType: 'text/html', body: `<!doctype html><meta charset="utf-8"><style>body{margin:24px}remote-compose-card{width:400px}</style>
        <script>customElements.define('ha-card', class extends HTMLElement {});</script>
        <script type="module" src="/terrazzo-static/remote-compose-card.js"></script>` });
    });
    await page.goto('https://ha.test/lovelace/0');
    await page.evaluate(async () => {
      await customElements.whenDefined('remote-compose-card');
      const card = document.createElement('remote-compose-card');
      card.setConfig({ type: 'custom:remote-compose-card', template_url: '/local/cards/gauge.rc',
        card: { type: 'gauge', entity: 'sensor.print_progress', name: 'Progress', min: 0, max: 100, severity: { green: 50, red: 90 } } });
      card.hass = { states: { 'sensor.print_progress': { state: '42', attributes: { unit_of_measurement: '%', friendly_name: 'Print progress' } } },
        themes: { darkMode: false }, user: { id: 'limited-user' }, callService: async () => {} };
      document.body.append(card);
      window.card = card;
    });
    await page.waitForFunction(() => card.dataset.status === 'live' && card.player);
    const pushed = await page.evaluate(() => Object.fromEntries(card.pushed));
    assert.equal(pushed['USER:card.min'], 0);
    assert.equal(pushed['USER:card.max'], 100);
    assert.equal(pushed['USER:card.severity_green'], 50);
    assert.equal(pushed['USER:card.severity_red'], 90);
    assert.equal(pushed['USER:card.severity_yellow'], Math.fround(1e30));
    assert.equal(pushed['USER:sensor.s0.numeric_state'], 42);
    assert.equal(pushed['USER:card.range'], '0 – 100 %');
    assert.equal(pushed['USER:sensor.s0.name'], 'Progress');
    // The sweep animates to a pushed value; let it settle before keeping the picture.
    await page.waitForTimeout(1500);
    const evidence = process.env.HA_PLUGIN_EVIDENCE ?? `${root}build/ha-plugin-evidence`;
    mkdirSync(evidence, { recursive: true });
    await page.screenshot({ path: `${evidence}/gauge-recipe.png` });
    // Live: a new state and a non-numeric one (which sits at min) both reach the template.
    const before = await page.locator('remote-compose-card canvas').screenshot();
    await page.evaluate(() => {
      card.hass = { ...card._hass, states: { 'sensor.print_progress': { state: '97', attributes: { unit_of_measurement: '%' } } } };
    });
    await page.waitForFunction(() => card.pushed.get('USER:sensor.s0.numeric_state') === 97);
    assert.notDeepEqual(await page.locator('remote-compose-card canvas').screenshot(), before);
    await page.evaluate(() => {
      card.hass = { ...card._hass, states: { 'sensor.print_progress': { state: 'unavailable', attributes: {} } } };
    });
    await page.waitForFunction(() => card.pushed.get('USER:sensor.s0.numeric_state') === 0);
    // Without the recipe the same template is rejected, never shown with the library's fixture values.
    await page.evaluate(() => card.setConfig({ ...card.config, template_url: '/local/cards/no-recipe.rc' }));
    await page.waitForFunction(() => card.dataset.status === 'error');
    assert.match(await page.locator('remote-compose-card .status').textContent(), /converter bindings unavailable/);
    assert.equal(await page.evaluate(() => card.player), null);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});
