/** Same-origin panel: never put a token in a URL, storage or an external message. */
export class TerrazzoPanel extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: 'open' }).innerHTML = `
      <style>:host { display:block;height:100%; } iframe { border:0;width:100%;height:100%; }</style>
      <iframe title="Terrazzo dashboard" allow="fullscreen"></iframe>`;
    this.frame = this.shadowRoot.querySelector('iframe');
    this.frame.terrazzoHost = {
      getAccessToken: async () => {
        const auth = this._hass?.auth;
        if (!this.isConnected || !auth) throw new Error('Home Assistant session unavailable');
        if (auth.expired) await auth.refreshAccessToken();
        return auth.accessToken;
      },
      getDashboard: () => this._panel?.config?.dashboard || '',
      dispatchAction: (payload) => {
        const action = JSON.parse(payload);
        const kind = action.type?.split('.').pop();
        if (kind === 'MoreInfo') {
          this.dispatchEvent(new CustomEvent('hass-more-info', {
            detail: { entityId: action.entityId }, bubbles: true, composed: true,
          }));
        } else if (kind === 'Navigate') {
          const url = new URL(action.path, location.href);
          if (url.origin !== location.origin) throw new Error('Navigation must stay in Home Assistant');
          history.pushState(null, '', url);
          window.dispatchEvent(new CustomEvent('location-changed', { detail: { replace: false } }));
        } else if (kind === 'Url') {
          if (/^https?:/i.test(action.url)) window.open(action.url, '_blank', 'noopener');
        } else return false;
        return true;
      },
    };
  }
  set hass(hass) { this._hass = hass; }
  set panel(panel) { this._panel = panel; }
  connectedCallback() {
    if (!this.frame.getAttribute('src') || this.frame.getAttribute('src') === 'about:blank') this.frame.src = '/terrazzo-static/wasm/index.html?ha-panel';
  }
  disconnectedCallback() {
    // Destroy the authenticated child and its sockets when leaving the panel.
    this.frame.removeAttribute('src');
    this.frame.src = 'about:blank';
  }
}
customElements.define('terrazzo-panel', TerrazzoPanel);
