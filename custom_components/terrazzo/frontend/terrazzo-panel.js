/** `?wall` turns every wall-panel behaviour on; each can also be set or overridden on its own. */
const WALL_PRESET = { kiosk: true, keepAwake: true, returnAfter: 120, screensaverAfter: 600 };
const OFF = { kiosk: false, keepAwake: false, returnAfter: 0, screensaverAfter: 0 };

/** Wall-panel settings from the bookmarked URL, so only the device that asks for them gets them. */
export function wallSettings(search) {
  const query = new URLSearchParams(search);
  const on = (name) => query.has(name) && !['0', 'false', 'off'].includes(query.get(name).toLowerCase());
  const base = on('wall') ? WALL_PRESET : OFF;
  const flag = (name, fallback) => (query.has(name) ? on(name) : fallback);
  const seconds = (name, fallback) => {
    if (!query.has(name)) return fallback;
    const value = Number(query.get(name));
    return Number.isFinite(value) && value > 0 ? value : 0;
  };
  return {
    kiosk: flag('kiosk', base.kiosk),
    keepAwake: flag('keep_awake', base.keepAwake),
    returnAfter: seconds('return_after', base.returnAfter),
    screensaverAfter: seconds('screensaver_after', base.screensaverAfter),
  };
}

/** Home Assistant's theme variables the dashboard draws its chrome in. */
const THEME_VARIABLES = {
  primary: '--primary-color',
  background: '--primary-background-color',
  card: '--card-background-color',
  text: '--primary-text-color',
  secondaryText: '--secondary-text-color',
  divider: '--divider-color',
};

/** A CSS color as a canvas normalizes it (`#rrggbb` or `rgba(…)`), or '' when it isn't one. */
function normalColor(value) {
  const probe = value && document.createElement('canvas').getContext('2d');
  if (!probe) return '';
  // An invalid color leaves the previous fill in place, so two different starting fills disagree.
  probe.fillStyle = '#000000';
  probe.fillStyle = value;
  const first = probe.fillStyle;
  probe.fillStyle = '#ffffff';
  probe.fillStyle = value;
  return first === probe.fillStyle ? first : '';
}

// `click` too: accessibility activation (screen readers, keyboard) sends no pointerdown.
const ACTIVITY = ['pointerdown', 'click', 'keydown', 'wheel'];

/** Same-origin panel: never put a token in a URL, storage or an external message. */
export class TerrazzoPanel extends HTMLElement {
  constructor() {
    super();
    this.attachShadow({ mode: 'open' }).innerHTML = `
      <style>
        :host { display:block;position:relative;height:100vh;height:100dvh;background:#000; }
        iframe { display:block;border:0;width:100%;height:100%; }
        .cover { position:absolute;inset:0;display:flex;align-items:center;justify-content:center;
          font-family:system-ui,sans-serif;color:#fff;cursor:pointer;user-select:none;-webkit-user-select:none; }
        .cover[hidden] { display:none; }
        .cover.start { background:rgba(0,0,0,.6);font-size:24px; }
        .cover.screensaver { background:#000;display:block; }
        .clock { position:absolute;text-align:center;transform:translate(-50%,-50%);opacity:.75; }
        .time { font-size:min(18vw,160px);font-weight:200;line-height:1; }
        .date { font-size:min(4vw,28px);margin-top:12px; }
        .start .clock { display:none; }
      </style>
      <iframe title="Terrazzo dashboard" allow="fullscreen"></iframe>
      <div class="cover" hidden role="button" tabindex="0">
        <span class="hint"></span>
        <div class="clock"><div class="time"></div><div class="date"></div></div>
      </div>`;
    this.frame = this.shadowRoot.querySelector('iframe');
    this.cover = this.shadowRoot.querySelector('.cover');
    this.settings = OFF;
    this.activity = () => this.active();
    // On click, not pointerdown: the whole tap (down, up, click) lands on the cover and none of it
    // reaches the dashboard or counts as use.
    this.cover.addEventListener('click', (event) => this.dismiss(event));
    this.cover.addEventListener('keydown', (event) => this.dismiss(event));
    // The dashboard's own taps happen in the iframe's document, which is same-origin.
    this.frame.addEventListener('load', () => {
      const view = this.frame.contentWindow;
      if (view && this.frame.getAttribute('src') !== 'about:blank')
        for (const type of ACTIVITY) view.addEventListener(type, this.activity, { capture: true, passive: true });
    });
    this.onVisibility = () => { if (document.visibilityState === 'visible') this.keepAwake(); };
    this.onFullscreen = () => {
      this.fullscreenListener?.(!!this.fullscreenElement());
      if (!this.fullscreenElement()) this.showStart();
    };
    this.frame.terrazzoHost = {
      getAccessToken: async () => {
        const auth = this._hass?.auth;
        if (!this.isConnected || !auth) throw new Error('Home Assistant session unavailable');
        if (auth.expired) await auth.refreshAccessToken();
        return auth.accessToken;
      },
      getDashboard: () => {
        // A bookmark can target any dashboard; configuration is only the default.
        const query = new URLSearchParams(location.search);
        return query.has('dashboard') ? query.get('dashboard') : this._panel?.config?.dashboard || '';
      },
      // The dashboard hands over how to go back to where it started, for `return_after`.
      setReturnHome: (returnHome) => { this.returnHome = returnHome; },
      // HA's active theme, so the dashboard's chrome matches the rest of Home Assistant.
      getTheme: () => this.theme(),
      setThemeListener: (listener) => { this.themeListener = listener; },
      // Full screen of the whole panel (HA's sidebar and header hidden), not just the frame.
      toggleFullscreen: () => {
        if (this.fullscreenElement()) (document.exitFullscreen || document.webkitExitFullscreen)?.call(document);
        else this.enterFullscreen();
      },
      isFullscreen: () => !!this.fullscreenElement(),
      setFullscreenListener: (listener) => { this.fullscreenListener = listener; },
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
  set hass(hass) {
    this._hass = hass;
    // HA replaces `themes` when the theme or dark mode changes; its CSS variables follow a frame later.
    if (hass?.themes !== this._themes) {
      this._themes = hass?.themes;
      requestAnimationFrame(() => { if (this.isConnected) this.themeListener?.(this.theme()); });
    }
  }
  /** JSON: HA's dark mode and resolved theme colors; a color the theme doesn't set is ''. */
  theme() {
    const style = getComputedStyle(this);
    const colors = Object.fromEntries(Object.entries(THEME_VARIABLES)
      .map(([name, variable]) => [name, normalColor(style.getPropertyValue(variable).trim())]));
    const dark = this._hass?.themes?.darkMode ?? matchMedia('(prefers-color-scheme: dark)').matches;
    return JSON.stringify({ dark: !!dark, ...colors });
  }
  set panel(panel) { this._panel = panel; }
  connectedCallback() {
    if (!this.frame.getAttribute('src') || this.frame.getAttribute('src') === 'about:blank') this.frame.src = '/terrazzo-static/wasm/index.html?ha-panel';
    this.settings = wallSettings(location.search);
    this.lastActive = Date.now();
    this.used = false;
    for (const type of ACTIVITY) this.addEventListener(type, this.activity, { capture: true, passive: true });
    document.addEventListener('visibilitychange', this.onVisibility);
    document.addEventListener('fullscreenchange', this.onFullscreen);
    document.addEventListener('webkitfullscreenchange', this.onFullscreen);
    this.keepAwake();
    this.showStart();
    const { returnAfter, screensaverAfter } = this.settings;
    if (returnAfter || screensaverAfter) this.timer = setInterval(() => this.tick(), Math.min(1000, 250 * Math.min(...[returnAfter, screensaverAfter].filter(Boolean))));
  }
  disconnectedCallback() {
    // Destroy the authenticated child and its sockets when leaving the panel.
    this.frame.removeAttribute('src');
    this.frame.src = 'about:blank';
    clearInterval(this.timer);
    for (const type of ACTIVITY) this.removeEventListener(type, this.activity, { capture: true });
    document.removeEventListener('visibilitychange', this.onVisibility);
    document.removeEventListener('fullscreenchange', this.onFullscreen);
    document.removeEventListener('webkitfullscreenchange', this.onFullscreen);
    this.wakeLock?.release().catch(() => {});
    this.wakeLock = null;
    this.returnHome = null;
    this.themeListener = null;
    this.fullscreenListener = null;
    this.cover.hidden = true;
  }
  active() {
    this.lastActive = Date.now();
    this.used = true;
  }
  tick() {
    const idle = (Date.now() - this.lastActive) / 1000;
    const { returnAfter, screensaverAfter } = this.settings;
    // Only after someone used it: an untouched panel is already where it started.
    if (returnAfter && this.used && idle >= returnAfter) {
      this.used = false;
      this.returnHome?.();
    }
    if (screensaverAfter && idle >= screensaverAfter && this.cover.hidden) this.showScreensaver();
    if (this.cover.classList.contains('screensaver')) this.drawClock();
  }
  /** A tap on the cover wakes the panel or starts full screen; it never reaches the dashboard. */
  dismiss(event) {
    if (event.type === 'keydown' && !['Enter', ' ', 'Escape'].includes(event.key)) return;
    event.preventDefault();
    event.stopPropagation();
    if (this.cover.classList.contains('start')) this.enterFullscreen();
    this.cover.hidden = true;
    this.cover.className = 'cover';
    this.active();
    this.used = false;
    this.keepAwake();
  }
  showScreensaver() {
    this.cover.className = 'cover screensaver';
    this.cover.querySelector('.hint').textContent = '';
    this.cover.setAttribute('aria-label', 'Screensaver. Tap to return to the dashboard.');
    this.clockMoved = 0;
    this.cover.hidden = false;
    this.drawClock();
  }
  drawClock() {
    const now = new Date();
    const language = this._hass?.locale?.language || navigator.language;
    this.cover.querySelector('.time').textContent = now.toLocaleTimeString(language, { hour: 'numeric', minute: '2-digit' });
    this.cover.querySelector('.date').textContent = now.toLocaleDateString(language, { weekday: 'long', day: 'numeric', month: 'long' });
    // Drift once a minute so a clock left on all night doesn't burn into the screen.
    if (Date.now() - this.clockMoved >= 60_000) {
      this.clockMoved = Date.now();
      const clock = this.cover.querySelector('.clock');
      clock.style.left = `${25 + Math.random() * 50}%`;
      clock.style.top = `${25 + Math.random() * 50}%`;
    }
  }
  fullscreenElement() { return document.fullscreenElement || document.webkitFullscreenElement; }
  canFullscreen() { return !!(this.requestFullscreen || this.webkitRequestFullscreen); }
  /** Kiosk: full screen hides HA's sidebar and the browser's own bars, but needs a tap to start. */
  showStart() {
    if (!this.isConnected || !this.settings.kiosk || !this.canFullscreen() || this.fullscreenElement()) return;
    this.cover.className = 'cover start';
    this.cover.querySelector('.hint').textContent = 'Tap to start full screen';
    this.cover.setAttribute('aria-label', 'Start full screen');
    this.cover.hidden = false;
  }
  enterFullscreen() {
    const request = this.requestFullscreen || this.webkitRequestFullscreen;
    // A refusal sends no fullscreenchange, so offer the tap again here.
    Promise.resolve(request?.call(this, { navigationUI: 'hide' })).catch(() => this.showStart());
  }
  /** Screen Wake Lock: browsers grant it only to HTTPS pages, and drop it whenever the tab hides. */
  async keepAwake() {
    if (!this.settings.keepAwake || !this.isConnected || document.visibilityState !== 'visible') return;
    if (!navigator.wakeLock) {
      if (!this.warnedWakeLock) console.info('Terrazzo: keep_awake needs HTTPS (Screen Wake Lock); this page cannot keep the screen on.');
      this.warnedWakeLock = true;
      return;
    }
    if ((this.wakeLock && !this.wakeLock.released) || this.wakeLockPending) return;
    this.wakeLockPending = true;
    try {
      const lock = await navigator.wakeLock.request('screen');
      // Granted after the panel was left: let the screen sleep again.
      if (this.isConnected) this.wakeLock = lock;
      else lock.release().catch(() => {});
    } catch {
      // Refused (battery saver, no permission); the next tap or visibility change asks again.
    } finally {
      this.wakeLockPending = false;
    }
  }
}
customElements.define('terrazzo-panel', TerrazzoPanel);
