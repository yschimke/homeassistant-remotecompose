// Pure unit tests for terrazzo-panel.js route and promoted-panel helpers (no browser needed).
import { test } from 'node:test';
import assert from 'node:assert/strict';

globalThis.HTMLElement ??= class {};
globalThis.customElements ??= { define() {}, get() {} };

const { viewPathFromRoute, promotedPanelForDashboard, wallSettings } = await import(
  '../../custom_components/terrazzo/frontend/terrazzo-panel.js'
);

test('viewPathFromRoute extracts the first decoded sub-view segment', () => {
  assert.equal(viewPathFromRoute(null), '');
  assert.equal(viewPathFromRoute({ path: '' }), '');
  assert.equal(viewPathFromRoute({ path: '/' }), '');
  assert.equal(viewPathFromRoute({ path: '/kitchen' }), 'kitchen');
  assert.equal(viewPathFromRoute({ path: '/kitchen/sub' }), 'kitchen');
  assert.equal(viewPathFromRoute({ path: '/living%20room/' }), 'living room');
  assert.equal(viewPathFromRoute({ path: '/0' }), '0');
});

test('promotedPanelForDashboard finds matching top-level Terrazzo sidebar panels only', () => {
  const panels = {
    terrazzo: {
      url_path: 'terrazzo',
      show_in_sidebar: false,
      config: { dashboard: 'kitchen', terrazzo_managed: true },
    },
    'terrazzo-kitchen': {
      url_path: 'terrazzo-kitchen',
      show_in_sidebar: true,
      config: { dashboard: 'kitchen', terrazzo_managed: true, pinned_dashboard: true },
    },
    'terrazzo-lovelace': {
      url_path: 'terrazzo-lovelace',
      show_in_sidebar: true,
      config: { dashboard: '', terrazzo_managed: true, pinned_dashboard: true },
    },
    'other-panel': {
      url_path: 'other-panel',
      show_in_sidebar: true,
      config: { dashboard: 'living' },
    },
  };
  assert.equal(promotedPanelForDashboard(panels, 'kitchen', 'terrazzo'), 'terrazzo-kitchen');
  assert.equal(promotedPanelForDashboard(panels, 'kitchen', 'terrazzo-kitchen'), null);
  assert.equal(promotedPanelForDashboard(panels, '', 'terrazzo-kitchen'), 'terrazzo-lovelace');
  assert.equal(promotedPanelForDashboard(panels, 'living', 'terrazzo'), null);
  assert.equal(promotedPanelForDashboard(null, 'kitchen'), null);
});

test('wallSettings preserves existing query flag parsing', () => {
  assert.deepEqual(wallSettings('?wall'), {
    kiosk: true,
    keepAwake: true,
    returnAfter: 120,
    screensaverAfter: 600,
  });
});
