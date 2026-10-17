import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';

test('WASM bridge accepts snapshots only from its same-origin parent', () => {
  const listeners = new Map();
  const sent = [], received = [];
  const parent = {postMessage: (...args) => sent.push(args)};
  const window = {parent, location: {origin: 'https://ha.test'},
    addEventListener: (type, listener) => listeners.set(type, listener)};
  runInNewContext(readFileSync(new URL('../../app-ui/src/wasmJsMain/resources/bootstrap.js', import.meta.url), 'utf8'), {window});
  window.terrazzoHost.connect(value => received.push(JSON.parse(value)));
  assert.equal(sent[0][0].type, 'terrazzo:ready');
  assert.equal(sent[0][1], 'https://ha.test');
  const snapshot = {type: 'terrazzo:snapshot', dashboard: {views: []}, states: {}};
  const receive = listeners.get('message');
  receive({source: parent, origin: 'https://attacker.test', data: snapshot});
  receive({source: {}, origin: 'https://ha.test', data: snapshot});
  receive({source: parent, origin: 'https://ha.test', data: {type: 'terrazzo:action'}});
  assert.deepEqual(received, []);
  receive({source: parent, origin: 'https://ha.test', data: snapshot});
  assert.deepEqual(received, [snapshot]);
});
