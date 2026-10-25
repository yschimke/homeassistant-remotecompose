import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../src/wasmJsMain/resources/terrazzo-backend.js', import.meta.url), 'utf8');
function page({ response = new Response('{"api":1,"kv":true}', { headers: { 'Content-Type': 'application/json' } }), frameElement = null, pathname = '/app.html', search = '', timeout = false, fetchImpl = null } = {}) {
  const calls = [];
  const window = { frameElement };
  runInNewContext(source, {
    window, location: { pathname, search }, URLSearchParams, Headers, AbortController,
    setTimeout: timeout ? (fn) => { queueMicrotask(fn); return 1; } : setTimeout,
    clearTimeout,
    fetch: async (url, options) => {
      calls.push({ url, options });
      if (timeout) return new Promise((_, reject) => options.signal.addEventListener('abort', () => reject(new Error('timeout'))));
      if (fetchImpl) return fetchImpl(url, options);
      if (response instanceof Error) throw response;
      return response;
    },
  });
  return { backend: window.terrazzoBackend, calls };
}

test('only a versioned JSON response enables Wasmo', async () => {
  const { backend, calls } = page();
  assert.equal((await backend.ready).kind, 'wasmo');
  assert.equal(calls[0].url, '/api/wasmo/v1/capabilities');
  assert.equal(calls[0].options.credentials, 'same-origin');
  assert.equal(calls[0].options.cache, 'no-store');
  for (const response of [new Response('missing', { status: 404 }), new Response('<html>login</html>'), new Response('{"api":2}', { headers: { 'content-type': 'application/json' } }), new Response('invalid', { headers: { 'content-type': 'application/json' } }), new Error('offline')]) {
    assert.equal((await page({ response }).backend.ready).kind, 'local');
  }
  assert.equal((await page({ timeout: true }).backend.ready).kind, 'local');
});

test('panel and both demo entry points never probe', async () => {
  for (const options of [{ frameElement: { terrazzoHost: {} } }, { pathname: '/demo/' }, { pathname: '/project/demo' }, { search: '?demo=1' }]) {
    const { backend, calls } = page(options);
    assert.equal((await backend.ready).kind, 'local');
    assert.equal(calls.length, 0);
  }
});

test('API wrapper keeps requests on origin and requires the CSRF header', async () => {
  const { backend, calls } = page();
  await backend.ready;
  await backend.request('kv/terrazzo.usage.sharing', { method: 'PUT', credentials: 'omit', redirect: 'follow', headers: { 'X-Wasmo-Api': '0' } });
  const { url, options } = calls.at(-1);
  assert.equal(url, '/api/wasmo/v1/kv/terrazzo.usage.sharing');
  assert.equal(options.headers.get('X-Wasmo-Api'), '1');
  assert.equal(options.credentials, 'same-origin');
  assert.equal(options.redirect, 'error');
  for (const path of ['https://evil.test/', '//evil.test', '../ha/token']) assert.throws(() => backend.request(path));
});

function service() {
  let values = {};
  let failure = null;
  const instance = page({ fetchImpl: async (url, options) => {
    if (url.endsWith('/capabilities')) return Response.json({ api: 1, kv: true, kvImport: true });
    if (failure) return new Response('', { status: failure });
    if (url.endsWith('/kv') && !options.method) return Response.json(values);
    if (url.endsWith('/kv/import')) {
      if (Object.keys(values).length) return Response.json({ error: 'already-initialized' }, { status: 409 });
      values = JSON.parse(options.body);
      return new Response(null, { status: 204 });
    }
    const key = url.split('/').at(-1);
    if (options.method === 'PUT') values[key] = JSON.parse(options.body);
    else if (options.method === 'DELETE') delete values[key];
    return new Response(null, { status: 204 });
  } });
  return { ...instance, values: (next) => { if (next) values = next; return values; }, fail: (status) => { failure = status; } };
}

test('settings transport round trips string values and normalizes generic JSON', async () => {
  const instance = service();
  const { backend } = instance;
  await backend.ready;
  instance.values({ primitive: true, object: { count: 1 }, ledger: '{"count":2}' });
  assert.deepEqual(JSON.parse(await backend.settings.read()), { primitive: 'true', object: '{"count":1}', ledger: '{"count":2}' });
  await backend.settings.write('terrazzo.usage.sharing', 'DISABLED');
  assert.equal(instance.values()['terrazzo.usage.sharing'], 'DISABLED');
  const { options } = instance.calls.at(-1);
  assert.equal(options.headers.get('X-Wasmo-Api'), '1');
  assert.equal(options.headers.get('content-type'), 'application/json');
  assert.equal(options.credentials, 'same-origin');
  await backend.settings.write('terrazzo.usage.sharing', null);
  assert.equal(instance.values()['terrazzo.usage.sharing'], undefined);
});

test('atomic import acknowledges values and rereads on an existing-install conflict', async () => {
  const { backend, values } = service();
  await backend.ready;
  const json = JSON.stringify({ ledger: 'local' });
  assert.equal(await backend.settings.importIfEmpty(json), json);
  assert.deepEqual(values(), { ledger: 'local' });
  values({ ledger: 'other-device' });
  assert.deepEqual(JSON.parse(await backend.settings.importIfEmpty(json)), { ledger: 'other-device' });
});

test('failed writes reject, including a conflict outside the import endpoint', async () => {
  const { backend, fail } = service();
  await backend.ready;
  for (const status of [401, 403, 409, 413, 503]) {
    fail(status);
    await assert.rejects(backend.settings.write('ledger', 'keep'));
  }
});
