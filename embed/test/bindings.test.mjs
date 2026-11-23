// The embed's port of ha-model's slot bindings and binding recipes, against the golden file design-server's
// EmbedBindingsGoldenTest writes from Kotlin. Run: node --test embed/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { cardBindings, cardEntityIds, parseRecipe, formatRangeNumber, hostValuesFromConfig, hvacLabel } from '../ha-card.js';

const golden = JSON.parse(readFileSync(new URL('./bindings-golden.json', import.meta.url), 'utf8'));
// The zone the Kotlin side read the cases' times in (`<slot>.last_changed_offset`).
process.env.TZ = golden.time_zone;

for (const c of golden.cases) {
  test(`bindings: ${c.name}`, () => {
    assert.ok(c.expected, 'the golden file has no expectations: run EmbedBindingsGoldenTest with -Pembed.golden.update=true');
    const recipe = c.recipe === undefined ? null : parseRecipe(c.recipe_json);
    if (c.recipe !== undefined) assert.ok(recipe, 'recipe_json must parse');
    const actual = cardBindings(c.card, c.states, recipe, c.host ?? {});
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

test('a recipe\'s host values come from Home Assistant\'s config', () => {
  assert.deepEqual(hostValuesFromConfig({ unit_system: { temperature: '°F' } }), { temperature_unit: '°F' });
  assert.deepEqual(hostValuesFromConfig({ unit_system: { temperature: ' ' } }), {});
  assert.deepEqual(hostValuesFromConfig(undefined), {});
});

test('a host expression or function this host doesn\'t know is null, so a fallback shows', () => {
  const recipe = parseRecipe({
    v: 1, prototype: 'p', floats: {},
    strings: { a: { first: [{ host: 'nope' }, '°'] }, b: { first: [{ fn: 'nope', args: [] }, '—'] }, c: { host: 'temperature_unit' } },
  });
  const { strings } = cardBindings({ type: 'tile', entity: 'climate.x' }, {}, recipe);
  assert.equal(strings.a, '°');
  assert.equal(strings.b, '—');
  assert.equal(strings.c, '°C');
  assert.equal(hvacLabel('idle', 'heat'), 'Idle');
  assert.equal(hvacLabel(null, 'fan_only'), 'Fan only');
  assert.equal(hvacLabel(null, ''), null);
});

test('semantic bindings format explicit entries with all options and push typed values', async () => {
  const { formatSemanticBinding } = await import('../ha-card.js');
  const states = {
    'sensor.dishwasher_operation_state': { state: 'run', attributes: {} },
    'sensor.dishwasher_remaining': { state: '95', attributes: { unit_of_measurement: 'min' } },
    'sensor.nozzle_temp': { state: '215.46', attributes: { unit_of_measurement: '°C' } },
    'binary_sensor.dishwasher_door': { state: 'off', attributes: {} },
    'light.hue': { state: 'on', attributes: { brightness: 128 } },
    'sensor.idle_metric': { state: 'none', attributes: {} },
  };
  assert.equal(
    formatSemanticBinding({ entity: 'sensor.dishwasher_operation_state', format: 'title', uppercase: true }, states, 0),
    'RUN',
  );
  assert.equal(
    formatSemanticBinding({ entity: 'sensor.dishwasher_remaining', format: 'hhmm', prefix: 'T-', suffix: ' left' }, states, 0),
    'T-1:35 left',
  );
  assert.equal(
    formatSemanticBinding({ entity: 'sensor.nozzle_temp', format: '%.1f', suffix: '°C' }, states, 0),
    '215.5°C',
  );
  assert.equal(
    formatSemanticBinding({ entity: 'binary_sensor.dishwasher_door', on_text: 'Open', off_text: 'Closed', uppercase: true }, states, 0),
    'CLOSED',
  );
  assert.equal(
    formatSemanticBinding({ entity: 'light.hue', attribute: 'brightness', scale: 100 / 255, format: 'percent' }, states, 0),
    '50%',
  );
  assert.equal(
    formatSemanticBinding({ entity: 'sensor.idle_metric', default_if: ['none', 'unknown'], fallback: '--:--' }, states, 0),
    '--:--',
  );
  // Typed outputs for float (1) and int/bool (4).
  assert.equal(
    formatSemanticBinding({ entity: 'sensor.nozzle_temp', scale: 0.01 }, states, 1),
    Math.fround(2.1546),
  );
  assert.equal(formatSemanticBinding('binary_sensor.dishwasher_door', states, 4), 0);
  assert.equal(formatSemanticBinding('light.hue', states, 4), 1);
  assert.equal(
    formatSemanticBinding({ entity: 'sensor.missing', fallback: 42 }, states, 1),
    Math.fround(42),
  );
});

test('semantic bindings auto-discover sibling entities on the same device_id via hass.entities', () => {
  const card = { type: 'tile', entity: 'sensor.bosch_dw_operation_state' };
  const states = {
    'sensor.bosch_dw_operation_state': { state: 'run', attributes: {} },
    'sensor.bosch_dw_remaining_program_time': { state: '45', attributes: { unit_of_measurement: 'min' } },
    'binary_sensor.bosch_dw_door': { state: 'off', attributes: {} },
    'sensor.bosch_dw_progress': { state: '62.5', attributes: { unit_of_measurement: '%' } },
    'sensor.helper_override': { state: 'Eco 50°', attributes: {} },
    'sensor.other_device_door': { state: 'on', attributes: {} },
  };
  const entities = {
    'sensor.bosch_dw_operation_state': { entity_id: 'sensor.bosch_dw_operation_state', device_id: 'dw1', translation_key: 'operation_state' },
    'sensor.bosch_dw_remaining_program_time': { entity_id: 'sensor.bosch_dw_remaining_program_time', device_id: 'dw1', translation_key: 'remaining_program_time' },
    'binary_sensor.bosch_dw_door': { entity_id: 'binary_sensor.bosch_dw_door', device_id: 'dw1', translation_key: 'door' },
    'sensor.bosch_dw_progress': { entity_id: 'sensor.bosch_dw_progress', device_id: 'dw1', translation_key: 'program_progress' },
    'sensor.other_device_door': { entity_id: 'sensor.other_device_door', device_id: 'other', translation_key: 'door' },
  };
  const namedVariables = new Map([
    ['USER:sensor_operation_state', { id: 1, type: 0 }],
    ['USER:sensor_remaining_program_time', { id: 2, type: 0 }],
    ['USER:binary_sensor_door', { id: 3, type: 4 }],
    ['USER:sensor_program_progress', { id: 4, type: 1 }],
    ['USER:active_program', { id: 5, type: 0 }],
    ['USER:local_tab_index', { id: 6, type: 4 }],
  ]);
  const explicit = {
    active_program: 'sensor.helper_override',
    sensor_operation_state: { entity: 'sensor.bosch_dw_operation_state', uppercase: true },
  };
  assert.deepEqual(cardEntityIds(card, explicit), ['sensor.bosch_dw_operation_state', 'sensor.helper_override']);
  const result = cardBindings(card, states, null, {}, { bindings: explicit, entities, namedVariables });
  assert.equal(result.strings.sensor_operation_state, 'RUN');
  assert.equal(result.strings.active_program, 'Eco 50°');
  assert.equal(result.strings.sensor_remaining_program_time, '45 min');
  assert.equal(result.ints.binary_sensor_door, 0);
  assert.equal(result.floats.sensor_program_progress, Math.fround(62.5));
  // Design-local state with no matching sibling is left untouched.
  assert.equal(Object.hasOwn(result.ints, 'local_tab_index'), false);
  assert.equal(Object.hasOwn(result.strings, 'local_tab_index'), false);
});
