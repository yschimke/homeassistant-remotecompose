// Pure request handling for the haPush relay, kept free of Firebase so it is unit tested
// (test/relay.test.js) without credentials. index.js wires it to FCM.
//
// The relay only wakes the Terrazzo app: it never sees a notification. Home Assistant's
// mobile_app hands each message to the Terrazzo integration, which holds it and POSTs here
//   { token, webhook_id, app_id }
// (the app's FCM token, which registration has something waiting, and the app). The relay sends
// FCM a data message with just { type: "sync", webhook_id }; the app wakes, connects to Home
// Assistant and takes the held messages over its own authenticated websocket.
//
// The integration also wakes the app when an entity its widgets read changes, with
//   { token, webhook_id, app_id, type: "state", urgent }
// and the relay sends { type: "state", webhook_id }: the app reads the states itself. A routine
// change isn't urgent and goes at normal priority, so FCM may hold it while the phone dozes; a
// garage door starting to open goes at high priority.
'use strict';

/** Package names this relay wakes: the Terrazzo Android app. */
const APP_IDS = new Set(['ee.schimke.harc']);

/** Wakes per token per window, per function instance (best effort). */
const RATE_LIMIT = 500;
const RATE_WINDOW_MS = 24 * 60 * 60 * 1000;

class RelayError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/** The wakes the relay sends: held messages to take, or widget states to read. */
const WAKE_TYPES = new Set(['sync', 'state']);

/**
 * Checks the integration's request [body] and returns the FCM wake for it: a data message carrying
 * only `type` and `webhook_id`, collapsing with earlier wakes of that type still undelivered. A
 * `sync` wake is always high priority; a `state` wake only when it is `urgent`. Throws a RelayError
 * otherwise.
 */
function buildWake(body) {
  if (!body || typeof body !== 'object') throw new RelayError(400, 'Expected a JSON body.');
  const token = body.token;
  if (typeof token !== 'string' || token.length < 20 || token.length > 4096) {
    throw new RelayError(400, 'Missing or invalid token.');
  }
  if (!APP_IDS.has(body.app_id)) {
    throw new RelayError(403, 'This relay only wakes the Terrazzo app.');
  }
  const webhookId = body.webhook_id;
  if (typeof webhookId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(webhookId)) {
    throw new RelayError(400, 'Missing or invalid webhook_id.');
  }
  const type = body.type === undefined ? 'sync' : body.type;
  if (!WAKE_TYPES.has(type)) throw new RelayError(400, 'Unknown wake type.');
  const high = type === 'sync' || body.urgent === true;
  return {
    token,
    data: { type, webhook_id: webhookId },
    android: { priority: high ? 'high' : 'normal', collapseKey: `${type}-${webhookId}` },
  };
}

/** A per-token counter over a fixed window. */
function rateLimiter(limit = RATE_LIMIT, windowMs = RATE_WINDOW_MS, now = () => Date.now()) {
  const counts = new Map();
  return {
    /** Counts one wake for [token]; false once it is over the limit for this window. */
    allow(token) {
      const t = now();
      let entry = counts.get(token);
      if (!entry || t >= entry.resetsAt) {
        entry = { count: 0, resetsAt: t + windowMs };
        counts.set(token, entry);
      }
      if (entry.count >= limit) return false;
      entry.count++;
      // Forget expired windows now and then, so the map stays small.
      if (counts.size > 10000) {
        for (const [k, v] of counts) if (t >= v.resetsAt) counts.delete(k);
      }
      return true;
    },
  };
}

/** FCM errors that mean the token is gone: the app must register a new one. */
const STALE_TOKEN_CODES = new Set([
  'messaging/registration-token-not-registered',
  'messaging/invalid-registration-token',
]);

/**
 * Handles one wake request: [send] delivers an FCM message and resolves to its id. Returns the
 * HTTP status and JSON body for the integration.
 */
async function handle(method, body, send, limiter) {
  if (method !== 'POST') return { status: 405, body: { message: 'POST only.' } };
  let wake;
  try {
    wake = buildWake(body);
  } catch (e) {
    if (e instanceof RelayError) return { status: e.status, body: { message: e.message } };
    throw e;
  }
  if (!limiter.allow(wake.token)) {
    return { status: 429, body: { message: 'Too many wakes for this device today.' } };
  }
  try {
    const messageId = await send(wake);
    return { status: 201, body: { messageId } };
  } catch (e) {
    if (STALE_TOKEN_CODES.has(e && e.code)) {
      return {
        status: 410,
        body: { message: 'The Terrazzo app has a new push token; open it to register again.' },
      };
    }
    // FCM's code (no token or content in it) says which: a bad request, credentials, quota.
    return { status: 502, body: { message: 'FCM refused the wake.', code: (e && e.code) || 'unknown' } };
  }
}

module.exports = { buildWake, rateLimiter, handle, RelayError, APP_IDS };
