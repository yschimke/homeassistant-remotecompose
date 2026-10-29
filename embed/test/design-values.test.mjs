// A design played with its own values: the "Change values" swap and the ambient flag.
// Run: node --test embed/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ambientValue, changedValues } from '../ha-card.js';

test('a changed fraction swings across its range and stays in it', () => {
  const { floats } = changedValues({ floats: { 'light.s0.brightness_fraction': 0.3, 'gauge.fill': 0.9 } });
  assert.ok(Math.abs(floats['light.s0.brightness_fraction'] - 0.7) < 1e-9);
  assert.ok(Math.abs(floats['gauge.fill'] - 0.5) < 1e-9);
});

test('a changed number grows', () => {
  assert.deepEqual(changedValues({ floats: { a: 20, c: -4 } }).floats, { a: 30, c: -6 });
});

test('flags flip, on/off states flip, status and other text stay', () => {
  const changed = changedValues({
    booleans: { 'light.s0.is_on': true },
    ints: { 'light.s0.state_int': 1, 'light.s0.count': 2, 'light.s0.status': 0 },
    strings: { 'light.s0.state': 'On', 'light.s0.name': 'Kitchen' },
  });
  assert.deepEqual(changed.booleans, { 'light.s0.is_on': false });
  assert.deepEqual(changed.ints, { 'light.s0.state_int': 0, 'light.s0.count': 3, 'light.s0.status': 0 });
  assert.deepEqual(changed.strings, { 'light.s0.state': 'Off', 'light.s0.name': 'Kitchen' });
});

test('the ambient attribute: present is ambient, false is not, absent writes nothing', () => {
  assert.equal(ambientValue(''), 1);
  assert.equal(ambientValue('true'), 1);
  assert.equal(ambientValue('false'), 0);
  assert.equal(ambientValue(null), null);
});
