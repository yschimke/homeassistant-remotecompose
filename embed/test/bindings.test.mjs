// The embed's port of ha-model's slot bindings, against the golden file design-server's
// EmbedBindingsGoldenTest writes from Kotlin. Run: node --test embed/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { slotBindings, cardEntityIds } from '../ha-card.js';

const golden = JSON.parse(readFileSync(new URL('./bindings-golden.json', import.meta.url), 'utf8'));

for (const c of golden.cases) {
  test(`bindings: ${c.name}`, () => {
    assert.ok(c.expected, 'the golden file has no expectations: run EmbedBindingsGoldenTest with -Pembed.golden.update=true');
    const actual = slotBindings(c.card, c.states);
    assert.deepEqual(actual.strings, c.expected.strings);
    assert.deepEqual(actual.booleans, c.expected.booleans);
    assert.deepEqual(actual.ints, c.expected.ints);
    assert.deepEqual(Object.keys(actual.floats), Object.keys(c.expected.floats));
    for (const [name, value] of Object.entries(c.expected.floats)) {
      if (value === 'NaN') assert.ok(Number.isNaN(actual.floats[name]), name);
      else assert.equal(actual.floats[name], Math.fround(value), name);
    }
  });
}

test('the card subscribes to each bound entity once', () => {
  assert.deepEqual(
    cardEntityIds({ type: 'entities', entities: ['light.a', { entity: 'sensor.b' }, 'light.a'] }),
    ['light.a', 'sensor.b'],
  );
});
