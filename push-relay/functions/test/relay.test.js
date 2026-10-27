'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildWake, rateLimiter, handle } = require('../relay');

const TOKEN = 'f'.repeat(40);
const request = (extra = {}) => ({ token: TOKEN, webhook_id: 'hook1', app_id: 'ee.schimke.harc', ...extra });

test('a wake carries the registration and nothing else', () => {
  const m = buildWake(request({ message: 'Washer done', title: 'Laundry', data: { tag: 'x' } }));
  assert.equal(m.token, TOKEN);
  assert.deepEqual(m.data, { type: 'sync', webhook_id: 'hook1' });
  assert.deepEqual(m.android, { priority: 'high', collapseKey: 'sync-hook1' });
});

test('a state wake names the registration, at high priority only when urgent', () => {
  const routine = buildWake(request({ type: 'state', urgent: false }));
  assert.deepEqual(routine.data, { type: 'state', webhook_id: 'hook1' });
  assert.deepEqual(routine.android, { priority: 'normal', collapseKey: 'state-hook1' });
  const urgent = buildWake(request({ type: 'state', urgent: true, entity_id: 'cover.garage' }));
  assert.deepEqual(urgent.data, { type: 'state', webhook_id: 'hook1' });
  assert.equal(urgent.android.priority, 'high');
  assert.throws(() => buildWake(request({ type: 'notification' })), /Unknown wake type/);
});

test('other apps, bad tokens and bad webhook ids are refused', async () => {
  const send = async () => 'id';
  const status = async (body) => (await handle('POST', body, send, rateLimiter())).status;
  assert.equal(await status(request({ app_id: 'io.homeassistant.companion.android' })), 403);
  assert.equal(await status(request({ token: 'short' })), 400);
  assert.equal(await status(request({ webhook_id: '../x' })), 400);
  assert.equal(await status(request({ webhook_id: undefined })), 400);
  assert.equal(await status(null), 400);
  assert.equal((await handle('GET', request(), send, rateLimiter())).status, 405);
});

test('a delivered wake answers 201 with FCM\'s id', async () => {
  let sent;
  const result = await handle('POST', request(), async (m) => { sent = m; return 'projects/p/messages/1'; }, rateLimiter());
  assert.equal(result.status, 201);
  assert.deepEqual(result.body, { messageId: 'projects/p/messages/1' });
  assert.equal(sent.token, TOKEN);
});

test('a stale token answers 410; other FCM errors are a 502', async () => {
  const fail = (code) => async () => { const e = new Error(code); e.code = code; throw e; };
  assert.equal((await handle('POST', request(), fail('messaging/registration-token-not-registered'), rateLimiter())).status, 410);
  const refused = await handle('POST', request(), fail('messaging/internal-error'), rateLimiter());
  assert.equal(refused.status, 502);
  assert.equal(refused.body.code, 'messaging/internal-error');
});

test('each token is limited per window, and the window resets', async () => {
  let now = 0;
  const limiter = rateLimiter(2, 1000, () => now);
  const send = async () => 'id';
  assert.equal((await handle('POST', request(), send, limiter)).status, 201);
  assert.equal((await handle('POST', request(), send, limiter)).status, 201);
  assert.equal((await handle('POST', request(), send, limiter)).status, 429);
  assert.equal((await handle('POST', request({ token: 'e'.repeat(40) }), send, limiter)).status, 201);
  now = 1000;
  assert.equal((await handle('POST', request(), send, limiter)).status, 201);
});
