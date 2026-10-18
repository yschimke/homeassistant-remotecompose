import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchTemplateUrl, HaCardElement } from '../ha-card.js';

test('direct templates resolve against the host page and send no credentials or referrer', async () => {
  const bytes = new Uint8Array([1, 2, 3]).buffer;
  for (const [value, expected] of [
    ['/local/cards/tile.rc', 'https://ha.test/local/cards/tile.rc'],
    ['tile.rc', 'https://ha.test/lovelace/tile.rc'],
    ['https://designs.test/tile.rc', 'https://designs.test/tile.rc'],
  ]) {
    const calls = [];
    const result = await fetchTemplateUrl(value, 'https://ha.test/lovelace/home', async (url, init) => {
      calls.push({ url: String(url), init });
      return { ok: true, arrayBuffer: async () => bytes };
    });
    assert.equal(result, bytes);
    assert.deepEqual(calls, [{ url: expected, init: { credentials: 'omit', referrerPolicy: 'no-referrer' } }]);
  }
});

test('direct templates reject non-HTTP URLs and embedded credentials before fetching', async () => {
  for (const value of ['javascript:alert(1)', 'data:application/octet-stream,abc', 'file:///tile.rc',
    'https://user:secret@designs.test/tile.rc']) {
    await assert.rejects(fetchTemplateUrl(value, 'https://ha.test/', () => assert.fail('must not fetch')), /HTTP\(S\) without credentials/);
  }
});

test('direct template failures are surfaced instead of loading an error page', async () => {
  await assert.rejects(fetchTemplateUrl('/missing.rc', 'https://ha.test/', async () => ({ ok: false, status: 404 })), /request failed \(404\)/);
  await assert.rejects(fetchTemplateUrl('/tile.rc', 'https://ha.test/', async () => { throw new Error('Failed to fetch'); }), /Failed to fetch/);
});

test('the host starts directly from a URL, reloads it, and rejects conflicting selectors', async () => {
  const previous = { fetch: globalThis.fetch, document: globalThis.document, RC: globalThis.RC };
  const bytes = new Uint8Array([1, 2, 3]).buffer;
  const requests = [];
  const played = [];
  globalThis.document = { baseURI: 'https://ha.test/lovelace/home' };
  globalThis.RC = { RcdPlayer: class {} };
  globalThis.fetch = async (url) => {
    requests.push(String(url));
    return { ok: true, arrayBuffer: async () => bytes };
  };
  try {
    const attributes = new Map([['template-url', '/local/first.rc']]);
    const host = {
      generation: 0, localStates: {}, card: { type: 'tile', entity: 'light.kitchen' },
      getAttribute: (name) => attributes.get(name) ?? null,
      stop() { this.generation++; },
      setStatus(status, message) { this.status = status; this.message = message; },
      async play(_RC, buffer, card) { played.push({ buffer, card }); },
      async connectHass() { assert.fail('must use HA-supplied states'); },
    };
    await HaCardElement.prototype.start.call(host);
    assert.equal(host.status, 'live');
    assert.deepEqual(requests, ['https://ha.test/local/first.rc']);
    assert.deepEqual(played, [{ buffer: bytes, card: host.card }]);
    attributes.set('template-url', '/local/second.rc');
    await HaCardElement.prototype.start.call(host);
    assert.equal(requests.at(-1), 'https://ha.test/local/second.rc');
    for (const selector of ['design', 'design-key']) {
      attributes.set(selector, 'conflict');
      await HaCardElement.prototype.start.call(host);
      assert.equal(host.status, 'error');
      assert.match(host.message, /cannot be combined/);
      attributes.delete(selector);
    }
    attributes.set('template-url', '');
    await HaCardElement.prototype.start.call(host);
    assert.equal(host.status, 'error');
    assert.match(host.message, /must not be empty/);
    assert.equal(requests.length, 2, 'invalid selectors must not fetch');
  } finally {
    Object.assign(globalThis, previous);
  }
});
