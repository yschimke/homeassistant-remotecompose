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
// Reproduce the packaged layout: the integration bundles the shared embed beside its adapter.
const directory = mkdtempSync(join(tmpdir(), 'ha-card-config-'));
let RemoteComposeCard;
try {
  copyFileSync(new URL('../../embed/ha-card.js', import.meta.url), join(directory, 'ha-card.js'));
  copyFileSync(new URL('../../custom_components/terrazzo/frontend/remote-compose-card.js', import.meta.url), join(directory, 'remote-compose-card.js'));
  ({ RemoteComposeCard } = await import(pathToFileURL(join(directory, 'remote-compose-card.js'))));
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
