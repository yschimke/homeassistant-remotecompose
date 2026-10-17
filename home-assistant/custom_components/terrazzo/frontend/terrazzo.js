import {HaCardElement, serviceCallFor} from './embed/ha-card.js?register=false';

function referencedStates(dashboard, states) {
  const ids = new Set();
  const visit = value => {
    if (typeof value === 'string' && /^[a-z_]+\.[a-z0-9_]+$/.test(value)) ids.add(value);
    else if (Array.isArray(value)) value.forEach(visit);
    else if (value && typeof value === 'object') Object.values(value).forEach(visit);
  };
  visit(dashboard);
  return Object.fromEntries([...ids].filter(id => states[id]).map(id => [id, states[id]]));
}

// HA already owns <ha-card>. Never replace it or depend on our embed owning that name.
class TerrazzoSurface extends HaCardElement {}
if (!customElements.get('terrazzo-surface')) customElements.define('terrazzo-surface', TerrazzoSurface);

export class TerrazzoCard extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({mode: 'open'});
    this.shadowRoot.addEventListener('ha-card-action', (event) => {
      // The wrapper owns actions through HA's current session. No second OAuth connection.
      event.preventDefault();
      if (this.config.read_only !== false || !this._hass) return;
      const call = serviceCallFor(event.detail);
      if (call) this._hass.callService(call.domain, call.service, call.service_data, call.target)
        .catch(() => { this.status.textContent = 'Home Assistant could not complete this action.'; });
      else if (event.detail.kind === 'MoreInfo') this.dispatchEvent(new CustomEvent('hass-more-info', {
        detail: {entityId: event.detail.entityId}, bubbles: true, composed: true,
      }));
    });
  }

  setConfig(config) {
    if (!config.card || typeof config.card.type !== 'string') throw new Error('Set card to a Lovelace card configuration.');
    if (!/^[0-9a-f]{64}$/.test(config.design ?? '')) throw new Error('Set design to a published Remote Compose template SHA-256.');
    const library = new URL(config.library ?? 'https://ha.coo.ee/designs/v1/', location.href);
    if (!['http:', 'https:'].includes(library.protocol)) throw new Error('library must be an HTTP(S) URL.');
    this.config = structuredClone(config);
    const surface = document.createElement('terrazzo-surface');
    surface.setAttribute('card', JSON.stringify(config.card));
    surface.setAttribute('design', config.design);
    surface.setAttribute('library', library.href);
    surface.setAttribute('theme', config.theme ?? 'auto');
    surface.setAttribute('read-only', '');
    if (config.height) surface.setAttribute('height', String(config.height));
    surface.setStates(this._hass?.states ?? {});
    this.status = document.createElement('p');
    this.status.setAttribute('role', 'status');
    this.surface = surface;
    const style = document.createElement('style');
    style.textContent = ':host { display: block; } p:empty { display: none; }';
    this.shadowRoot.replaceChildren(style, surface, this.status);
  }

  set hass(hass) {
    this._hass = hass;
    this.surface?.setStates(hass.states);
  }

  getCardSize() { return Math.max(1, Math.ceil((this.config?.height ?? 150) / 50)); }
  getGridOptions() { return {columns: 12, min_columns: 6, rows: 'auto'}; }
}

export class TerrazzoPanel extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({mode: 'open'});
    this.shadowRoot.innerHTML = `<style>
      :host { display:block; height:100%; min-height:0; }
      iframe { width:100%; height:100%; border:0; display:block; }
      p { font:16px system-ui; padding:16px; }
    </style><p role="status">Loading dashboard…</p>`;
    this.ready = false;
    this.generation = 0;
    this.onMessage = (event) => {
      if (event.origin !== location.origin || event.source !== this.frame?.contentWindow) return;
      if (event.data?.type !== 'terrazzo:ready') return;
      this.ready = true;
      this.lastSnapshot = null;
      this.publish();
    };
  }

  connectedCallback() {
    window.addEventListener('message', this.onMessage);
    this.load();
  }

  disconnectedCallback() {
    window.removeEventListener('message', this.onMessage);
    this.generation++;
    this.loading = false;
  }

  set panel(panel) {
    const next = panel.config?.dashboard ?? null;
    if (this.dashboardPath !== next) {
      this.dashboardPath = next;
      this.generation++;
      this.dashboard = null;
      this.loading = false;
      this.load();
    }
  }

  set hass(hass) {
    this._hass = hass;
    this.load();
    this.publish();
  }

  async load() {
    if (!this.isConnected || !this._hass || this.loading || this.dashboard) return;
    const generation = ++this.generation;
    this.loading = true;
    try {
      const command = {type: 'lovelace/config'};
      if (this.dashboardPath) command.url_path = this.dashboardPath;
      const dashboard = await this._hass.callWS(command);
      if (generation !== this.generation || !this.isConnected) return;
      this.dashboard = dashboard;
      if (!this.frame) {
        this.frame = document.createElement('iframe');
        this.frame.title = 'Terrazzo dashboard';
        this.frame.allow = 'fullscreen';
        this.frame.src = new URL('./wasm/index.html?host=homeassistant', import.meta.url).href;
        this.shadowRoot.querySelector('p').replaceWith(this.frame);
      }
      this.publish();
    } catch {
      if (generation === this.generation) {
        const status = this.shadowRoot.querySelector('p');
        if (status) status.textContent = 'Could not load the dashboard. Check your HA permissions and dashboard path.';
      }
    } finally {
      if (generation === this.generation) this.loading = false;
    }
  }

  publish() {
    if (!this.ready || !this.dashboard || !this._hass) return;
    // This message is data only. Never copy hass, auth or connection into an iframe.
    const message = {type: 'terrazzo:snapshot', dashboard: this.dashboard,
      states: referencedStates(this.dashboard, this._hass.states)};
    const encoded = JSON.stringify(message);
    if (encoded === this.lastSnapshot) return;
    this.lastSnapshot = encoded;
    this.frame.contentWindow.postMessage(message, location.origin);
  }
}

if (!customElements.get('terrazzo-card')) customElements.define('terrazzo-card', TerrazzoCard);
if (!customElements.get('terrazzo-panel')) customElements.define('terrazzo-panel', TerrazzoPanel);
window.customCards ??= [];
if (!window.customCards.some(card => card.type === 'terrazzo-card')) window.customCards.push({
  type: 'terrazzo-card', name: 'Terrazzo Remote Compose',
  description: 'A published Remote Compose template with live Home Assistant values.', preview: false,
});
