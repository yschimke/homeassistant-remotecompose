// Capability detection is per page. Static hosts, the HA panel and demo keep browser storage.
(() => {
  // reason says why a page that could have had a backend fell back: 'not-enabled' when the host
  // answered without one, 'unavailable' when it didn't answer in time.
  const local = (reason = null) => ({ kind: 'local', caps: null, reason });

  async function probe(timeoutMs) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch('/api/wasmo/v1/capabilities', {
        credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal,
      });
      if (response.status >= 500) return local('unavailable');
      if (response.status !== 200 || !/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? '')) {
        return local('not-enabled');
      }
      const caps = await response.json().catch(() => null);
      return caps?.api === 1 ? { kind: 'wasmo', caps } : local('not-enabled');
    } catch {
      return local('unavailable');
    } finally {
      clearTimeout(timeout);
    }
  }

  async function detect() {
    // Accessing frameElement can throw for a cross-origin embedding page.
    try {
      if (window.frameElement?.terrazzoHost) return local();
    } catch {
      return local();
    }
    if (/\/demo\/?$/.test(location.pathname) || new URLSearchParams(location.search).get('demo') === '1') {
      return local();
    }
    const first = await probe(3000);
    // A backend that is starting up or briefly overloaded gets one longer second chance.
    return first.reason === 'unavailable' ? probe(6000) : first;
  }

  const backend = window.terrazzoBackend = {
    ready: detect(),
    // A relative API path is deliberate: credentials must never leave the app origin.
    request(path, options = {}) {
      if (!/^[a-z0-9][a-z0-9._-]*(?:\/[a-z0-9][a-z0-9._-]*)*$/.test(path)) throw new Error('Invalid API path');
      const headers = new Headers(options.headers);
      headers.set('X-Wasmo-Api', '1');
      return fetch(`/api/wasmo/v1/${path}`, {
        ...options, headers, credentials: 'same-origin', cache: 'no-store', redirect: 'error',
      });
    },
  };

  async function settingsRequest(path, options = {}) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const response = await backend.request(path, { ...options, signal: controller.signal });
      if (response.status === 409 && path === 'kv/import') return { conflict: true };
      if (!response.ok) throw new Error(`Settings request failed (${response.status})`);
      if (response.status === 204) return null;
      if (!/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? '')) {
        throw new Error('Settings response is not JSON');
      }
      return await response.json();
    } finally {
      clearTimeout(timeout);
    }
  }

  function storageValues(values) {
    if (!values || typeof values !== 'object' || Array.isArray(values)) throw new Error('Invalid settings');
    const result = Object.create(null);
    for (const [key, value] of Object.entries(values)) {
      if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(key)) throw new Error('Invalid settings key');
      result[key] = typeof value === 'string' ? value : JSON.stringify(value);
    }
    return JSON.stringify(result);
  }

  backend.settings = {
    async read() { return storageValues(await settingsRequest('kv')); },
    async importIfEmpty(json) {
      const { caps } = await backend.ready;
      if (caps?.kvImport !== true) throw new Error('Atomic settings import is unavailable');
      const response = await settingsRequest('kv/import', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: json,
      });
      return response?.conflict ? this.read() : json;
    },
    async write(key, value) {
      if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(key)) throw new Error('Invalid settings key');
      await settingsRequest(`kv/${key}`, value === null ? { method: 'DELETE' } : {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value),
      });
    },
  };

  backend.showSignInError = () => {
    showStartupError('Your saved Home Assistant sign-in could not be loaded. Reload to try again.');
    const button = document.createElement('button');
    button.textContent = 'Use a different Home Assistant';
    button.addEventListener('click', () => window.terrazzoOAuth.useDifferentSignIn());
    document.querySelector('#app .loading')?.append(button);
  };
  // The .wasmo build marks its page, so only a Wasmo install says it fell back to this browser.
  backend.ready.then(({ reason }) => {
    if (!reason || typeof document === 'undefined') return;
    if (document.querySelector('meta[name="terrazzo-host"]')?.content !== 'wasmo') return;
    const text = reason === 'not-enabled'
      ? 'This Wasmo isn\u2019t saving Terrazzo\u2019s settings or sign-in. Install the app under the name your Wasmo enables for it (usually \u201cterrazzo\u201d). Until then, they\u2019re saved in this browser only.'
      : 'Your Wasmo didn\u2019t answer, so settings and sign-in are saved in this browser only for now. Reload to try again.';
    showNotice(text);
  });

  function showNotice(text) {
    const notice = document.createElement('div');
    notice.setAttribute('role', 'status');
    notice.style.cssText = 'position:fixed;inset:0 0 auto 0;z-index:2147483647;display:flex;gap:12px;align-items:center;'
      + 'padding:8px 12px;font:14px/1.4 system-ui,sans-serif;background:#fff4d6;color:#3d2e00;box-shadow:0 1px 4px #0003';
    const message = document.createElement('span');
    message.style.flex = '1';
    message.textContent = text;
    const close = document.createElement('button');
    close.textContent = 'Dismiss';
    close.addEventListener('click', () => notice.remove());
    notice.append(message, close);
    (document.body ?? document.documentElement).append(notice);
  }

  backend.showStorageError = () => showStartupError('Settings on your Wasmo could not be loaded. Reload to try again.');

  function showStartupError(text) {
    const app = document.getElementById('app');
    if (!app) return;
    const message = document.createElement('p');
    message.textContent = text;
    const retry = document.createElement('button');
    retry.textContent = 'Reload';
    retry.addEventListener('click', () => location.reload());
    const container = document.createElement('div');
    container.className = 'loading';
    container.append(message, retry);
    app.replaceChildren(container);
  }
})();
