// Tap resolution and Home Assistant's compressed entity events. Run: node --test embed/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveTap, serviceCallFor, applyEntitiesEvent } from '../ha-card.js';

const action = (kind, fields) => JSON.stringify({ type: `ee.schimke.ha.rc.components.HaAction.${kind}`, ...fields });

test('a slot toggle runs on the slot\'s real entity', () => {
  const tap = resolveTap({ type: 'tile', entity: 'light.kitchen' }, action('Toggle', { entityId: 'light.s0' }));
  assert.deepEqual(tap, { kind: 'Toggle', entityId: 'light.kitchen' });
  assert.deepEqual(serviceCallFor(tap), {
    domain: 'homeassistant', service: 'toggle', service_data: {}, target: { entity_id: 'light.kitchen' },
  });
});

test('the card\'s own tap_action wins over the template', () => {
  const card = { type: 'tile', entity: 'light.kitchen', tap_action: { action: 'more-info' } };
  assert.deepEqual(resolveTap(card, action('Toggle', { entityId: 'light.s0' })), { kind: 'MoreInfo', entityId: 'light.kitchen' });
});

test('a row\'s tap_action applies to its slot', () => {
  const card = {
    type: 'entities',
    entities: ['light.a', { entity: 'switch.b', tap_action: { action: 'perform-action', perform_action: 'switch.turn_off' } }],
  };
  assert.deepEqual(resolveTap(card, action('Toggle', { entityId: 'switch.s1' })), {
    kind: 'CallService', domain: 'switch', service: 'turn_off', entityId: 'switch.b', serviceData: {},
  });
  assert.deepEqual(resolveTap(card, action('Toggle', { entityId: 'light.s0' })), { kind: 'Toggle', entityId: 'light.a' });
});

test('a template cannot reach another entity or another domain', () => {
  const card = { type: 'tile', entity: 'light.kitchen' };
  // A real entity id, not a slot: the card's own action.
  assert.deepEqual(resolveTap(card, action('Toggle', { entityId: 'lock.front_door' })), { kind: 'Toggle', entityId: 'light.kitchen' });
  // A slot of the wrong domain.
  assert.deepEqual(resolveTap(card, action('Toggle', { entityId: 'lock.s0' })), { kind: 'Toggle', entityId: 'light.kitchen' });
  // A slot past the card's entities.
  assert.deepEqual(resolveTap(card, action('Toggle', { entityId: 'light.s3' })), { kind: 'Toggle', entityId: 'light.kitchen' });
  // Another domain's service on the slot: the domain default instead.
  assert.deepEqual(
    resolveTap(card, action('CallService', { domain: 'lock', service: 'unlock', entityId: 'light.s0' })),
    { kind: 'Toggle', entityId: 'light.kitchen' },
  );
  // Even its own domain's service: the template only picks the slot, never the service or data.
  assert.deepEqual(
    resolveTap(card, action('CallService', { domain: 'light', service: 'turn_on', entityId: 'light.s0', serviceData: { brightness: 10 } })),
    { kind: 'Toggle', entityId: 'light.kitchen' },
  );
  // So a harmless default can't become a disarm.
  assert.deepEqual(
    resolveTap(
      { type: 'tile', entity: 'alarm_control_panel.home' },
      action('CallService', { domain: 'alarm_control_panel', service: 'alarm_disarm', entityId: 'alarm_control_panel.s0', serviceData: { code: '1234' } }),
    ),
    { kind: 'MoreInfo', entityId: 'alarm_control_panel.home' },
  );
  // A URL in a template is never followed.
  assert.deepEqual(resolveTap(card, action('Url', { url: 'https://evil.example' })), { kind: 'Toggle', entityId: 'light.kitchen' });
  assert.deepEqual(resolveTap({ type: 'markdown' }, action('Url', { url: 'https://evil.example' })), { kind: 'None' });
});

test('domain defaults: sensors open more-info', () => {
  assert.deepEqual(resolveTap({ type: 'tile', entity: 'sensor.t' }, 'not json'), { kind: 'MoreInfo', entityId: 'sensor.t' });
});

test('compressed entity events add, change and remove', () => {
  const states = {};
  applyEntitiesEvent(states, { a: { 'light.k': { s: 'off', a: { friendly_name: 'K', brightness: 1 }, c: 'ctx', lc: 1, lu: 1 } } });
  assert.deepEqual(states, { 'light.k': { state: 'off', attributes: { friendly_name: 'K', brightness: 1 } } });
  applyEntitiesEvent(states, { c: { 'light.k': { '+': { s: 'on', a: { brightness: 255 } }, '-': { a: ['friendly_name'] } } } });
  assert.deepEqual(states, { 'light.k': { state: 'on', attributes: { brightness: 255 } } });
  applyEntitiesEvent(states, { r: ['light.k'] });
  assert.deepEqual(states, {});
});
