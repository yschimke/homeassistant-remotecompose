// The embed's port of ha-model's slot bindings and binding recipes, against the golden file design-server's
// EmbedBindingsGoldenTest writes from Kotlin. Run: node --test embed/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { cardBindings, cardEntityIds, parseRecipe, formatRangeNumber } from '../ha-card.js';

const golden = JSON.parse(readFileSync(new URL('./bindings-golden.json', import.meta.url), 'utf8'));

for (const c of golden.cases) {
  test(`bindings: ${c.name}`, () => {
    assert.ok(c.expected, 'the golden file has no expectations: run EmbedBindingsGoldenTest with -Pembed.golden.update=true');
    const recipe = c.recipe === undefined ? null : parseRecipe(c.recipe_json);
    if (c.recipe !== undefined) assert.ok(recipe, 'recipe_json must parse');
    const actual = cardBindings(c.card, c.states, recipe);
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

test('a recipe of another version or shape is not one', () => {
  assert.equal(parseRecipe('{"v":2,"prototype":"gauge"}'), null);
  assert.equal(parseRecipe('{"v":1}'), null);
  assert.equal(parseRecipe('not json'), null);
  assert.deepEqual(parseRecipe('{"v":1,"prototype":"p"}'), { prototype: 'p', floats: {}, strings: {} });
});

test('range numbers match CardBindingRecipe.formatRangeNumber', () => {
  const cases = { 0: '0', '-3': '-3', 0.25: '0.3', '-0.5': '-0.5', '-12.25': '-12.2', 99.94: '99.9', 1e6: '1000000' };
  for (const [value, text] of Object.entries(cases)) assert.equal(formatRangeNumber(Number(value)), text, value);
  assert.equal(formatRangeNumber(Number.NaN), 'NaN');
  assert.equal(formatRangeNumber(-Infinity), '-Infinity');
});
