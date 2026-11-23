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
  assert.deepEqual(states, { 'light.k': { state: 'off', attributes: { friendly_name: 'K', brightness: 1 }, last_changed: 1 } });
  applyEntitiesEvent(states, { c: { 'light.k': { '+': { s: 'on', a: { brightness: 255 }, lc: 2.5 }, '-': { a: ['friendly_name'] } } } });
  assert.deepEqual(states, { 'light.k': { state: 'on', attributes: { brightness: 255 }, last_changed: 2.5 } });
  // An attribute-only change keeps the state's last_changed.
  applyEntitiesEvent(states, { c: { 'light.k': { '+': { a: { brightness: 128 }, lu: 3 } } } });
  assert.equal(states['light.k'].last_changed, 2.5);
  applyEntitiesEvent(states, { r: ['light.k'] });
  assert.deepEqual(states, {});
});

test('semantic named actions resolve explicit actions: map and preserve target/service data', async () => {
  const { resolveNamedAction } = await import('../ha-card.js');
  const card = { type: 'tile', entity: 'sensor.bosch_dw_operation_state' };
  const actions = {
    start: 'button.bosch_dw_start_pause',
    power: 'switch.bosch_dw_power',
    eco_50: {
      service: 'home_connect.start_program',
      data: { program: 'Dishcare.Dishwasher.Program.Eco50' },
      target: { device_id: 'dw1' },
    },
    warm: {
      action: 'perform-action',
      perform_action: 'light.turn_on',
      entity: 'light.living_room_hue',
      data: { color_temp_kelvin: 2700 },
    },
    kettle_80: { entity: 'number.kettle_target_temp', value: 80 },
    mode_auto: { entity: 'select.bosch_dw_program', option: 'Auto 45-65°' },
    details: { action: 'more-info', entity: 'sensor.bosch_dw_operation_state' },
  };

  const start = resolveNamedAction(card, 'start', '', { actions });
  assert.deepEqual(serviceCallFor(start), {
    domain: 'button', service: 'press', service_data: {}, target: { entity_id: 'button.bosch_dw_start_pause' },
  });
  assert.equal(start.entityAction, true);

  assert.deepEqual(serviceCallFor(resolveNamedAction(card, 'power', '', { actions })), {
    domain: 'homeassistant', service: 'toggle', service_data: {}, target: { entity_id: 'switch.bosch_dw_power' },
  });

  assert.deepEqual(serviceCallFor(resolveNamedAction(card, 'eco_50', '', { actions })), {
    domain: 'home_connect', service: 'start_program',
    service_data: { program: 'Dishcare.Dishwasher.Program.Eco50' },
    target: { device_id: 'dw1' },
  });

  assert.deepEqual(serviceCallFor(resolveNamedAction(card, 'warm', '', { actions })), {
    domain: 'light', service: 'turn_on',
    service_data: { color_temp_kelvin: 2700 },
    target: { entity_id: 'light.living_room_hue' },
  });

  assert.deepEqual(serviceCallFor(resolveNamedAction(card, 'kettle_80', '', { actions })), {
    domain: 'number', service: 'set_value',
    service_data: { value: 80 },
    target: { entity_id: 'number.kettle_target_temp' },
  });

  assert.deepEqual(serviceCallFor(resolveNamedAction(card, 'mode_auto', '', { actions })), {
    domain: 'select', service: 'select_option',
    service_data: { option: 'Auto 45-65°' },
    target: { entity_id: 'select.bosch_dw_program' },
  });

  assert.deepEqual(resolveNamedAction(card, 'details', '', { actions }), {
    kind: 'MoreInfo', entityId: 'sensor.bosch_dw_operation_state', entityAction: true,
  });
});

test('semantic named actions auto-discover actionable sibling entities on the same device_id', async () => {
  const { resolveNamedAction } = await import('../ha-card.js');
  const card = { type: 'tile', entity: 'sensor.bosch_dw_operation_state' };
  const entities = {
    'sensor.bosch_dw_operation_state': { entity_id: 'sensor.bosch_dw_operation_state', device_id: 'dw1', translation_key: 'operation_state' },
    'button.bosch_dw_start_pause': { entity_id: 'button.bosch_dw_start_pause', device_id: 'dw1', translation_key: 'start_pause' },
    'switch.bosch_dw_power': { entity_id: 'switch.bosch_dw_power', device_id: 'dw1', translation_key: 'power' },
    'select.bosch_dw_program': { entity_id: 'select.bosch_dw_program', device_id: 'dw1', translation_key: 'selected_program' },
    'button.other_device_start_pause': { entity_id: 'button.other_device_start_pause', device_id: 'other', translation_key: 'start_pause' },
    'lock.front_door': { entity_id: 'lock.front_door', device_id: 'other', translation_key: 'front_door' },
  };

  assert.deepEqual(serviceCallFor(resolveNamedAction(card, 'start_pause', '', { entities })), {
    domain: 'button', service: 'press', service_data: {}, target: { entity_id: 'button.bosch_dw_start_pause' },
  });
  assert.deepEqual(serviceCallFor(resolveNamedAction(card, 'switch_power', '', { entities })), {
    domain: 'homeassistant', service: 'toggle', service_data: {}, target: { entity_id: 'switch.bosch_dw_power' },
  });
  assert.deepEqual(serviceCallFor(resolveNamedAction(card, 'selected_program', 'Eco 50°', { entities })), {
    domain: 'select', service: 'select_option', service_data: { option: 'Eco 50°' }, target: { entity_id: 'select.bosch_dw_program' },
  });
  // Unrelated device entities cannot be reached by a template's named action.
  assert.deepEqual(resolveNamedAction(card, 'front_door', '', { entities }), { kind: 'None' });
  assert.deepEqual(resolveNamedAction(card, 'lock.unlock', '', { entities }), { kind: 'None' });
});

test('semantic action visibility variables hide unmapped/suppressed actions and show mapped ones', async () => {
  const { cardBindings, semanticActionVariable, isNamedActionMapped } = await import('../ha-card.js');

  assert.deepEqual(semanticActionVariable('action_start_visible'), { action: 'start', kind: 'visibility' });
  assert.deepEqual(semanticActionVariable('action.open_cover.visible'), { action: 'open_cover', kind: 'visibility' });
  assert.deepEqual(semanticActionVariable('action_start'), { action: 'start', kind: 'action' });
  assert.equal(semanticActionVariable('sensor_temperature'), null);

  const card = { type: 'tile', entity: 'sensor.bosch_dw_operation_state' };
  const entities = {
    'sensor.bosch_dw_operation_state': { entity_id: 'sensor.bosch_dw_operation_state', device_id: 'dw1', translation_key: 'operation_state' },
    'button.bosch_dw_start_pause': { entity_id: 'button.bosch_dw_start_pause', device_id: 'dw1', translation_key: 'start_pause' },
    'button.bosch_dw_cancel': { entity_id: 'button.bosch_dw_cancel', device_id: 'dw1', translation_key: 'cancel' },
    'button.bosch_dw_rinse': { entity_id: 'button.bosch_dw_rinse', device_id: 'dw1', translation_key: 'rinse' },
    'button.bosch_dw_boost': { entity_id: 'button.bosch_dw_boost', device_id: 'dw1', translation_key: 'boost' },
    'button.bosch_dw_mute': { entity_id: 'button.bosch_dw_mute', device_id: 'dw1', translation_key: 'mute' },
    'sensor.bosch_dw_unmapped_btn': { entity_id: 'sensor.bosch_dw_unmapped_btn', device_id: 'dw1', translation_key: 'unmapped_btn_visible' },
  };
  const actions = {
    eco: { service: 'home_connect.start_program', data: { program: 'Eco50' } },
    'preset:quick': { service: 'home_connect.start_program', data: { program: 'Quick' } },
    // Explicit unmapping suppresses Device Registry auto-discovery.
    cancel: '',
    rinse: null,
    boost: false,
    mute: 'none',
    custom_none: { action: 'none' },
  };
  const namedVariables = new Map([
    ['USER:action_eco_visible', { type: 4 }],
    ['USER:action_start_pause_visible', { type: 4 }],
    ['USER:action_preset_visible', { type: 4 }],
    ['USER:action_cancel_visible', { type: 4 }],
    ['USER:action_rinse_visible', { type: 4 }],
    ['USER:action_boost_visible', { type: 4 }],
    ['USER:action_mute_visible', { type: 4 }],
    ['USER:action_custom_none_visible', { type: 4 }],
    ['USER:action_unmapped_btn_visible', { type: 4 }],
    ['USER:action.eco.visible', { type: 4 }],
    ['USER:action_eco', { type: 0 }],
    ['USER:action_unmapped_btn', { type: 0 }],
    ['USER:action_override_visible', { type: 4 }],
  ]);

  const bound = cardBindings(card, {}, null, {}, {
    actions,
    bindings: { action_override_visible: { entity: 'sensor.override', fallback: 1 } },
    entities,
    namedVariables,
  });

  // Mapped via explicit actions, Device Registry auto-discovery, or compound action key -> 1 (VISIBLE).
  assert.equal(bound.ints.action_eco_visible, 1);
  assert.equal(bound.ints.action_start_pause_visible, 1);
  assert.equal(bound.ints.action_preset_visible, 1);
  assert.equal(bound.ints['action.eco.visible'], 1);
  // String complement: action name when mapped, empty string when unmapped.
  assert.equal(bound.strings.action_eco, 'eco');
  assert.equal(bound.strings.action_unmapped_btn, '');
  // Explicitly suppressed or unmapped -> 0 (GONE), even when device registry has a matching sibling.
  assert.equal(bound.ints.action_cancel_visible, 0);
  assert.equal(bound.ints.action_rinse_visible, 0);
  assert.equal(bound.ints.action_boost_visible, 0);
  assert.equal(bound.ints.action_mute_visible, 0);
  assert.equal(bound.ints.action_custom_none_visible, 0);
  assert.equal(bound.ints.action_unmapped_btn_visible, 0);
  // Explicit bindings: entry always takes precedence over automatic action visibility.
  assert.equal(bound.ints.action_override_visible, 1);

  // Without actions/bindings/entities, unmapped buttons still bind to 0 (GONE) when namedVariables is present.
  const bare = cardBindings(
    { type: 'tile', entity: 'cover.garage' },
    { 'cover.garage': { state: 'closed', attributes: { supported_features: 1 } } },
    null,
    {},
    {
      namedVariables: new Map([
        ['USER:action_open_cover_visible', { type: 4 }],
        ['USER:action_stop_cover_visible', { type: 4 }],
      ]),
    },
  );
  assert.equal(bare.ints.action_open_cover_visible, 1);
  assert.equal(bare.ints.action_stop_cover_visible, 0);
  assert.equal(isNamedActionMapped({ type: 'tile', entity: 'cover.garage' }, 'open_cover', {
    states: { 'cover.garage': { state: 'closed', attributes: { supported_features: 1 } } },
  }), true);
});

test('changing the actions attribute re-renders action visibility overrides on a live card', async () => {
  const { HaCardElement } = await import('../ha-card.js');

  const attrs = new Map([
    ['card', JSON.stringify({ type: 'tile', entity: 'sensor.bosch_dw_operation_state' })],
    ['actions', JSON.stringify({ eco: { service: 'home_connect.start_program' } })],
  ]);
  const intOverrides = new Map();
  let repaints = 0;
  let invalidations = 0;
  let fits = 0;

  const context = {
    namedVariables: new Map([['USER:action_eco_visible', { type: 4 }]]),
    setNamedIntegerOverride: (key, value) => intOverrides.set(key, value),
    setNamedFloatOverride: () => {},
    setNamedStringOverride: () => {},
  };
  const cardEl = Object.create(HaCardElement.prototype);
  Object.assign(cardEl, {
    isConnected: true,
    states: {},
    pushed: new Map(),
    hostValues: {},
    localValues: null,
    entityRegistry: null,
    recipe: null,
    player: {
      getRemoteContext: () => context,
      getDocument: () => ({ invalidateMeasure: () => { invalidations += 1; } }),
      repaint: () => { repaints += 1; },
    },
    getAttribute: (name) => attrs.get(name) ?? null,
    fit: () => { fits += 1; },
    start: () => { throw new Error('changing actions must not reload the card'); },
  });

  // Initial render binds eco as visible (1).
  cardEl.render();
  assert.equal(intOverrides.get('USER:action_eco_visible'), 1);
  assert.equal(repaints, 1);

  // Suppressing eco via the actions attribute updates visibility to GONE (0) without reloading.
  const prev = attrs.get('actions');
  const next = JSON.stringify({ eco: '' });
  attrs.set('actions', next);
  cardEl.attributeChangedCallback('actions', prev, next);
  assert.equal(intOverrides.get('USER:action_eco_visible'), 0);
  assert.equal(repaints, 2);
  assert.equal(invalidations, 2);
  assert.equal(fits, 2);

  // Re-enabling eco via actions restores visibility to VISIBLE (1).
  const restored = JSON.stringify({ eco: { service: 'home_connect.start_program' } });
  attrs.set('actions', restored);
  cardEl.attributeChangedCallback('actions', next, restored);
  assert.equal(intOverrides.get('USER:action_eco_visible'), 1);
  assert.equal(repaints, 3);
});

