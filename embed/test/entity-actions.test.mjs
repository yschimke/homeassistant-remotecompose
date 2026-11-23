// Entity actions: a design's named action on the card's own entity (a garage door's Close), the port
// of ha-model's CardEntityActions, against the same cases as CardEntityActionsTest.
// Run: node --test embed/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ENTITY_ACTION_NAME, entityActionCall, entityActionNames, resolveEntityAction, serviceCallFor } from '../ha-card.js';

const payload = (entityId, action) => JSON.stringify({ entityId, action });
const cover = (attributes) => ({ 'cover.my_garage': { state: 'closed', attributes } });

test('entity actions travel under their own host action, not the tap', () => {
  assert.equal(ENTITY_ACTION_NAME, 'ha.entity_action');
});

test('the names match CardEntityActions', () => {
  assert.deepEqual(entityActionNames(), {
    cover: ['open_cover', 'close_cover', 'stop_cover', 'toggle'],
    climate: ['turn_on', 'turn_off', 'temperature_up', 'temperature_down'],
    light: ['turn_on', 'turn_off', 'toggle'],
    switch: ['turn_on', 'turn_off', 'toggle'],
    fan: ['turn_on', 'turn_off', 'toggle'],
    input_boolean: ['turn_on', 'turn_off', 'toggle'],
  });
});

test('a garage door\'s Open, Stop and Close each run their own service on the card\'s door', () => {
  const card = { type: 'tile', entity: 'cover.my_garage', tap_action: { action: 'toggle' } };
  for (const service of ['open_cover', 'stop_cover', 'close_cover']) {
    const action = resolveEntityAction(card, payload('cover.s0', service), cover({ supported_features: 15 }));
    assert.deepEqual(action, {
      kind: 'CallService', domain: 'cover', service, entityId: 'cover.my_garage', serviceData: {}, entityAction: true,
    });
    assert.deepEqual(serviceCallFor(action), {
      domain: 'cover', service, service_data: {}, target: { entity_id: 'cover.my_garage' },
    });
  }
});

test('a cover that can\'t stop sends no stop; one reporting no features may', () => {
  const gate = cover({ supported_features: 3 });
  assert.equal(entityActionCall('cover.my_garage', 'stop_cover', gate), null);
  assert.equal(entityActionCall('cover.my_garage', 'close_cover', gate).service, 'close_cover');
  assert.equal(entityActionCall('cover.my_garage', 'stop_cover', cover({})).service, 'stop_cover');
});

test('a template reaches only the card\'s own slot and its domain\'s actions', () => {
  const card = { type: 'tile', entity: 'cover.my_garage' };
  const none = { kind: 'None' };
  assert.deepEqual(resolveEntityAction(card, payload('cover.s1', 'open_cover')), none);
  assert.deepEqual(resolveEntityAction(card, payload('lock.s0', 'unlock')), none);
  assert.deepEqual(resolveEntityAction(card, payload('cover.s0', 'unlock')), none);
  assert.deepEqual(resolveEntityAction(card, payload('cover.my_garage', 'open_cover')), none);
  assert.deepEqual(resolveEntityAction(card, '{not json'), none);
  assert.deepEqual(resolveEntityAction(card, ''), none);
  assert.deepEqual(resolveEntityAction({ type: 'tile', entity: 'lock.front' }, payload('lock.s0', 'unlock')), none);
  assert.equal(entityActionCall('light.kitchen', 'open_cover', {}), null);
  assert.equal(entityActionCall('light.kitchen', 'turn_off', {}).service, 'turn_off');
});

test('a thermostat steps its set point within its range, by its own step or the home\'s unit', () => {
  const celsius = { 'climate.hall': { state: 'heat', attributes: { temperature: 21, min_temp: 7, max_temp: 21.2 } } };
  assert.deepEqual(entityActionCall('climate.hall', 'temperature_down', celsius), {
    domain: 'climate', service: 'set_temperature', entityId: 'climate.hall', data: { temperature: 20.5 },
  });
  assert.equal(entityActionCall('climate.hall', 'temperature_up', celsius).data.temperature, 21.2);
  const fahrenheit = { 'climate.hall': { state: 'heat', attributes: { temperature: 70, max_temp: 90 } } };
  assert.equal(entityActionCall('climate.hall', 'temperature_up', fahrenheit, { temperature_unit: '°F' }).data.temperature, 71);
  const stepped = { 'climate.hall': { state: 'heat', attributes: { temperature: 20, target_temp_step: 0.1 } } };
  assert.equal(entityActionCall('climate.hall', 'temperature_up', stepped).data.temperature, 20.1);
  assert.equal(entityActionCall('climate.hall', 'temperature_up', { 'climate.hall': { state: 'off', attributes: {} } }), null);
  const card = { type: 'thermostat', entity: 'climate.hall' };
  assert.deepEqual(resolveEntityAction(card, payload('climate.s0', 'temperature_up'), fahrenheit, { temperature_unit: '°F' }), {
    kind: 'CallService', domain: 'climate', service: 'set_temperature', entityId: 'climate.hall',
    serviceData: { temperature: 71 }, entityAction: true,
  });
});
