'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildMessage, rateLimiter, handle } = require('../relay');

const TOKEN = 'f'.repeat(40);
const request = (extra = {}) => ({
  message: 'Washer done',
  title: 'Laundry',
  data: { tag: 'washer', actions: [{ action: 'ACK', title: 'OK' }] },
  push_token: TOKEN,
  registration_info: { app_id: 'ee.schimke.harc', app_version: '1.0', webhook_id: 'hook1' },
  ...extra,
});

test('HA\'s request becomes a high-priority data message of strings', () => {
  const m = buildMessage(request());
  assert.equal(m.token, TOKEN);
  assert.deepEqual(m.android, { priority: 'high' });
  assert.deepEqual(m.data, {
    webhook_id: 'hook1',
    message: 'Washer done',
    title: 'Laundry',
    data: JSON.stringify({ tag: 'washer', actions: [{ action: 'ACK', title: 'OK' }] }),
  });
  for (const v of Object.values(m.data)) assert.equal(typeof v, 'string');
});

test('a message without title or data carries just the message', () => {
  const m = buildMessage(request({ title: undefined, data: undefined }));
  assert.deepEqual(m.data, { webhook_id: 'hook1', message: 'Washer done' });
});

test('other apps, bad tokens and oversized messages are refused', async () => {
  const send = async () => 'id';
  const status = async (body) => (await handle('POST', body, send, rateLimiter())).status;
  assert.equal(
    await status(request({ registration_info: { app_id: 'io.homeassistant.companion.android', webhook_id: 'x' } })),
    403,
  );
  assert.equal(await status(request({ push_token: 'short' })), 400);
  assert.equal(await status(request({ data: [1, 2] })), 400);
  assert.equal(await status(request({ message: 'x'.repeat(5000) })), 413);
  assert.equal(await status(null), 400);
  assert.equal((await handle('GET', request(), send, rateLimiter())).status, 405);
});

test('a delivered message answers 201 with FCM\'s id', async () => {
  let sent;
  const result = await handle('POST', request(), async (m) => { sent = m; return 'projects/p/messages/1'; }, rateLimiter());
  assert.equal(result.status, 201);
  assert.deepEqual(result.body, { messageId: 'projects/p/messages/1' });
  assert.equal(sent.token, TOKEN);
});

test('a stale token tells HA to have the app re-register; other FCM errors are a 502', async () => {
  const fail = (code) => async () => { const e = new Error(code); e.code = code; throw e; };
  const stale = await handle('POST', request(), fail('messaging/registration-token-not-registered'), rateLimiter());
  assert.equal(stale.status, 410);
  assert.match(stale.body.message, /new push token/);
  assert.equal((await handle('POST', request(), fail('messaging/internal-error'), rateLimiter())).status, 502);
});

test('each token is limited per window, and the window resets', async () => {
  let now = 0;
  const limiter = rateLimiter(2, 1000, () => now);
  const send = async () => 'id';
  assert.equal((await handle('POST', request(), send, limiter)).status, 201);
  assert.equal((await handle('POST', request(), send, limiter)).status, 201);
  assert.equal((await handle('POST', request(), send, limiter)).status, 429);
  // Another device is unaffected.
  assert.equal((await handle('POST', request({ push_token: 'e'.repeat(40) }), send, limiter)).status, 201);
  now = 1000;
  assert.equal((await handle('POST', request(), send, limiter)).status, 201);
});
