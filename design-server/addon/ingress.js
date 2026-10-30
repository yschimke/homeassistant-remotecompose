// Loaded first on every page the add-on serves (nginx.conf puts it at the top of <head>). The
// servers behind it, and UI Builder's editor, call root-relative URLs (`/api/ui-builder/v1/...`);
// under Home Assistant ingress those must carry the panel's prefix, which this script's own URL
// names (`<prefix>/__ingress.js`). Every same-origin URL outside the prefix gets it.
(() => {
  const script = document.currentScript;
  if (!script) return;
  const prefix = new URL(script.src).pathname.replace(/\/__ingress\.js$/, '');
  if (!prefix) return;

  const prefixed = (value, base = location.href) => {
    let url;
    try {
      url = new URL(String(value), base);
    } catch {
      return value;
    }
    const sameHost = url.host === location.host;
    const web = url.protocol === 'http:' || url.protocol === 'https:';
    const socket = url.protocol === 'ws:' || url.protocol === 'wss:';
    if (!sameHost || !(web || socket)) return value;
    if (url.pathname === prefix || url.pathname.startsWith(prefix + '/')) return value;
    url.pathname = prefix + url.pathname;
    return url.href;
  };

  const fetch = globalThis.fetch;
  globalThis.fetch = function (input, init) {
    if (input instanceof Request) {
      const url = prefixed(input.url);
      if (url !== input.url) input = new Request(url, input);
    } else {
      input = prefixed(input);
    }
    return fetch.call(this, input, init);
  };

  const open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url, ...rest) {
    return open.call(this, method, prefixed(url), ...rest);
  };

  for (const name of ['WebSocket', 'EventSource', 'Worker', 'SharedWorker']) {
    const Native = globalThis[name];
    if (!Native) continue;
    const wrapped = class extends Native {
      constructor(url, ...rest) {
        super(prefixed(url), ...rest);
      }
    };
    Object.defineProperty(wrapped, 'name', { value: name });
    globalThis[name] = wrapped;
  }

  for (const name of ['pushState', 'replaceState']) {
    const native = history[name];
    history[name] = function (state, title, url) {
      return url == null
        ? native.call(this, state, title)
        : native.call(this, state, title, prefixed(url));
    };
  }

  const windowOpen = window.open;
  window.open = function (url, ...rest) {
    return windowOpen.call(this, url == null ? url : prefixed(url), ...rest);
  };

  // URLs set on elements: the renderer runtime's iframe, scripts and images made at run time.
  const urlProperties = [
    [HTMLIFrameElement, 'src'],
    [HTMLScriptElement, 'src'],
    [HTMLImageElement, 'src'],
    [HTMLSourceElement, 'src'],
    [HTMLLinkElement, 'href'],
    [HTMLAnchorElement, 'href'],
    [HTMLFormElement, 'action'],
  ];
  for (const [type, name] of urlProperties) {
    const descriptor = Object.getOwnPropertyDescriptor(type.prototype, name);
    if (!descriptor?.set) continue;
    Object.defineProperty(type.prototype, name, {
      ...descriptor,
      set(value) {
        descriptor.set.call(this, prefixed(value));
      },
    });
  }
  const setAttribute = Element.prototype.setAttribute;
  Element.prototype.setAttribute = function (name, value) {
    const lower = String(name).toLowerCase();
    if (lower === 'src' || lower === 'href' || lower === 'action') value = prefixed(value);
    return setAttribute.call(this, name, value);
  };

  // Forms built or retargeted after load, whichever way they are sent.
  const fixForm = (form) => {
    const action = form.getAttribute('action');
    if (action != null) setAttribute.call(form, 'action', prefixed(action));
  };
  document.addEventListener('submit', (event) => fixForm(event.target), true);
  for (const name of ['submit', 'requestSubmit']) {
    const native = HTMLFormElement.prototype[name];
    HTMLFormElement.prototype[name] = function (...args) {
      fixForm(this);
      return native.apply(this, args);
    };
  }

  // Navigations nothing above sees: `location.assign('/ui-builder/designs?...')` and the like. The
  // Navigation API lets this page stop one and go to the prefixed address instead.
  globalThis.navigation?.addEventListener('navigate', (event) => {
    const url = event.destination.url;
    const fixed = prefixed(url);
    if (fixed === url || !event.cancelable || event.formData) return;
    event.preventDefault();
    location.assign(fixed);
  });

  // A service worker here would be registered on Home Assistant's own origin, where one scoped to /
  // would take over Home Assistant's frontend. The pages work without one.
  if (navigator.serviceWorker) {
    navigator.serviceWorker.register = () =>
      Promise.reject(new Error('service workers are off under Home Assistant ingress'));
  }
})();
