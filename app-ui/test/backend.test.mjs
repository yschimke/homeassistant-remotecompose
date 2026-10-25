import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../src/wasmJsMain/resources/terrazzo-backend.js', import.meta.url), 'utf8');
function page({ response = new Response('{"api":1,"kv":true}', { headers: { 'Content-Type': 'application/json' } }), frameElement = null, pathname = '/app.html', search = '', timeout = false } = {}) {
  const calls = [];
  const window = { frameElement };
  runInNewContext(source, {
    window, location: { pathname, search }, URLSearchParams, Headers, AbortController,
    setTimeout: timeout ? (fn) => { queueMicrotask(fn); return 1; } : setTimeout,
    clearTimeout,
    fetch: async (url, options) => {
      calls.push({ url, options });
      if (timeout) return new Promise((_, reject) => options.signal.addEventListener('abort', () => reject(new Error('timeout'))));
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
