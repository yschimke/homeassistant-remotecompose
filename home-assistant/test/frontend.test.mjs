import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {existsSync, readFileSync, mkdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const playerPath = process.env.RC_PLAYER_JS ?? `${root}design-server/build/generated/embed/embed/v1/rc-player.js`;
const wasmRoot = `${root}app-ui/build/dist/wasmJs/productionExecutable/`;
const template = readFileSync(`${root}design-server/src/test/resources/tile-light.rc`);
const sha = createHash('sha256').update(template).digest('hex');
let chromium;
try { ({chromium} = await import(new URL('../../embed/node_modules/playwright/index.mjs', import.meta.url))); } catch {}
const skip = !chromium || !existsSync(playerPath);
if (skip && process.env.HA_BROWSER_REQUIRED) throw new Error('Build the embed player and install Playwright');

async function open(browser, nativeCard = true) {
  const page = await browser.newPage({viewport: {width: 1200, height: 800}, deviceScaleFactor: 1});
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('http://ha.test/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/') return route.fulfill({contentType: 'text/html', body: `<!doctype html><body>
      <script>${nativeCard ? "customElements.define('ha-card', class extends HTMLElement {});" : ''} window.nativeCard = customElements.get('ha-card');</script>
      <script type="module" src="/terrazzo_static/terrazzo.js"></script></body>`});
    let file;
    if (path === '/terrazzo_static/terrazzo.js') file = `${root}home-assistant/custom_components/terrazzo/frontend/terrazzo.js`;
    else if (path === '/terrazzo_static/embed/ha-card.js') file = `${root}embed/ha-card.js`;
    else if (path === '/terrazzo_static/embed/rc-player.js') file = playerPath;
    else if (path === `/designs/v1/blobs/${sha}.rc`) return route.fulfill({body: template, contentType: 'application/octet-stream'});
    else if (path.startsWith('/terrazzo_static/wasm/')) file = wasmRoot + path.slice('/terrazzo_static/wasm/'.length);
    if (!file || !existsSync(file)) return route.fulfill({status: 404});
    return route.fulfill({body: readFileSync(file), contentType: file.endsWith('.wasm') ? 'application/wasm' :
      file.endsWith('.html') ? 'text/html' : file.endsWith('.js') || file.endsWith('.mjs') ? 'text/javascript' : 'application/octet-stream'});
  });
  await page.goto('http://ha.test/');
  await page.waitForFunction(() => customElements.get('terrazzo-card'));
  return {page, errors};
}

test('Lovelace card uses the real TS player, live host values, and current-session actions', {skip}, async () => {
  const browser = await chromium.launch({args: ['--no-sandbox']});
  try {
    const {page, errors} = await open(browser);
    const sockets = [];
    page.on('websocket', socket => sockets.push(socket.url()));
    await page.evaluate(sha => {
      window.calls = [];
      window.card = document.createElement('terrazzo-card');
      window.hass = {states: {'light.kitchen': {state: 'off', attributes: {friendly_name: 'Kitchen'}}},
        callService: async (...args) => calls.push(args)};
      card.hass = hass;
      card.setConfig({card: {type: 'tile', entity: 'light.kitchen'}, design: sha, library: '/designs/v1/'});
      card.style.width = '380px';
      document.body.append(card);
    }, sha);
    await page.waitForFunction(() => card.surface.dataset.status === 'live');
    assert.equal(await page.evaluate(() => card.surface.player.getRemoteContext().getNamedString?.('USER:light.s0.name') ?? card.surface.pushed.get('USER:light.s0.name')), 'Kitchen');
    assert.equal(await page.evaluate(() => card.surface.pushed.get('USER:light.s0.is_on')), 0);
    await page.evaluate(() => {
      card.hass = {...hass, states: {'light.kitchen': {state: 'on', attributes: {friendly_name: 'Updated kitchen'}}}};
    });
    assert.equal(await page.evaluate(() => card.surface.pushed.get('USER:light.s0.is_on')), 1);
    assert.equal(await page.evaluate(() => card.surface.pushed.get('USER:light.s0.name')), 'Updated kitchen');
    await page.evaluate(() => card.surface.tap({kind: 'Toggle', entityId: 'light.kitchen'}));
    assert.deepEqual(await page.evaluate(() => calls), []);
    await page.evaluate(() => { card.config.read_only = false; return card.surface.tap({kind: 'Toggle', entityId: 'light.kitchen'}); });
    assert.deepEqual(await page.evaluate(() => calls), [['homeassistant', 'toggle', {}, {entity_id: 'light.kitchen'}]]);
    assert.equal(await page.evaluate(() => customElements.get('ha-card') === nativeCard), true);
    assert.deepEqual(sockets, []);
    assert.deepEqual(errors, []);
    mkdirSync(`${root}_wasm-test`, {recursive: true});
    await page.screenshot({path: `${root}_wasm-test/ha-ts-card.png`});
  } finally { await browser.close(); }
});

test('integration never claims HA native card name, including before HA loads it', {skip}, async () => {
  const browser = await chromium.launch({args: ['--no-sandbox']});
  try {
    const {page, errors} = await open(browser, false);
    assert.equal(await page.evaluate(() => customElements.get('ha-card')), undefined);
    await page.evaluate(() => customElements.define('ha-card', class extends HTMLElement {}));
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});

test('HA panel renders Lovelace in the actual WASM app and updates live values', {skip: skip || !existsSync(wasmRoot)}, async () => {
  const browser = await chromium.launch({args: ['--no-sandbox']});
  try {
    const {page, errors} = await open(browser);
    await page.route('https://ha.coo.ee/**', route => route.abort());
    await page.evaluate(() => {
      window.commands = [];
      window.panel = document.createElement('terrazzo-panel');
      panel.style.cssText = 'height:780px;display:block';
      panel.panel = {config: {dashboard: 'lovelace-demo'}};
      window.hass = {auth: {accessToken: 'must-never-cross-the-bridge'},
        states: {'sensor.room': {state: '21.5', attributes: {friendly_name: 'Room temperature', unit_of_measurement: '°C'}}},
        callWS: async command => { commands.push(command); return {title: 'Wall home', views: [{title: 'Home', cards: [{type: 'tile', entity: 'sensor.room'}]}]}; }};
      panel.hass = hass;
      document.body.append(panel);
      window.messages = [];
      window.addEventListener('message', event => messages.push(event.data));
    });
    const frame = page.frameLocator('iframe');
    await frame.getByText('21.5 °C', {exact: true}).waitFor({timeout: 30000});
    await page.evaluate(() => { panel.hass = {...hass, states: {'sensor.room': {state: '22.8', attributes: {friendly_name: 'Room temperature', unit_of_measurement: '°C'}}}}; });
    await frame.getByText('22.8 °C', {exact: true}).waitFor();
    assert.deepEqual(await page.evaluate(() => commands), [{type: 'lovelace/config', url_path: 'lovelace-demo'}]);
    assert.equal(await page.evaluate(() => JSON.stringify(messages).includes('must-never-cross')), false);
    await page.screenshot({path: `${root}_wasm-test/ha-wasm-dashboard.png`});
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});
