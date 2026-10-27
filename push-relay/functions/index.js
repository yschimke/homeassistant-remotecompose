// The Terrazzo push relay: wakes the Terrazzo app for the notifications its Home Assistant holds.
// See docs/architecture/notifications.md and relay.js.
'use strict';

const { onRequest } = require('firebase-functions/v2/https');
const { logger } = require('firebase-functions');
const { initializeApp } = require('firebase-admin/app');
const { getMessaging } = require('firebase-admin/messaging');
const { handle, rateLimiter } = require('./relay');

// The function's own service account: no key leaves Google.
initializeApp();
const limiter = rateLimiter();

exports.haPush = onRequest(
  { region: 'us-central1', maxInstances: 10, memory: '256MiB', timeoutSeconds: 10 },
  async (req, res) => {
    const result = await handle(req.method, req.body, (m) => getMessaging().send(m), limiter);
    // Refused wakes in the function's logs, by status and FCM code; never a token or webhook id.
    if (result.status >= 500 || result.status === 410) {
      logger.warn('Wake refused', { status: result.status, code: result.body.code });
    }
    res.status(result.status).json(result.body);
  },
);
