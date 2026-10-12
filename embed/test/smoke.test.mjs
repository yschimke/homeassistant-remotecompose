// End to end in headless Chromium: <ha-card> loads the real player and a real template, signs in
// with HA's OAuth, subscribes over a stubbed Home Assistant WebSocket, draws the pushed values
// and sends a tap as call_service. Nothing leaves the browser: every origin is routed.
//
//   node --test embed/test/*.test.mjs
//
// Needs Playwright (`npm i playwright` in embed/, or PLAYWRIGHT_MODULE=<path to its index.mjs>)
// and the player bundle: `./gradlew :design-server:embedResources`, or RC_PLAYER_JS=<bundle.js>.
// Skipped when either is missing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const playerPath =
  process.env.RC_PLAYER_JS ?? `${root}design-server/build/generated/embed/embed/v1/rc-player.js`;
const template = readFileSync(`${root}design-server/src/test/resources/tile-light.rc`);
const sha = createHash('sha256').update(template).digest('hex');

let chromium = null;
try {
  ({ chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright'));
} catch {
  // Reported by the skip below.
}
const skip = !chromium ? 'playwright is not installed' : !existsSync(playerPath) ? `no player at ${playerPath}` : false;
// CI sets this so a missing dependency fails rather than skips.
if (skip && process.env.EMBED_SMOKE_REQUIRED) throw new Error(`smoke test cannot run: ${skip}`);

const PAGE = 'https://page.test/card.html';
const HASS = 'https://ha.test';
const card = { type: 'tile', entity: 'light.kitchen' };

/** A page on page.test embedding the card, with scripts and blobs from designs.test. */
async function openPage(browser, { tokens, url = PAGE, setup, cardConfig = card, failFirst = 0 } = {}) {
  const page = await browser.newPage({ deviceScaleFactor: 1, viewport: { width: 480, height: 320 } });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  if (process.env.SMOKE_DEBUG) page.on('console', (m) => console.log('page:', m.text()));
  if (process.env.SMOKE_DEBUG) page.on('requestfailed', (r) => console.log('failed:', r.url(), r.failure()?.errorText));
  const cors = { 'Access-Control-Allow-Origin': '*' };
  await page.route('https://page.test/**', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: `<!doctype html><body style="margin:0"><script type="module" src="https://designs.test/embed/v1/ha-card.js"></script>
        <ha-card style="width:400px" hass="${HASS}" design="${sha}" library="https://designs.test/designs/v1/"
                 card='${JSON.stringify(cardConfig)}'></ha-card></body>`,
    }),
  );
  await page.route('https://designs.test/**', (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/embed/v1/ha-card.js') {
      return route.fulfill({ contentType: 'text/javascript', headers: cors, body: readFileSync(`${root}embed/ha-card.js`) });
    }
    if (path === '/embed/v1/rc-player.js') {
      return route.fulfill({ contentType: 'text/javascript', headers: cors, body: readFileSync(playerPath) });
    }
    if (path === `/designs/v1/blobs/${sha}.rc`) {
      return route.fulfill({ contentType: 'application/octet-stream', headers: cors, body: template });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
  if (tokens) {
    await page.addInitScript(([key, value]) => localStorage.setItem(key, value), [
      `ha-card-auth:${HASS}`,
      JSON.stringify(tokens),
    ]);
  }
  const ha = await stubHomeAssistant(page, failFirst);
  if (setup) await setup(page);
  await page.goto(url);
  return { page, ha, errors };
}

/** Home Assistant's WebSocket API, as far as the card uses it; records what the card sends. */
async function stubHomeAssistant(page, failFirst = 0) {
  const ha = { sent: [], socket: null, subscription: null, connections: 0 };
  await page.routeWebSocket('wss://ha.test/api/websocket', (ws) => {
    // The first [failFirst] connections are refused, as by a Home Assistant still starting.
    if (++ha.connections <= failFirst) return ws.close();
    ha.socket = ws;
    ws.send(JSON.stringify({ type: 'auth_required', ha_version: '2026.10.0' }));
    ws.onMessage((raw) => {
      const msg = JSON.parse(raw);
      ha.sent.push(msg);
      if (msg.type === 'auth') {
        ws.send(JSON.stringify(msg.access_token === 'live-token' ? { type: 'auth_ok' } : { type: 'auth_invalid', message: 'bad' }));
      } else if (msg.type === 'subscribe_entities') {
        ha.subscription = msg.id;
        ws.send(JSON.stringify({ id: msg.id, type: 'result', success: true, result: null }));
        ws.send(JSON.stringify({
          id: msg.id,
          type: 'event',
          event: { a: { 'light.kitchen': { s: 'on', a: { friendly_name: 'Kitchen Light' }, c: 'c1', lc: 1, lu: 1 } } },
        }));
      } else if (msg.type === 'call_service') {
        ws.send(JSON.stringify({ id: msg.id, type: 'result', success: true, result: { context: { id: 'c2' } } }));
        ws.send(JSON.stringify({ id: ha.subscription, type: 'event', event: { c: { 'light.kitchen': { '+': { s: 'off', lc: 2, lu: 2 } } } } }));
      }
    });
  });
  return ha;
}

/** The text the player draws for a named string binding (its paint cache). */
function drawnText(page, name) {
  return page.evaluate((n) => {
    const el = document.querySelector('ha-card');
    const ctx = el.player.getRemoteContext();
    const variable = ctx.namedVariables.get(`USER:${n}`);
    return variable ? ctx.getText(variable.id) : null;
  }, name);
}

const liveTokens = {
  hassUrl: HASS,
  clientId: 'https://page.test/',
  access_token: 'live-token',
  refresh_token: 'refresh',
  expires: Date.now() + 3_600_000,
};

test('a signed-in viewer sees live values and taps call services', { skip }, async () => {
  const browser = await chromium.launch();
  try {
    const { page, ha, errors } = await openPage(browser, { tokens: liveTokens });
    await page.waitForFunction(() => document.querySelector('ha-card')?.dataset.status === 'live');
    await page.waitForFunction(() => document.querySelector('ha-card').states['light.kitchen']);

    assert.deepEqual(ha.sent.find((m) => m.type === 'subscribe_entities').entity_ids, ['light.kitchen']);
    assert.equal(await drawnText(page, 'light.s0.name'), 'Kitchen Light');
    assert.equal(await drawnText(page, 'light.s0.state'), 'On');
    if (process.env.SMOKE_SCREENSHOT) {
      await page.waitForTimeout(600); // the layout animates to the new text's size
      await page.screenshot({ path: process.env.SMOKE_SCREENSHOT });
    }

    const box = await page.locator('ha-card canvas').boundingBox();
    assert.ok(box.height > 40 && box.height < 200, `the canvas fits the card (${box.height}px)`);
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForFunction(() => document.querySelector('ha-card').states['light.kitchen'].state === 'off');

    const call = ha.sent.find((m) => m.type === 'call_service');
    assert.deepEqual(
      { domain: call.domain, service: call.service, target: call.target },
      { domain: 'homeassistant', service: 'toggle', target: { entity_id: 'light.kitchen' } },
    );
    assert.equal(await drawnText(page, 'light.s0.state'), 'Off');
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});

test('a signed-out viewer is offered sign-in, and the OAuth callback signs them in', { skip }, async () => {
  const browser = await chromium.launch();
  try {
    const { page } = await openPage(browser);
    await page.waitForFunction(() => document.querySelector('ha-card')?.dataset.status === 'signed-out');
    const button = page.locator('ha-card button');
    assert.match(await button.textContent(), /Connect to Home Assistant \(ha\.test\)/);

    // The button goes to HA's authorize page with this page as the OAuth client.
    let authorize = null;
    await page.route('https://ha.test/auth/authorize**', (route) => {
      authorize = new URL(route.request().url());
      return route.fulfill({ contentType: 'text/html', body: 'sign in' });
    });
    await button.click();
    await page.waitForURL('https://ha.test/auth/authorize**');
    assert.equal(authorize.searchParams.get('client_id'), 'https://page.test/');
    assert.equal(authorize.searchParams.get('redirect_uri'), 'https://page.test/card.html?ha_card_auth=1');
    const state = authorize.searchParams.get('state');

    // HA redirects back with a code; the card exchanges it and goes live.
    let tokenRequest = null;
    const back = await openPage(browser, {
      url: `${PAGE}?ha_card_auth=1&code=the-code&state=${encodeURIComponent(state)}`,
      setup: (p) =>
        p.route('https://ha.test/auth/token', (route) => {
          tokenRequest = new URLSearchParams(route.request().postData());
          return route.fulfill({
            headers: { 'Access-Control-Allow-Origin': '*' },
            contentType: 'application/json',
            body: JSON.stringify({ access_token: 'live-token', refresh_token: 'refresh', expires_in: 1800, token_type: 'Bearer' }),
          });
        }),
    });
    await back.page.waitForFunction(() => document.querySelector('ha-card')?.dataset.status === 'live');
    assert.equal(tokenRequest.get('grant_type'), 'authorization_code');
    assert.equal(tokenRequest.get('code'), 'the-code');
    assert.equal(tokenRequest.get('client_id'), 'https://page.test/');
    assert.equal(back.page.url(), PAGE, 'the callback parameters are cleared');
  } finally {
    await browser.close();
  }
});

test('a card with no entities subscribes to nothing', { skip }, async () => {
  const browser = await chromium.launch();
  try {
    // Home Assistant reads an empty entity_ids as no filter, i.e. every entity.
    const { page, ha } = await openPage(browser, { tokens: liveTokens, cardConfig: { type: 'tile' } });
    await page.waitForFunction(() => document.querySelector('ha-card')?.dataset.status === 'live');
    await page.waitForTimeout(300);
    assert.equal(ha.sent.find((m) => m.type === 'subscribe_entities'), undefined);
  } finally {
    await browser.close();
  }
});

test('Home Assistant unreachable at load is retried', { skip }, async () => {
  const browser = await chromium.launch();
  try {
    const { page, ha } = await openPage(browser, { tokens: liveTokens, failFirst: 1 });
    await page.waitForFunction(() => document.querySelector('ha-card')?.dataset.status === 'reconnecting');
    await page.waitForFunction(() => document.querySelector('ha-card').dataset.status === 'live', null, { timeout: 5000 });
    assert.equal(ha.connections, 2);
    assert.deepEqual(ha.sent.find((m) => m.type === 'subscribe_entities').entity_ids, ['light.kitchen']);
  } finally {
    await browser.close();
  }
});

test('local states detach the card from Home Assistant', { skip }, async () => {
  const browser = await chromium.launch();
  try {
    const { page, ha } = await openPage(browser, { tokens: liveTokens });
    await page.waitForFunction(() => document.querySelector('ha-card')?.dataset.status === 'live');
    await page.waitForFunction(() => document.querySelector('ha-card').states['light.kitchen']);
    await page.evaluate(() =>
      document.querySelector('ha-card').setStates({ 'light.kitchen': { state: 'off', attributes: { friendly_name: 'Local' } } }),
    );
    // Home Assistant's later events no longer reach the card…
    ha.socket.send(JSON.stringify({ id: ha.subscription, type: 'event', event: { c: { 'light.kitchen': { '+': { s: 'on' } } } } }));
    await page.waitForTimeout(300);
    assert.equal(await page.evaluate(() => document.querySelector('ha-card').states['light.kitchen'].state), 'off');
    assert.ok(ha.sent.some((m) => m.type === 'unsubscribe_events' && m.subscription === ha.subscription));
    // …and a tap calls no service.
    const box = await page.locator('ha-card canvas').boundingBox();
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await page.waitForTimeout(300);
    assert.equal(ha.sent.find((m) => m.type === 'call_service'), undefined);
  } finally {
    await browser.close();
  }
});
