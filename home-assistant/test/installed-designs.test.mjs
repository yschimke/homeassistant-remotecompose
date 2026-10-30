// After installed-push.test.mjs: the private card design server inside a real HA container. The app
// asks `terrazzo/design_server` whether this Home Assistant runs the card design add-on, and calls
// its API through /api/terrazzo/designs with its own token.
//
// CI's Home Assistant has no Supervisor, so there it checks the answer without one. With
// DESIGNS_ADDON=1 it expects the add-on (design-server/addon) to be reachable through a Supervisor,
// as design-server/addon/README.md's local test sets up.
import { test } from 'node:test';
import assert from 'node:assert/strict';

const base = process.env.HA_BASE ?? 'http://127.0.0.1:8123';
const withAddon = process.env.DESIGNS_ADDON === '1';
const client_id = `${base}/`;
const json = (body) => ({method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});

/** Tokens for [username], onboarding the instance first when nothing has yet. */
async function signIn(username, password, name) {
  // An instance onboarded before its last restart no longer serves the onboarding view.
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

/** One websocket command as [accessToken]; answers its result. */
async function command(accessToken, body) {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/api/websocket`);
  try {
    return await new Promise((resolve, reject) => {
      ws.onmessage = (event) => {
        const message = JSON.parse(event.data);
        if (message.type === 'auth_required') ws.send(JSON.stringify({type: 'auth', access_token: accessToken}));
        else if (message.type === 'auth_ok') ws.send(JSON.stringify({id: 1, ...body}));
        else if (message.type === 'auth_invalid') reject(new Error('auth_invalid'));
        else if (message.type === 'result') resolve(message);
      };
    });
  } finally {
    ws.close();
  }
}

test('the app finds the private design server and reaches only its API', async () => {
  const tokens = await signIn('panel', 'fixture-only-password', 'Test panel user');
  const auth = {Authorization: `Bearer ${tokens.access_token}`};
  const designs = `${base}/api/terrazzo/designs`;

  const found = await command(tokens.access_token, {type: 'terrazzo/design_server'});
  assert.equal(found.success, true);
  assert.equal(
    (await fetch(`${designs}/designs/v1/manifest.json`)).status, 401, 'only for a signed-in user');
  // UI Builder and pages are the ingress panel's, never the token's.
  for (const path of ['api/ui-builder/v1/identity', 'ui-builder/designs', 'my', 'designs/v1/../../my']) {
    assert.equal((await fetch(`${designs}/${path}`, {headers: auth})).status, 404, path);
  }

  if (!withAddon) {
    assert.deepEqual(found.result, {available: false});
    assert.equal((await fetch(`${designs}/designs/v1/manifest.json`, {headers: auth})).status, 404);
    return;
  }

  assert.equal(found.result.available, true);
  assert.equal(found.result.url, '/api/terrazzo/designs');
  assert.match(found.result.panel, /^\/hassio\/ingress\/[a-z0-9]+_card_designs$/);

  const manifest = await fetch(`${designs}/designs/v1/manifest.json`, {headers: auth});
  assert.equal(manifest.status, 200);
  assert.ok((await manifest.json()).designs.length > 0, 'the library is served');
  // Signed in as the Home Assistant user, so their own designs answer (none yet).
  const mine = await fetch(`${designs}/api/my/designs`, {headers: auth});
  assert.equal(mine.status, 200, 'signed in as panel');
});

test('a one-time link signs a browser in to the add-on and opens a design page', async () => {
  const tokens = await signIn('panel', 'fixture-only-password', 'Test panel user');
  const refused = await command(tokens.access_token,
    {type: 'terrazzo/design_server/open', path: '/api/ui-builder/v1/identity'});
  assert.equal(refused.success, false, 'only pages');

  const link = await command(tokens.access_token,
    {type: 'terrazzo/design_server/open', path: '/ui-builder/card-x'});
  assert.equal(link.success, true);
  assert.match(link.result.url, /^\/api\/terrazzo\/designs-open\/[\w-]{40,}$/);

  const opened = await fetch(`${base}${link.result.url}`, {redirect: 'manual'});
  if (!withAddon) {
    assert.equal(opened.status, 404, 'no add-on to open');
    return;
  }
  assert.equal(opened.status, 302);
  assert.match(opened.headers.get('location'), /^\/api\/hassio_ingress\/[^/]+\/ui-builder\/card-x$/);
  assert.match(opened.headers.get('set-cookie'), /^ingress_session=[^;]+;.*Path=\/api\/hassio_ingress\//i);
  assert.equal((await fetch(`${base}${link.result.url}`, {redirect: 'manual'})).status, 410, 'once');
});
