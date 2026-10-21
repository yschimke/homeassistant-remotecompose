// Home Assistant sign-in for the standalone web app: OAuth with client_id = this page's origin,
// the same flow as embed/ha-card.js. Home Assistant accepts any https client_id whose
// redirect_uri is on the same host, so a page served from its own origin (GitHub Pages, a Wasmo
// app) needs no registration. The refresh token is kept in this origin's localStorage until sign-out.
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
      writeTokens(tokens);
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
      try {
        await completeSignIn();
      } catch (e) {
        console.warn('Home Assistant sign-in failed', e);
      }
      return tokens?.refresh_token ? tokens.hassUrl : '';
    },

    /** Send the viewer to Home Assistant's sign-in page; it comes back here with a code. */
    signIn(hassUrl) {
      const origin = new URL(hassUrl).origin;
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

    signOut() {
      tokens = null;
      writeTokens(null);
    },

    /** A current access token, refreshed when it is about to expire. */
    async accessToken() {
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
