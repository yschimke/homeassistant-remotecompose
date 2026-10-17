import { HaCardElement, serviceCallFor } from './ha-card.js?register=false';

// Register a distinct tag: HA already owns <ha-card> for its card chrome.
export class RemoteComposeCard extends HaCardElement {
  static get observedAttributes() { return HaCardElement.observedAttributes; }
  setConfig(config) {
    if (!config.card || !['tile', 'button', 'entity', 'gauge'].includes(config.card.type)) {
      throw new Error('Remote Compose requires a tile, button, entity or gauge card with live bindings.');
    }
    this.config = structuredClone(config);
    // Set local mode before attributes can trigger an asynchronous start/OAuth.
    this.setStates(this._hass?.states ?? {});
    const attrs = {
      card: JSON.stringify(config.card),
      library: config.library ?? 'https://ha.coo.ee/designs/v1/',
      design: config.design,
      'card-style': config.card_style ?? 'classic',
      height: config.height,
      theme: this._hass?.themes?.darkMode ? 'dark' : 'light',
    };
    for (const [name, value] of Object.entries(attrs)) {
      if (value == null) this.removeAttribute(name);
      else this.setAttribute(name, String(value));
    }
  }
  set hass(hass) {
    this._hass = hass;
    this.setStates(hass.states);
    this.setAttribute('theme', hass.themes?.darkMode ? 'dark' : 'light');
  }
  getCardSize() { return Math.max(1, Math.ceil((this.offsetHeight || 120) / 50)); }
  getGridOptions() { return { columns: 12, rows: 'auto' }; }
  async tap(action) {
    if (!this._hass || !action || action.kind === 'None') return;
    const confirmation = this.config.card.tap_action?.confirmation;
    if (confirmation && !confirmation.exemptions?.some((e) => e.user === this._hass.user.id)) {
      if (!window.confirm(confirmation.text || 'Run this action?')) return;
    }
    try {
      if (action.kind === 'MoreInfo') {
        this.dispatchEvent(new CustomEvent('hass-more-info', {
          detail: { entityId: action.entityId }, bubbles: true, composed: true,
        }));
      } else if (action.kind === 'Navigate') {
        const url = new URL(action.path, location.href);
        if (url.origin !== location.origin) throw new Error('Navigation must stay in Home Assistant');
        history.pushState(null, '', url);
        window.dispatchEvent(new CustomEvent('location-changed', { detail: { replace: false } }));
      } else if (action.kind === 'Url') {
        if (/^https?:/i.test(action.url)) window.open(action.url, '_blank', 'noopener');
      } else {
        const call = serviceCallFor(action);
        if (call) {
          const configured = this.config.card.tap_action;
          // Keep HA's full target (entity arrays, area/device/floor IDs) and service data.
          const target = configured?.target ?? call.target;
          await this._hass.callService(call.domain, call.service, call.service_data, target);
        }
      }
    } catch (error) {
      this.setStatus('error', `Action failed: ${error.message}`);
    }
  }
}
customElements.define('remote-compose-card', RemoteComposeCard);
window.customCards = window.customCards || [];
window.customCards.push({ type: 'remote-compose-card', name: 'Remote Compose',
  description: 'Live Home Assistant card rendered by the TypeScript Remote Compose player',
  preview: false });
