// Pure unit tests for terrazzo-panel.js route and promoted-panel helpers (no browser needed).
import { test } from 'node:test';
import assert from 'node:assert/strict';

globalThis.HTMLElement ??= class {};
globalThis.customElements ??= { define() {}, get() {} };

const { TerrazzoPanel, viewPathFromRoute, promotedPanelForDashboard, wallSettings } = await import(
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

test('setViewPath synchronizes _route so navigating back to the initial view notifies the listener', () => {
  const prevLocation = globalThis.location;
  const prevHistory = globalThis.history;
  const stubEl = () => ({ addEventListener() {}, setAttribute() {}, getAttribute() { return ''; } });
  const origAttachShadow = HTMLElement.prototype.attachShadow;
  HTMLElement.prototype.attachShadow = function () {
    const frame = stubEl();
    const cover = stubEl();
    this.shadowRoot = { querySelector: (sel) => (sel === 'iframe' ? frame : cover) };
    return this.shadowRoot;
  };
  const replaced = [];
  globalThis.location = { pathname: '/terrazzo-wall/kitchen', search: '', hash: '' };
  globalThis.history = {
    replaceState(_state, _title, url) {
      replaced.push(url);
      globalThis.location.pathname = url;
    },
  };
  try {
    const panel = new TerrazzoPanel();
    panel.panel = { url_path: 'terrazzo-wall', config: { dashboard: 'wall', pinned_dashboard: true } };
    panel.route = { prefix: '/terrazzo-wall', path: '/kitchen' };

    const seen = [];
    panel.frame.terrazzoHost.setViewPathListener((next) => seen.push(next));
    assert.equal(panel.frame.terrazzoHost.getViewPath(), 'kitchen');

    // Selecting the living tab updates both the URL and internal route state.
    panel.frame.terrazzoHost.setViewPath('living');
    assert.deepEqual(replaced, ['/terrazzo-wall/living']);
    assert.equal(panel.frame.terrazzoHost.getViewPath(), 'living');

    // A subsequent navigation back to kitchen must notify the listener instead of being suppressed.
    panel.route = { prefix: '/terrazzo-wall', path: '/kitchen' };
    assert.deepEqual(seen, ['kitchen']);

    // Selecting the root view clears the route path and notifies on subsequent non-root navigation.
    panel.frame.terrazzoHost.setViewPath('');
    assert.equal(panel.frame.terrazzoHost.getViewPath(), '');
    assert.equal(replaced.at(-1), '/terrazzo-wall');
    panel.route = { prefix: '/terrazzo-wall', path: '/kitchen' };
    assert.deepEqual(seen, ['kitchen', 'kitchen']);
  } finally {
    if (origAttachShadow === undefined) delete HTMLElement.prototype.attachShadow;
    else HTMLElement.prototype.attachShadow = origAttachShadow;
    globalThis.location = prevLocation;
    globalThis.history = prevHistory;
  }
});
