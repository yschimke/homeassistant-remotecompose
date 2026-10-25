// Capability detection is per page. Static hosts, the HA panel and demo keep browser storage.
(() => {
  const local = () => ({ kind: 'local', caps: null });

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
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3000);
    try {
      const response = await fetch('/api/wasmo/v1/capabilities', {
        credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal,
      });
      if (response.status !== 200 || !/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? '')) {
        return local();
      }
      const caps = await response.json();
      return caps?.api === 1 ? { kind: 'wasmo', caps } : local();
    } catch {
      return local();
    } finally {
      clearTimeout(timeout);
    }
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
