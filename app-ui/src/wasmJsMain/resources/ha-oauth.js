// Home Assistant sign-in for the standalone web app: OAuth with client_id = this page's origin,
// the same flow as embed/ha-card.js. Home Assistant accepts any https client_id whose
// redirect_uri is on the same host, so a page served from its own origin (GitHub Pages, a Wasmo
// app) needs no registration. Static hosts keep tokens in localStorage; a capable Wasmo keeps
// the refresh token encrypted server-side and returns only short-lived access tokens.
(() => {
  const CALLBACK_PARAM = 'terrazzo_auth';
  const STORAGE_KEY = 'terrazzo-oauth';
  // The server and random nonce of the sign-in this tab started. A callback must carry both, so a
  // crafted link cannot sign the viewer in to someone else's account or Home Assistant.
  const PENDING_KEY = 'terrazzo-oauth-pending';

  class AuthError extends Error {}

  function clientId() {
    return `${location.protocol}//${location.host}/`;
  }

  function redirectUri() {
    const url = new URL(location.href);
    url.hash = '';
    for (const p of [CALLBACK_PARAM, 'code', 'state']) url.searchParams.delete(p);
    url.searchParams.set(CALLBACK_PARAM, '1');
    return url.toString();
  }

  function readTokens() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }

  function writeTokens(tokens) {
    try {
      if (tokens) localStorage.setItem(STORAGE_KEY, JSON.stringify(tokens));
      else localStorage.removeItem(STORAGE_KEY);
    } catch {
      // Storage blocked: the sign-in lasts as long as the page.
    }
  }

  async function tokenRequest(hassUrl, params) {
    const response = await fetch(`${hassUrl}/auth/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(params),
    });
    if (response.status === 400 || response.status === 403) throw new AuthError(`token request refused (${response.status})`);
    if (!response.ok) throw new Error(`token request failed (${response.status})`);
    const body = await response.json();
    return {
      hassUrl,
      clientId: params.client_id,
      access_token: body.access_token,
      refresh_token: body.refresh_token ?? params.refresh_token,
      expires: Date.now() + (body.expires_in ?? 1800) * 1000,
    };
  }

  let tokens = readTokens();
  let refreshing = null;
  let cloud = false;
  let session = null;

  class CloudAuthError extends Error {
    constructor(status) { super('Saved sign-in request failed'); this.status = status; }
  }

  async function cloudRequest(path, method = 'GET', value) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await window.terrazzoBackend.request(path, {
        method, signal: controller.signal,
        ...(value === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) }),
      });
      if (!response.ok) throw new CloudAuthError(response.status);
      if (!/^application\/json(?:\s*;|$)/i.test(response.headers.get('content-type') ?? '')) throw new CloudAuthError(502);
      return await response.json();
    } finally { clearTimeout(timeout); }
  }

  function forgetLegacyTokens(expected) {
    // Do not delete another tab's newer sign-in while a request was in flight.
    try {
      if (localStorage.getItem(STORAGE_KEY) === expected) localStorage.removeItem(STORAGE_KEY);
    } catch { /* Browser storage may be blocked. Cloud mode never reads it again this page. */ }
  }

  function checkSession(value) {
    if (!value || typeof value.signedIn !== 'boolean' || typeof value.sessionId !== 'string') throw new CloudAuthError(502);
    if (value.signedIn && (typeof value.hassUrl !== 'string' || new URL(value.hassUrl).protocol !== 'https:')) throw new CloudAuthError(502);
    return value;
  }

  async function saveCloudTokens(value, importing) {
    return checkSession(await cloudRequest(importing ? 'ha/session/import' : 'ha/session', importing ? 'POST' : 'PUT', {
      hassUrl: value.hassUrl, clientId: value.clientId, refreshToken: value.refresh_token,
    }));
  }

  /** Finish a sign-in this page was redirected back from. Resolves to the server, or ''. */
  async function completeSignIn() {
    const params = new URLSearchParams(location.search);
    if (params.get(CALLBACK_PARAM) !== '1' || !params.get('code')) return false;
    try {
      const state = JSON.parse(atob(params.get('state') ?? ''));
      let pending = null;
      try {
        pending = sessionStorage.getItem(PENDING_KEY);
        sessionStorage.removeItem(PENDING_KEY);
      } catch {
        // Storage blocked: no sign-in can be completed.
      }
      const expected = pending ? JSON.parse(pending) : null;
      if (
        !expected ||
        state.hassUrl !== expected.hassUrl ||
        state.nonce !== expected.nonce ||
        state.clientId !== clientId()
      )
        return false;
      tokens = await tokenRequest(state.hassUrl, {
        grant_type: 'authorization_code',
        code: params.get('code'),
        client_id: state.clientId,
      });
      if (cloud) {
        session = await saveCloudTokens(tokens, false);
        tokens = null;
      } else writeTokens(tokens);
    } finally {
      const url = new URL(location.href);
      for (const p of [CALLBACK_PARAM, 'code', 'state']) url.searchParams.delete(p);
      history.replaceState(history.state, '', url.toString());
    }
    return true;
  }

  window.terrazzoOAuth = {
    /** The signed-in Home Assistant origin after any pending redirect completes, or ''. */
    async restore() {
      cloud = (await window.terrazzoBackend?.ready)?.caps?.haSession === true;
      if (cloud) {
        let legacy = null;
        try { legacy = localStorage.getItem(STORAGE_KEY); } catch { /* optional migration */ }
        const completed = await completeSignIn();
        session = checkSession(await cloudRequest('ha/session'));
        let skipImport = false;
        try { skipImport = sessionStorage.getItem('terrazzo-oauth-skip-import') === '1'; } catch { /* optional migration */ }
        if (!completed && !skipImport && !session.signedIn && tokens?.refresh_token) {
          try { session = await saveCloudTokens(tokens, true); }
          catch (error) {
            if (!(error instanceof CloudAuthError) || ![401, 409].includes(error.status)) throw error;
            session = checkSession(await cloudRequest('ha/session'));
          }
        }
        tokens = null;
        if (session.signedIn) {
          try { await window.terrazzoOAuth.accessToken(); }
          catch (error) {
            if (!(error instanceof CloudAuthError) || error.status !== 401) throw error;
            session = checkSession(await cloudRequest('ha/session'));
            if (session.signedIn) throw error;
          }
        }
        if (!skipImport || session.signedIn) forgetLegacyTokens(legacy);
        return session.signedIn ? session.hassUrl : '';
      }
      try { await completeSignIn(); }
      catch { console.warn('Home Assistant sign-in failed'); }
      return tokens?.refresh_token ? tokens.hassUrl : '';
    },

    /** Send the viewer to Home Assistant's sign-in page; it comes back here with a code. */
    signIn(hassUrl) {
      const address = new URL(hassUrl);
      if (cloud && address.protocol !== 'https:') {
        window.alert('Use a public HTTPS Home Assistant address.');
        return;
      }
      const origin = address.origin;
      const nonce = crypto.randomUUID();
      try {
        sessionStorage.setItem(PENDING_KEY, JSON.stringify({ hassUrl: origin, nonce }));
      } catch {
        // Storage blocked: the callback will be ignored and the connect screen shown again.
      }
      const url = new URL(`${origin}/auth/authorize`);
      url.searchParams.set('response_type', 'code');
      url.searchParams.set('client_id', clientId());
      url.searchParams.set('redirect_uri', redirectUri());
      url.searchParams.set('state', btoa(JSON.stringify({ hassUrl: origin, clientId: clientId(), nonce })));
      location.assign(url.toString());
    },

    async signOut() {
      if (cloud) {
        if (!session) throw new CloudAuthError(409);
        await cloudRequest('ha/session', 'DELETE', { sessionId: session.sessionId });
        session = null;
      }
      tokens = null;
      writeTokens(null);
    },

    useDifferentSignIn() {
      // Explicitly skip this tab's legacy import without deleting a credential we could not save.
      sessionStorage.setItem('terrazzo-oauth-skip-import', '1');
      location.reload();
    },

    sessionLabel() {
      return cloud ? 'Your Wasmo saves this Home Assistant sign-in for your devices. Signing out removes it for all devices.' : '';
    },

    /** A current access token, refreshed when it is about to expire. */
    async accessToken() {
      if (cloud) {
        if (!session?.signedIn) throw new AuthError('not signed in');
        const value = await cloudRequest('ha/token', 'POST', { sessionId: session.sessionId });
        if (value.sessionId !== session.sessionId || value.hassUrl !== session.hassUrl || typeof value.access_token !== 'string') throw new CloudAuthError(502);
        return value.access_token;
      }
      if (!tokens) throw new AuthError('not signed in');
      if (tokens.expires - Date.now() > 30_000) return tokens.access_token;
      refreshing ??= tokenRequest(tokens.hassUrl, {
        grant_type: 'refresh_token',
        refresh_token: tokens.refresh_token,
        client_id: tokens.clientId,
      }).finally(() => {
        refreshing = null;
      });
      try {
        tokens = await refreshing;
        writeTokens(tokens);
      } catch (e) {
        if (e instanceof AuthError) window.terrazzoOAuth.signOut();
        throw e;
      }
      return tokens.access_token;
    },
  };
})();
