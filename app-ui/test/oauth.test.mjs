import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { webcrypto } from 'node:crypto';

const source = readFileSync(new URL('../src/wasmJsMain/resources/ha-oauth.js', import.meta.url), 'utf8');
const backendSource = readFileSync(new URL('../src/wasmJsMain/resources/terrazzo-backend.js', import.meta.url), 'utf8');
const fixtureTokens = () => ({ hassUrl: 'https://ha.example.test', clientId: 'https://app.example.test/', refresh_token: 'fixture-refresh', access_token: 'fixture-access', expires: Date.now() + 3600000 });
function storage(seed = {}) {
  const map = new Map(Object.entries(seed));
  return { getItem: key => map.get(key) ?? null, setItem: (key, value) => map.set(key, String(value)), removeItem: key => map.delete(key) };
}
function service() {
  return { session: null, initialized: false, fail: null, calls: [], onImport: null };
}
function page({ cloud = true, server = service(), local = storage(), search = '', pending = null, tokenFailure = false } = {}) {
  const sessionStorage = storage(pending ? { 'terrazzo-oauth-pending': JSON.stringify(pending) } : {});
  const location = { protocol: 'https:', host: 'app.example.test', origin: 'https://app.example.test', pathname: '/app.html', search, href: `https://app.example.test/app.html${search}`, reload: () => { location.reloaded = true; }, assign: url => { location.assigned = url; } };
  const window = { frameElement: null };
  const context = { window, location, localStorage: local, sessionStorage, crypto: webcrypto, URL, URLSearchParams, Headers, Response, AbortController, setTimeout, clearTimeout,
    console: { warn() {} }, atob, btoa, history: { state: {}, replaceState: (_state, _unused, url) => { location.href = url; } },
    fetch: async (url, options = {}) => {
      server.calls.push({ url, options });
      if (url.endsWith('/capabilities')) return Response.json({ api: 1, kv: true, haSession: cloud, publish: false });
      if (url === 'https://ha.example.test/auth/token') return tokenFailure ? Response.json({}, { status: 400 }) : Response.json({ access_token: 'fixture-new-access', refresh_token: 'fixture-refresh', expires_in: 1800 });
      if (server.fail) return Response.json({}, { status: server.fail });
      const value = options.body ? JSON.parse(options.body) : null;
      if (url.endsWith('/ha/session') && options.method === 'GET') return Response.json(server.session ?? { signedIn: false, sessionId: server.initialized ? 'tombstone' : '' });
      if (url.endsWith('/ha/session/import') && server.importStatus) return Response.json({}, { status: server.importStatus });
      if (url.endsWith('/ha/session/import') || (url.endsWith('/ha/session') && options.method === 'PUT')) {
        if (url.endsWith('/import') && server.initialized) return Response.json({}, { status: 409 });
        server.onImport?.();
        server.initialized = true;
        server.session = { signedIn: true, hassUrl: value.hassUrl, sessionId: 'fixture-session' };
        return Response.json(server.session);
      }
      if (url.endsWith('/ha/token')) {
        if (!server.session || value.sessionId !== server.session.sessionId) return Response.json({}, { status: 401 });
        return Response.json({ ...server.session, access_token: 'fixture-cloud-access', expires: Date.now() + 1800000 });
      }
      if (url.endsWith('/ha/session') && options.method === 'DELETE') {
        if (value.sessionId !== server.session?.sessionId) return Response.json({}, { status: 409 });
        server.session = null;
        server.initialized = true;
        return Response.json({ signedIn: false });
      }
      throw new Error('Unexpected fixture request');
    },
  };
  runInNewContext(backendSource, context);
  runInNewContext(source, context);
  return { auth: window.terrazzoOAuth, local, location, server, sessionStorage };
}

test('cloud migrates legacy refresh once, deletes only after acknowledgement and never persists cloud access tokens', async () => {
  const local = storage({ 'terrazzo-oauth': JSON.stringify(fixtureTokens()), unrelated: 'keep' });
  const instance = page({ local });
  instance.server.onImport = () => assert.ok(local.getItem('terrazzo-oauth'));
  assert.equal(await instance.auth.restore(), 'https://ha.example.test');
  assert.equal(local.getItem('terrazzo-oauth'), null);
  assert.equal(local.getItem('unrelated'), 'keep');
  const imported = instance.server.calls.find(call => call.url.endsWith('/ha/session/import'));
  assert.equal(JSON.parse(imported.options.body).refreshToken, 'fixture-refresh');
  assert.equal(imported.options.credentials, 'same-origin');
  assert.equal(imported.options.redirect, 'error');
  assert.equal(imported.options.headers.get('X-Wasmo-Api'), '1');
  assert.equal(await instance.auth.accessToken(), 'fixture-cloud-access');
  assert.equal(local.getItem('terrazzo-oauth'), null);
  assert.match(instance.auth.sessionLabel(), /all devices/);
  const secondDevice = page({ server: instance.server });
  assert.equal(await secondDevice.auth.restore(), 'https://ha.example.test');
  assert.equal(await secondDevice.auth.accessToken(), 'fixture-cloud-access');
});

test('cloud failures preserve legacy credentials and fail without falling back to local tokens', async () => {
  const raw = JSON.stringify(fixtureTokens());
  const instance = page({ local: storage({ 'terrazzo-oauth': raw }) });
  await instance.auth.restore();
  instance.server.fail = 503;
  await assert.rejects(instance.auth.accessToken());
  const offline = page({ server: instance.server, local: storage({ 'terrazzo-oauth': raw }) });
  await assert.rejects(offline.auth.restore());
  assert.equal(offline.local.getItem('terrazzo-oauth'), raw);
});

test('a sign-out tombstone prevents another device importing its old browser sign-in', async () => {
  const first = page({ local: storage({ 'terrazzo-oauth': JSON.stringify(fixtureTokens()) }) });
  await first.auth.restore();
  const oldDevice = page({ server: first.server });
  await oldDevice.auth.restore();
  first.server.fail = 502;
  await assert.rejects(first.auth.signOut());
  assert.ok(first.server.session);
  first.server.fail = null;
  await first.auth.signOut();
  await assert.rejects(oldDevice.auth.accessToken());
  const stale = page({ server: first.server, local: storage({ 'terrazzo-oauth': JSON.stringify(fixtureTokens()) }) });
  assert.equal(await stale.auth.restore(), '');
  assert.equal(stale.local.getItem('terrazzo-oauth'), null);
  assert.equal(first.server.session, null);
});

test('migration never erases another tab newer browser sign-in', async () => {
  const instance = page({ local: storage({ 'terrazzo-oauth': JSON.stringify(fixtureTokens()) }) });
  instance.server.onImport = () => instance.local.setItem('terrazzo-oauth', 'newer-tab-value');
  await instance.auth.restore();
  assert.equal(instance.local.getItem('terrazzo-oauth'), 'newer-tab-value');
});

test('cloud OAuth callback preserves nonce checking and keeps the refresh token out of localStorage', async () => {
  const state = { hassUrl: 'https://ha.example.test', clientId: 'https://app.example.test/', nonce: 'fixture-nonce' };
  const search = `?terrazzo_auth=1&code=fixture-code&state=${encodeURIComponent(btoa(JSON.stringify(state)))}`;
  const instance = page({ search, pending: state });
  assert.equal(await instance.auth.restore(), state.hassUrl);
  assert.ok(instance.server.calls.find(call => call.options.method === 'PUT'));
  assert.equal(instance.local.getItem('terrazzo-oauth'), null);
  assert.equal(new URL(instance.location.href).searchParams.has('code'), false);
  const forged = page({ search, pending: { ...state, nonce: 'wrong-nonce' } });
  assert.equal(await forged.auth.restore(), '');
  assert.equal(forged.server.calls.some(call => call.url.includes('/auth/token')), false);
});

test('static mode keeps existing browser OAuth storage and refresh behavior', async () => {
  const instance = page({ cloud: false, local: storage({ 'terrazzo-oauth': JSON.stringify({ ...fixtureTokens(), expires: 0 }) }) });
  assert.equal(await instance.auth.restore(), 'https://ha.example.test');
  assert.equal(await instance.auth.accessToken(), 'fixture-new-access');
  assert.equal(JSON.parse(instance.local.getItem('terrazzo-oauth')).refresh_token, 'fixture-refresh');
  assert.equal(instance.auth.sessionLabel(), '');
  assert.equal(instance.server.calls.some(call => call.url.includes('/ha/session')), false);
  await instance.auth.signOut();
  assert.equal(instance.local.getItem('terrazzo-oauth'), null);
});

test('an invalid legacy grant is discarded after metadata acknowledgement', async () => {
  const instance = page({ local: storage({ 'terrazzo-oauth': JSON.stringify(fixtureTokens()) }) });
  instance.server.importStatus = 401;
  assert.equal(await instance.auth.restore(), '');
  assert.equal(instance.local.getItem('terrazzo-oauth'), null);
  assert.equal(instance.server.session, null);
});

test('explicit recovery skips this tab legacy import without deleting an unsaved credential', async () => {
  const raw = JSON.stringify(fixtureTokens());
  const instance = page({ local: storage({ 'terrazzo-oauth': raw }) });
  instance.server.importStatus = 502;
  await assert.rejects(instance.auth.restore());
  instance.auth.useDifferentSignIn();
  assert.equal(instance.location.reloaded, true);
  assert.equal(instance.sessionStorage.getItem('terrazzo-oauth-skip-import'), '1');
  assert.equal(await instance.auth.restore(), '');
  assert.equal(instance.local.getItem('terrazzo-oauth'), raw);
  assert.equal(instance.server.session, null);
});
