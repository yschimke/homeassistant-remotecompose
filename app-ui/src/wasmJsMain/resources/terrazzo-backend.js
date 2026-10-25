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

  window.terrazzoBackend = {
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
})();
