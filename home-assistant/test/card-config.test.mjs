import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, copyFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

// Exercise HA's public setConfig contract without loading the browser/player.
const elements = new Map();
globalThis.customElements = { get: (name) => elements.get(name), define: (name, value) => elements.set(name, value) };
globalThis.window = {};
globalThis.document = { baseURI: 'https://ha.test/lovelace/home' };
// No test reaches the network: the default index request fails at once unless a test stubs it.
const fetched = [];
globalThis.fetch = async (url) => { fetched.push(String(url)); return { ok: false }; };
// Reproduce the packaged layout: the integration bundles the shared embed beside its adapter.
const directory = mkdtempSync(join(tmpdir(), 'ha-card-config-'));
let RemoteComposeCard, card;
try {
  copyFileSync(new URL('../../embed/ha-card.js', import.meta.url), join(directory, 'ha-card.js'));
  copyFileSync(new URL('../../custom_components/terrazzo/frontend/remote-compose-card.js', import.meta.url), join(directory, 'remote-compose-card.js'));
  card = await import(pathToFileURL(join(directory, 'remote-compose-card.js')));
  ({ RemoteComposeCard } = card);
} finally {
  rmSync(directory, { recursive: true, force: true });
}

function host() {
  const card = Object.create(RemoteComposeCard.prototype);
  card.attributes = new Map();
  card.setAttribute = (name, value) => card.attributes.set(name, value);
  card.removeAttribute = (name) => card.attributes.delete(name);
  card.setStates = (states) => { card.states = states; };
  return card;
}

const config = { card: { type: 'tile', entity: 'light.kitchen', tap_action: { action: 'toggle' } } };

test('HA configuration forwards a direct URL and clears it when selecting a library design', () => {
  const card = host();
  card.setConfig({ ...config, template_url: '/local/cards/tile.rc' });
  assert.equal(card.attributes.get('template-url'), '/local/cards/tile.rc');
  assert.ok(card.attributes.has('require-host-bindings'));
  assert.deepEqual(JSON.parse(card.attributes.get('card')), config.card);
  const states = { 'light.kitchen': { state: 'on', attributes: {} } };
  card.hass = { states, themes: { darkMode: true } };
  assert.equal(card.states, states);
  assert.equal(card.attributes.get('theme'), 'dark');
  card.setConfig({ ...config, design: 'a'.repeat(64) });
  assert.equal(card.attributes.has('template-url'), false);
  assert.equal(card.attributes.get('design'), 'a'.repeat(64));
  card.setConfig({ ...config, template_url: 'https://templates.test/tile.rc' });
  assert.equal(card.attributes.has('design'), false);
});

test('invalid URL settings fail before changing an existing HA card', () => {
  const card = host();
  card.setConfig(config);
  const previous = card.config;
  for (const template_url of ['', '   ', 42, {}, 'javascript:alert(1)', 'data:text/plain,test', 'https://user:password@templates.test/tile.rc']) {
    assert.throws(() => card.setConfig({ ...config, template_url }), /template_url/);
    assert.equal(card.config, previous);
  }
  assert.throws(() => card.setConfig({ ...config, template_url: '/local/tile.rc', design: 'a'.repeat(64) }), /not both/);
});

test('a watchface card plays a published face without host bindings, square by default', () => {
  const card = host();
  card.setConfig(config);
  assert.ok(card.attributes.has('require-host-bindings'));
  card.setConfig({ card: { type: 'watchface', face: 'aurora' } });
  assert.equal(card.attributes.has('require-host-bindings'), false);
  assert.equal(card.attributes.get('height'), '450');
  card.setConfig({ card: { type: 'watchface', face: 'aurora', size: 300 } });
  assert.equal(card.attributes.get('height'), '300');
  card.setConfig({ card: { type: 'watchface', face: 'aurora' }, height: 200 });
  assert.equal(card.attributes.get('height'), '200');
  for (const face of [undefined, '', '  ', 7]) {
    assert.throws(() => card.setConfig({ card: { type: 'watchface', face } }), /face/);
  }
  for (const size of [0, 63, 1025, 1.5, '300']) {
    assert.throws(() => card.setConfig({ card: { type: 'watchface', face: 'aurora', size } }), /size/);
  }
});

// The card picker (`window.customCards`, `getStubConfig`, `getConfigForm`, `getEntitySuggestion`).
const printerHass = {
  states: { 'light.printer_chamber': { state: 'on', attributes: {} }, 'light.kitchen': { state: 'off', attributes: {} } },
  entities: {
    'light.printer_chamber': { entity_id: 'light.printer_chamber', device_id: 'd1', platform: 'bambu_lab', translation_key: 'chamber_light' },
    'light.kitchen': { entity_id: 'light.kitchen', device_id: 'd2', platform: 'hue' },
    'sensor.no_device': { entity_id: 'sensor.no_device', platform: 'template' },
  },
  devices: {
    d1: { id: 'd1', manufacturer: 'Bambu Lab', model: 'X1 Carbon' },
    d2: { id: 'd2', manufacturer: 'Signify', model: 'LCT015' },
  },
};
const printerIndex = {
  serial: 3,
  designs: [
    { designId: 'user:chamber', cardType: 'tile', device: { integration: 'bambu_lab', model: 'x1*', translationKey: 'chamber_light' }, title: 'Chamber light' },
    { designId: 'user:printer', cardType: 'tile', device: { integration: 'bambu_lab' }, title: 'Bambu printer', domain: 'light' },
    { designId: 'user:printer', cardType: 'tile', device: { manufacturer: 'bambu lab' }, title: 'Bambu printer', domain: 'light' },
    { designId: 'user:printer-graph', cardType: 'history-graph', device: { integration: 'bambu_lab' } },
    { designId: 'user:p1', cardType: 'tile', device: { integration: 'bambu_lab', model: 'P1*' } },
    { designId: 'user:untitled', cardType: 'gauge', device: { manufacturer: 'Bambu Lab' } },
    { designId: 'user:nozzle', cardType: 'tile', device: { integration: 'bambu_lab' }, domain: 'sensor', title: 'Nozzle' },
  ],
};

test('the picker lists the card with a live preview, its documentation and suggestions', () => {
  const entry = globalThis.window.customCards.find((c) => c.type === 'remote-compose-card');
  assert.equal(entry.preview, true);
  assert.match(entry.documentationURL, /^https:\/\/github\.com\/yschimke\/homeassistant-remotecompose\//);
  assert.equal(entry.getEntitySuggestion, card.getEntitySuggestion);
});

test('device facts come from the registries Home Assistant already holds', () => {
  assert.deepEqual(card.deviceInfo(printerHass, 'light.printer_chamber'), {
    integration: 'bambu_lab', manufacturer: 'Bambu Lab', model: 'X1 Carbon', translationKey: 'chamber_light', deviceClass: null,
  });
  assert.deepEqual(card.deviceInfo(printerHass, 'sensor.no_device'), {
    integration: 'template', manufacturer: null, model: null, translationKey: null, deviceClass: null,
  });
  assert.equal(card.deviceInfo(printerHass, 'light.unknown'), null);
  assert.equal(card.deviceInfo({ states: {} }, 'light.kitchen'), null);
});

test('device matching follows DeviceMatch: every set field, without case, a model glob', () => {
  const device = card.deviceInfo(printerHass, 'light.printer_chamber');
  assert.ok(card.deviceMatches({ integration: 'BAMBU_LAB' }, device));
  assert.ok(card.deviceMatches({ model: 'x1*' }, device));
  assert.ok(card.deviceMatches({ model: '*carbon' }, device));
  assert.ok(!card.deviceMatches({ model: 'x1' }, device), 'a model without a wildcard is whole');
  assert.ok(!card.deviceMatches({ model: 'X1 (Carbon' }, device), 'regex characters are literal');
  assert.ok(!card.deviceMatches({ integration: 'bambu_lab', manufacturer: 'Prusa' }, device));
  assert.ok(!card.deviceMatches({}, device), 'an empty match matches nothing');
  assert.ok(!card.deviceMatches({ manufacturer: 'Bambu Lab' }, card.deviceInfo(printerHass, 'sensor.no_device')));
});

test('an entity is offered the designs made for its device, most specific first', () => {
  const suggestions = card.entitySuggestions(printerHass, 'light.printer_chamber', printerIndex);
  assert.deepEqual(suggestions, [
    { config: { type: 'custom:remote-compose-card', card: { type: 'tile', entity: 'light.printer_chamber' }, design_id: 'user:chamber' }, label: 'Chamber light' },
    { config: { type: 'custom:remote-compose-card', card: { type: 'tile', entity: 'light.printer_chamber' }, design_id: 'user:printer' }, label: 'Bambu printer' },
    { config: { type: 'custom:remote-compose-card', card: { type: 'gauge', entity: 'light.printer_chamber' }, design_id: 'user:untitled' }, label: 'Made for this device (gauge)' },
  ]);
  // Each suggestion is a config the card accepts as it is.
  for (const { config } of suggestions) host().setConfig(config);
});

test('a model design is listed before a brand design that sets more fields', () => {
  const index = { designs: [
    { designId: 'user:brand', cardType: 'tile', device: { integration: 'bambu_lab', manufacturer: 'Bambu Lab' } },
    { designId: 'user:model', cardType: 'tile', device: { model: 'X1*' } },
    { designId: 'user:role', cardType: 'tile', device: { translationKey: 'chamber_light' } },
  ] };
  const ids = card.entitySuggestions(printerHass, 'light.printer_chamber', index).map((s) => s.config.design_id);
  assert.deepEqual(ids, ['user:model', 'user:brand', 'user:role']);
  assert.ok(card.deviceRank({ integration: 'a', translationKey: 'b' }) > card.deviceRank({ integration: 'a' }));
  assert.equal(card.deviceRank({ model: ' ' }), 0);
});

test('a design for a class (every garage door) is offered on any entity of that class, after device designs', () => {
  const hass = {
    states: {
      'cover.garage': { state: 'closed', attributes: { device_class: 'garage' } },
      'cover.gate': { state: 'closed', attributes: { device_class: 'gate' } },
    },
    entities: {
      'cover.garage': { platform: 'ratgdo', device_id: 'd1' },
      'cover.gate': { platform: 'ratgdo', device_id: 'd1' },
    },
    devices: { d1: { manufacturer: 'Chamberlain', model: 'Security+ 2.0' } },
  };
  const index = { designs: [
    { designId: 'pack:garage-door-controls-skeuomorphic', cardType: 'tile', device: { deviceClass: 'garage' }, domain: 'cover', title: 'Garage door · Wall console' },
    { designId: 'user:ratgdo', cardType: 'tile', device: { integration: 'ratgdo' }, domain: 'cover', title: 'ratgdo' },
  ] };
  assert.equal(card.deviceInfo(hass, 'cover.garage').deviceClass, 'garage');
  const unregistered = { ...hass, entities: {} };
  assert.deepEqual(card.deviceInfo(unregistered, 'cover.garage'),
    { integration: null, manufacturer: null, model: null, translationKey: null, deviceClass: 'garage' });
  assert.deepEqual(card.entitySuggestions(unregistered, 'cover.garage', index).map((s) => s.config.design_id),
    ['pack:garage-door-controls-skeuomorphic']);
  assert.deepEqual(card.entitySuggestions(hass, 'cover.garage', index).map((s) => s.config.design_id),
    ['user:ratgdo', 'pack:garage-door-controls-skeuomorphic']);
  assert.deepEqual(card.entitySuggestions(hass, 'cover.gate', index).map((s) => s.config.design_id), ['user:ratgdo']);
  assert.ok(card.deviceRank({ translationKey: 'door' }) > card.deviceRank({ deviceClass: 'garage' }));
  assert.ok(card.deviceRank({ deviceClass: 'garage' }) > 0);
});

test('an entity with nothing made for its device gets no suggestions', () => {
  assert.deepEqual(card.entitySuggestions(printerHass, 'light.kitchen', printerIndex), []);
  assert.deepEqual(card.entitySuggestions(printerHass, 'sensor.no_device', printerIndex), []);
  assert.deepEqual(card.entitySuggestions(printerHass, 'light.printer_chamber', null), []);
  assert.deepEqual(card.entitySuggestions(printerHass, 'light.printer_chamber', { designs: 'nope' }), []);
  assert.equal(card.getEntitySuggestion(printerHass, 'light.kitchen'), null);
});

test('the device index is fetched once, without credentials, and a failure leaves no suggestions', async () => {
  const seen = [];
  const fetcher = async (url, init) => { seen.push({ url, init }); return { ok: true, json: async () => printerIndex }; };
  const library = 'https://designs.test/designs/v1/';
  assert.deepEqual(await card.loadSuggestionIndex(library, fetcher), printerIndex);
  await card.loadSuggestionIndex(library, fetcher);
  assert.equal(seen.length, 1);
  assert.equal(seen[0].url, 'https://designs.test/designs/v1/suggestions.json');
  assert.deepEqual(seen[0].init, { credentials: 'omit', referrerPolicy: 'no-referrer' });
  const failing = async () => { throw new Error('offline'); };
  assert.equal(await card.loadSuggestionIndex('https://down.test/designs/v1/', failing), null);
  let calls = 0;
  const counting = async () => { calls += 1; throw new Error('offline'); };
  await card.loadSuggestionIndex('https://down.test/designs/v1/', counting);
  assert.equal(calls, 1, 'a failed index is asked for again');
});

test('a new card starts as a tile of an unused entity a tile shows well', () => {
  const stub = RemoteComposeCard.getStubConfig(printerHass, ['sensor.no_device', 'light.kitchen'], []);
  assert.deepEqual(stub, { card: { type: 'tile', entity: 'light.kitchen' } });
  assert.equal(card.stubEntity({ states: {} }, [], ['switch.fan']), 'switch.fan');
  assert.equal(card.stubEntity({ states: { 'sensor.t': {} } }), 'sensor.t');
  assert.equal(card.stubEntity(undefined), 'sun.sun');
  host().setConfig(stub);
});

test('the visual editor edits the HA card and its style, and leaves other cards to YAML', () => {
  const form = RemoteComposeCard.getConfigForm();
  assert.equal(form.schema[0].name, 'card');
  assert.deepEqual(form.schema[0].schema.map((f) => f.name), ['type', 'entity', 'name']);
  assert.ok(form.schema[1].selector.select.options.some((o) => o.value === 'lcd'));
  form.assertConfig({ card: { type: 'gauge', entity: 'sensor.t' }, design_id: 'library:gauge' });
  assert.throws(() => form.assertConfig({ card: { type: 'watchface', face: 'aurora' } }), /YAML/);
  assert.throws(() => form.assertConfig({ ...config, template_url: '/local/a.rc' }), /YAML/);
});

test('a card set to a design sends its id to the resolver, and only on its own', () => {
  const element = host();
  element.setConfig({ ...config, design_id: 'user:chamber' });
  assert.equal(element.attributes.get('design-id'), 'user:chamber');
  element.setConfig(config);
  assert.equal(element.attributes.has('design-id'), false);
  for (const design_id of ['', ' ', 7, 'a\nb', 'x'.repeat(257)]) {
    assert.throws(() => element.setConfig({ ...config, design_id }), /design_id/);
  }
  assert.throws(() => element.setConfig({ ...config, design_id: 'library:tile', design: 'a'.repeat(64) }), /not more than one/);
  assert.throws(() => element.setConfig({ ...config, design_id: 'library:tile', template_url: '/local/a.rc' }), /not more than one/);
});

test('a design made for another domain of the same device is not offered', () => {
  const hass = structuredClone(printerHass);
  hass.entities['sensor.printer_nozzle'] = { entity_id: 'sensor.printer_nozzle', device_id: 'd1', platform: 'bambu_lab' };
  const ids = card.entitySuggestions(hass, 'sensor.printer_nozzle', printerIndex).map((s) => s.config.design_id);
  assert.deepEqual(ids, ['user:untitled', 'user:nozzle']);
  assert.ok(!card.entitySuggestions(printerHass, 'light.printer_chamber', printerIndex)
    .some((s) => s.config.design_id === 'user:nozzle'));
});

test('an administrator page fetches the index ahead of the picker; no other page does', async () => {
  const root = (user) => ({ querySelector: (tag) => (tag === 'home-assistant' ? { hass: { user } } : null) });
  // Let any earlier test's failed request settle and be forgotten.
  await new Promise((resolve) => setTimeout(resolve, 0));
  fetched.length = 0;
  card.preloadForEditors(root({ is_admin: false }));
  card.preloadForEditors(root(undefined));
  card.preloadForEditors({ querySelector: () => { throw new Error('no DOM'); } });
  assert.equal(fetched.length, 0);
  card.preloadForEditors(root({ is_admin: true }));
  assert.deepEqual(fetched, ['https://ha.coo.ee/designs/v1/suggestions.json']);
});

test('Home Assistant\'s own unit system reaches a design\'s recipe, so a °F thermostat reads °F', () => {
  const card = host();
  card.setConfig({ card: { type: 'tile', entity: 'climate.den' } });
  assert.deepEqual(card.hostValues, {});
  card.hass = { states: {}, themes: {}, config: { unit_system: { temperature: '°F' } } };
  assert.deepEqual(card.hostValues, { temperature_unit: '°F' });
  card.setConfig({ card: { type: 'tile', entity: 'climate.den' }, card_style: 'braun' });
  assert.deepEqual(card.hostValues, { temperature_unit: '°F' });
});

test('an entity action runs on the card\'s own entity, never on the tap action\'s target', async () => {
  const card = host();
  card.hasAttribute = (name) => card.attributes.has(name);
  const calls = [];
  card.setConfig({
    card: { type: 'tile', entity: 'cover.my_garage', tap_action: { action: 'toggle', target: { entity_id: 'cover.other' } } },
  });
  card.hass = { states: {}, themes: {}, user: { id: 'u' }, callService: async (...args) => calls.push(args) };
  await card.tap({
    kind: 'CallService', domain: 'cover', service: 'close_cover', entityId: 'cover.my_garage', serviceData: {}, entityAction: true,
  });
  await card.tap({ kind: 'Toggle', entityId: 'cover.my_garage' });
  assert.deepEqual(calls, [
    ['cover', 'close_cover', {}, { entity_id: 'cover.my_garage' }],
    // The card's own tap keeps its configured target.
    ['homeassistant', 'toggle', {}, { entity_id: 'cover.other' }],
  ]);
});

test('bindings, actions, device_id, and hass.entities are validated and forwarded to ha-card', () => {
  const el = host();
  const bindings = {
    sensor_operation_state: 'sensor.dw_state',
    sensor_remaining_program_time: { entity: 'sensor.dw_remaining', format: 'hhmm', fallback: '--:--' },
  };
  const actions = {
    start: 'button.dw_start',
    eco_50: { service: 'home_connect.start_program', data: { program: 'Eco50' } },
  };
  el.setConfig({
    card: { type: 'tile', entity: 'sensor.dw_state' },
    design_id: 'pack:bosch-dishwasher',
    device_id: 'dev_dw1',
    bindings,
    actions,
  });
  assert.equal(el.attributes.get('device-id'), 'dev_dw1');
  assert.deepEqual(JSON.parse(el.attributes.get('bindings')), bindings);
  assert.deepEqual(JSON.parse(el.attributes.get('actions')), actions);

  const entities = { 'sensor.dw_state': { entity_id: 'sensor.dw_state', device_id: 'dev_dw1' } };
  el.hass = { states: {}, entities, themes: {} };
  assert.equal(el.entityRegistry, entities);

  // Invalid values fail before changing existing config.
  const previous = el.config;
  for (const bad of ['', '   ', 42]) {
    assert.throws(() => el.setConfig({ ...config, device_id: bad }), /device_id/);
    assert.equal(el.config, previous);
  }
  for (const bad of ['nope', [], { a: '' }, { a: 123 }]) {
    assert.throws(() => el.setConfig({ ...config, bindings: bad }), /bindings/);
    assert.throws(() => el.setConfig({ ...config, actions: bad }), /actions/);
    assert.equal(el.config, previous);
  }

  // Cards with custom bindings or actions are edited in YAML.
  const form = RemoteComposeCard.getConfigForm();
  assert.throws(() => form.assertConfig({ card: { type: 'tile', entity: 'sensor.dw_state' }, bindings }), /YAML/);
  assert.throws(() => form.assertConfig({ card: { type: 'tile', entity: 'sensor.dw_state' }, actions }), /YAML/);
});
