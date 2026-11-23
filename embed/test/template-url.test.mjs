import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fetchTemplateUrl, HaCardElement } from '../ha-card.js';

test('direct templates resolve against the host page and send credentials only for same-origin URLs', async () => {
  const bytes = new Uint8Array([1, 2, 3]).buffer;
  for (const [value, expected, expectedCredentials] of [
    ['/local/cards/tile.rc', 'https://ha.test/local/cards/tile.rc', 'same-origin'],
    ['tile.rc', 'https://ha.test/lovelace/tile.rc', 'same-origin'],
    ['https://ha.test/designs/v1/blobs/abc.rc', 'https://ha.test/designs/v1/blobs/abc.rc', 'same-origin'],
    ['https://designs.test/tile.rc', 'https://designs.test/tile.rc', 'omit'],
  ]) {
    const calls = [];
    const result = await fetchTemplateUrl(value, 'https://ha.test/lovelace/home', async (url, init) => {
      calls.push({ url: String(url), init });
      return { ok: true, arrayBuffer: async () => bytes };
    });
    assert.equal(result, bytes);
    assert.deepEqual(calls, [{ url: expected, init: { credentials: expectedCredentials, referrerPolicy: 'no-referrer' } }]);
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

test('the host starts directly from a URL, supports relative library paths, reloads, and rejects conflicting selectors', async () => {
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
    const attributes = new Map([['template-url', '/local/first.rc'], ['library', '/designs/v1/']]);
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

test('require-host-bindings permits dot-less design-local state variables while rejecting unknown dotted ones', async () => {
  const makeRuntime = (namedVariables) => {
    const overrides = new Map();
    const context = {
      namedVariables,
      textOverrides: new Map(),
      setNamedStringOverride: (k, v) => overrides.set(k, v),
      setNamedFloatOverride: (k, v) => overrides.set(k, v),
      setNamedIntegerOverride: (k, v) => overrides.set(k, v),
    };
    const doc = { addActionCallback() {}, invalidateMeasure() {} };
    let destroyed = false;
    class RcdPlayer {
      setDensity() {}
      setTheme() {}
      setOnLoad(cb) { this.onLoad = cb; }
      async loadFromArrayBuffer() { this.onLoad(doc); }
      getRemoteContext() { return context; }
      getDocument() { return doc; }
      resize() {}
      repaint() {}
      destroy() { destroyed = true; }
    }
    return { RC: { RcdPlayer }, overrides, wasDestroyed: () => destroyed };
  };

  const makeHost = (attributes) => ({
    canvas: { style: {}, width: 0, height: 0 },
    clientWidth: 360,
    states: { 'light.kitchen': { state: 'on', attributes: { friendly_name: 'Kitchen' } } },
    hostValues: {},
    entityRegistry: null,
    pushed: new Map(),
    inputDrafts: new Map(),
    getAttribute: (name) => attributes.get(name) ?? null,
    hasAttribute: (name) => attributes.has(name),
    configureInputPlayer() {},
    render() { HaCardElement.prototype.render.call(this); },
    fit() {},
    get card() { return { type: 'tile', entity: 'light.kitchen' }; },
    get semanticBindingsConfig() { return null; },
    get actionsConfig() { return null; },
    get deviceId() { return null; },
  });

  // Dot-less local state variable + bound slot variable succeeds under require-host-bindings.
  const okVars = new Map([
    ['USER:light.s0.state', { id: 1, type: 0 }],
    ['USER:active_tab', { id: 2, type: 4 }],
    ['USER:sensor_operation_state', { id: 3, type: 0 }],
  ]);
  const okRuntime = makeRuntime(okVars);
  const okHost = makeHost(new Map([['require-host-bindings', '']]));
  await HaCardElement.prototype.play.call(okHost, okRuntime.RC, new ArrayBuffer(0), okHost.card);
  assert.equal(okRuntime.wasDestroyed(), false);
  assert.equal(okRuntime.overrides.get('USER:light.s0.state'), 'On');
  assert.equal(okRuntime.overrides.has('USER:active_tab'), false);

  // Unknown dotted variable is still rejected.
  const badVars = new Map([['USER:card.converter_only', { id: 1, type: 0 }]]);
  const badRuntime = makeRuntime(badVars);
  const badHost = makeHost(new Map([['require-host-bindings', '']]));
  await assert.rejects(
    HaCardElement.prototype.play.call(badHost, badRuntime.RC, new ArrayBuffer(0), badHost.card),
    /converter bindings unavailable/,
  );
  assert.equal(badRuntime.wasDestroyed(), true);
});
