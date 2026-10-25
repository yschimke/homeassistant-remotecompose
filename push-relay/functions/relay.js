// Pure request handling for the haPush relay, kept free of Firebase so it is unit tested
// (test/relay.test.js) without credentials. index.js wires it to FCM.
//
// Home Assistant's mobile_app POSTs, for a registration whose app_data has push_url (this
// function) and push_token (the app's FCM token), while the app holds no websocket:
//   { message, title?, data?, push_token, registration_info: { app_id, app_version,
//     webhook_id, os_version? } }
// and treats any 2xx as delivered; otherwise it logs `message` (or `errorMessage`).
'use strict';

/** Package names this relay delivers for: the Terrazzo Android app. */
const APP_IDS = new Set(['ee.schimke.harc']);

/** FCM caps a message's data payload at 4 KB; leave room for its own keys. */
const MAX_DATA_BYTES = 3800;

/** Notifications per token per window, per function instance (best effort, like HA's own). */
const RATE_LIMIT = 300;
const RATE_WINDOW_MS = 24 * 60 * 60 * 1000;

class RelayError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/**
 * Checks HA's request [body] and returns the FCM message for it: a high-priority data message,
 * each value a string as FCM requires — `message`, `title`, `data` (HA's data as JSON) and
 * `webhook_id`, which tells the app which server sent it. Throws a RelayError otherwise.
 */
function buildMessage(body) {
  if (!body || typeof body !== 'object') throw new RelayError(400, 'Expected a JSON body.');
  const token = body.push_token;
  if (typeof token !== 'string' || token.length < 20 || token.length > 4096) {
    throw new RelayError(400, 'Missing or invalid push_token.');
  }
  const info = body.registration_info;
  if (!info || typeof info !== 'object' || !APP_IDS.has(info.app_id)) {
    throw new RelayError(403, 'This relay only delivers to the Terrazzo app.');
  }
  if (typeof info.webhook_id !== 'string' || info.webhook_id.length === 0) {
    throw new RelayError(400, 'Missing registration_info.webhook_id.');
  }
  if (typeof body.message !== 'string') throw new RelayError(400, 'Missing message.');
  const data = { webhook_id: info.webhook_id, message: body.message };
  if (typeof body.title === 'string') data.title = body.title;
  if (body.data !== undefined && body.data !== null) {
    if (typeof body.data !== 'object' || Array.isArray(body.data)) {
      throw new RelayError(400, 'data must be an object.');
    }
    data.data = JSON.stringify(body.data);
  }
  const size = Object.entries(data).reduce(
    (n, [k, v]) => n + Buffer.byteLength(k) + Buffer.byteLength(v),
    0,
  );
  if (size > MAX_DATA_BYTES) {
    throw new RelayError(413, 'Notification too large to relay (FCM allows 4 KB).');
  }
  return { token, data, android: { priority: 'high' } };
}

/** A per-token counter over a fixed window. */
function rateLimiter(limit = RATE_LIMIT, windowMs = RATE_WINDOW_MS, now = () => Date.now()) {
  const counts = new Map();
  return {
    /** Counts one send for [token]; false once it is over the limit for this window. */
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
 * Handles one relay request: [send] delivers an FCM message and resolves to its id. Returns the
 * HTTP status and JSON body for HA.
 */
async function handle(method, body, send, limiter) {
  if (method !== 'POST') return { status: 405, body: { message: 'POST only.' } };
  let message;
  try {
    message = buildMessage(body);
  } catch (e) {
    if (e instanceof RelayError) return { status: e.status, body: { message: e.message } };
    throw e;
  }
  if (!limiter.allow(message.token)) {
    return { status: 429, body: { message: 'Too many notifications for this device today.' } };
  }
  try {
    const messageId = await send(message);
    return { status: 201, body: { messageId } };
  } catch (e) {
    if (STALE_TOKEN_CODES.has(e && e.code)) {
      return {
        status: 410,
        body: { message: 'The Terrazzo app has a new push token; open it to send HA the new one.' },
      };
    }
    return { status: 502, body: { message: 'FCM refused the notification.' } };
  }
}

module.exports = { buildMessage, rateLimiter, handle, RelayError, APP_IDS, MAX_DATA_BYTES };
