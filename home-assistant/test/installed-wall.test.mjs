// The wall display API (docs/architecture/wall-display-api.md) inside a real HA container, with a
// device's long-lived access token in a header and nothing else.
//
// CI's Home Assistant has no design server, so there it checks the dashboard list, the grid, taps
// and the 503 for values. With WALL_DESIGN_SERVER=1 it expects one at TERRAZZO_DESIGN_SERVER_URL
// (HA's environment) and checks designs, blobs and a long poll that ends on a tap.
import { test } from 'node:test';
import assert from 'node:assert/strict';

const base = process.env.HA_BASE ?? 'http://127.0.0.1:8123';
const withDesigns = process.env.WALL_DESIGN_SERVER === '1';
const client_id = `${base}/`;
const wall = `${base}/api/terrazzo/wall/v1`;
const json = (body) => ({method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});

/** Tokens for [username], onboarding the instance first when nothing has yet. */
async function signIn(username, password, name) {
  const status = await fetch(`${base}/api/onboarding`);
  const onboarding = status.ok ? await status.json() : [];
  if (onboarding.some((step) => step.step === 'user' && !step.done)) {
    const {auth_code} = await (await fetch(`${base}/api/onboarding/users`,
      json({client_id, name, username, password, language: 'en'}))).json();
    const tokens = await token({code: auth_code});
    const headers = {'Content-Type': 'application/json', Authorization: `Bearer ${tokens.access_token}`};
    for (const step of ['core_config', 'analytics']) {
      await fetch(`${base}/api/onboarding/${step}`, {method: 'POST', headers, body: '{}'});
    }
    await fetch(`${base}/api/onboarding/integration`,
      {method: 'POST', headers, body: JSON.stringify({client_id, redirect_uri: client_id})});
    return tokens;
  }
  const flow = await (await fetch(`${base}/auth/login_flow`,
    json({client_id, handler: ['homeassistant', null], redirect_uri: client_id}))).json();
  const login = await (await fetch(`${base}/auth/login_flow/${flow.flow_id}`,
    json({client_id, username, password}))).json();
  assert.equal(login.type, 'create_entry', `${username} signs in`);
  return token({code: login.result});
}

async function token({code}) {
  const form = new URLSearchParams({client_id, code, grant_type: 'authorization_code'});
  return (await fetch(`${base}/auth/token`, {method: 'POST', body: form})).json();
}

/** A long-lived access token, as a person makes one for a display under their profile. */
async function longLivedToken(accessToken) {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/api/websocket`);
  try {
    return await new Promise((resolve, reject) => {
      ws.onmessage = (event) => {
        const message = JSON.parse(event.data);
        if (message.type === 'auth_required') ws.send(JSON.stringify({type: 'auth', access_token: accessToken}));
        else if (message.type === 'auth_ok') {
          ws.send(JSON.stringify({id: 1, type: 'auth/long_lived_access_token', client_name: `wall-${Date.now()}`, lifespan: 1}));
        } else if (message.type === 'auth_invalid') reject(new Error('auth_invalid'));
        else if (message.type === 'result') message.success ? resolve(message.result) : reject(new Error(JSON.stringify(message.error)));
      };
    });
  } finally {
    ws.close();
  }
}

async function state(auth, entity) {
  return (await (await fetch(`${base}/api/states/${entity}`, {headers: auth})).json()).state;
}

test('a wall display lists, lays out and taps with a long-lived token in a header', async () => {
  const tokens = await signIn('panel', 'fixture-only-password', 'Test panel user');
  const auth = {Authorization: `Bearer ${await longLivedToken(tokens.access_token)}`};

  assert.equal((await fetch(`${wall}/dashboards`)).status, 401, 'only with a token');

  const {dashboards} = await (await fetch(`${wall}/dashboards`, {headers: auth})).json();
  assert.equal(dashboards[0].url_path, 'wall-panel', 'the chosen wall dashboard comes first');
  assert.equal(dashboards[0].default, true);
  assert.ok(dashboards.some((d) => d.url_path === 'second-panel'));

  const response = await fetch(`${wall}/dashboards/wall-panel`, {headers: auth});
  assert.equal(response.status, 200);
  const layout = await response.json();
  const cards = layout.views[0].sections[0].cards;
  assert.deepEqual(cards.map((c) => [c.id, c.type, c.entities]), [
    ['v0.c0', 'entities', ['input_boolean.wall_light']],
    // The Remote Compose card is the tile it wraps.
    ['v0.c1', 'tile', ['light.wall_light']],
  ]);
  assert.equal(response.headers.get('etag'), `"${layout.etag}"`);
  assert.equal((await fetch(`${wall}/dashboards/wall-panel`,
    {headers: {...auth, 'If-None-Match': `"${layout.etag}"`}})).status, 304);
  assert.equal((await fetch(`${wall}/dashboards/nope`, {headers: auth})).status, 404);

  // A tap on the entities card runs its first row's default: a toggle.
  const before = await state(auth, 'input_boolean.wall_light');
  const tap = await (await fetch(`${wall}/actions/wall-panel/v0.c0`, {method: 'POST', headers: auth})).json();
  assert.equal(tap.ran, 'homeassistant.toggle');
  assert.notEqual(await state(auth, 'input_boolean.wall_light'), before);
  // A template's own service is never run: it only names the slot.
  const evil = JSON.stringify({type: 'ee.schimke.ha.rc.components.HaAction.CallService', domain: 'homeassistant',
    service: 'stop', entityId: 'light.s0'});
  const slot = await (await fetch(`${wall}/actions/wall-panel/v0.c1`,
    {method: 'POST', headers: {...auth, 'Content-Type': 'application/json'}, body: JSON.stringify({action: evil})})).json();
  assert.equal(slot.ran, 'homeassistant.toggle');
  assert.deepEqual(slot.action, {kind: 'Toggle', entity_id: 'light.wall_light'});

  if (!withDesigns) {
    assert.equal((await fetch(`${wall}/values/wall-panel?wait=0`, {headers: auth})).status, 503);
    return;
  }

  const first = await (await fetch(`${wall}/values/wall-panel?wait=0`, {headers: auth})).json();
  assert.equal(first.etag, layout.etag);
  const tile = first.cards['v0.c1'];
  assert.match(tile.design, /^[0-9a-f]{64}$/, JSON.stringify(first));
  assert.equal(typeof tile.values['light.s0.is_on'], 'boolean');
  const blob = await fetch(`${base}${tile.rc}`, {headers: auth});
  assert.equal(blob.status, 200);
  assert.match(blob.headers.get('cache-control'), /immutable/);
  assert.ok((await blob.arrayBuffer()).byteLength > 100, 'a document');

  // Nothing changed: an immediate answer is empty.
  const quiet = await (await fetch(`${wall}/values/wall-panel?since=${first.cursor}&wait=0`, {headers: auth})).json();
  assert.deepEqual(quiet.cards, {});

  // A long poll ends on the tap's state change, with only the values that changed.
  const started = Date.now();
  // Two displays on one user and look poll the same card: one resolve serves both, and neither
  // misses the change behind the cursor it gets.
  const longPoll = () => fetch(`${wall}/values/wall-panel?cards=v0.c1&since=${first.cursor}&wait=30`, {headers: auth}).then((r) => r.json());
  const polls = [longPoll(), longPoll()];
  await new Promise((resolve) => setTimeout(resolve, 500));
  await fetch(`${wall}/actions/wall-panel/v0.c1`, {method: 'POST', headers: auth});
  const [changed, other] = await Promise.all(polls);
  assert.ok(Date.now() - started < 15000, 'answered on the change, not the timeout');
  assert.deepEqual(other.cards, changed.cards, 'the second display gets the change too');
  const update = changed.cards['v0.c1'];
  assert.ok(update, JSON.stringify(changed));
  const isOn = update.values?.['light.s0.is_on'];
  assert.equal(isOn, !tile.values['light.s0.is_on'], JSON.stringify(update));
  assert.ok(!('light.s0.name' in update.values) || update.design, 'only what changed');
});

/** A PNG's width and height, from its IHDR. */
function pngSize(bytes) {
  const view = new DataView(bytes);
  return [view.getUint32(16), view.getUint32(20)];
}

test('a wall display gets a slot picture scaled for it, and the fonts designs name', async () => {
  const tokens = await signIn('panel', 'fixture-only-password', 'Test panel user');
  const auth = {Authorization: `Bearer ${await longLivedToken(tokens.access_token)}`};
  const picture = `${wall}/pictures/wall-pictures/v0.c0/sensor.s0`;

  assert.equal((await fetch(picture)).status, 401, 'only with a token');
  const original = await fetch(picture, {headers: auth});
  assert.equal(original.status, 200);
  assert.equal(original.headers.get('content-type'), 'image/png', 'as it came when nothing is asked');
  assert.deepEqual(pngSize(await original.arrayBuffer()), [400, 300]);

  const contain = await fetch(`${picture}?w=100&h=100&format=png`, {headers: auth});
  assert.deepEqual(pngSize(await contain.arrayBuffer()), [100, 75], 'all of it inside the box');
  const cover = await fetch(`${picture}?w=100&h=100&fit=cover&format=png`, {headers: auth});
  assert.deepEqual(pngSize(await cover.arrayBuffer()), [100, 100], 'the box filled');
  const wide = await fetch(`${picture}?w=200`, {headers: auth});
  assert.equal(wide.headers.get('content-type'), 'image/jpeg', 'scaled pictures are jpeg by default');
  const etag = wide.headers.get('etag');
  assert.equal((await fetch(`${picture}?w=200`, {headers: {...auth, 'If-None-Match': etag}})).status, 304);

  for (const [path, status] of [
    [`${wall}/pictures/wall-pictures/v0.c0/sensor.s1`, 404],
    [`${wall}/pictures/wall-pictures/v0.c0/light.s0`, 404],
    [`${wall}/pictures/wall-panel/v0.c1/light.s0`, 404],
    [`${picture}?w=0`, 400],
    [`${picture}?format=gif`, 400],
  ]) {
    assert.equal((await fetch(path, {headers: auth})).status, status, path);
  }

  if (!withDesigns) return;

  const values = await (await fetch(`${wall}/values/wall-pictures?wait=0`, {headers: auth})).json();
  const card = values.cards['v0.c0'];
  assert.ok(card?.values, JSON.stringify(values));
  assert.match(card.values['sensor.s0.picture_url'], /^\/api\/terrazzo\/wall\/v1\/pictures\/wall-pictures\/v0\.c0\/sensor\.s0\?v=/);

  const fonts = await (await fetch(`${wall}/fonts/fonts.json`, {headers: auth})).json();
  const face = fonts.families.find((f) => f.name === 'digital7')?.fonts[0];
  assert.ok(face, JSON.stringify(fonts));
  const font = await fetch(`${base}${face.url}`, {headers: auth});
  assert.equal(font.status, 200);
  assert.equal(font.headers.get('content-type'), 'font/ttf');
  assert.match(font.headers.get('cache-control'), /immutable/);
  const digest = Buffer.from(await crypto.subtle.digest('SHA-256', await font.arrayBuffer())).toString('hex');
  assert.equal(digest, face.sha256);
});
