// Picking a card's design through the design server's resolver. Run: node --test embed/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveDesign } from '../ha-card.js';

const SHA = 'a'.repeat(64);
const card = { type: 'gauge', entity: 'sensor.temp', name: 'Temperature' };

function fakeFetch(status, body, seen = []) {
  return async (url, init) => {
    seen.push({ url: String(url), init });
    return {
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
      text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
    };
  };
}

test('the resolver picks the design', async () => {
  const seen = [];
  const resolved = await resolveDesign(
    'https://ha.coo.ee/api/cards/resolve',
    card,
    { cardStyle: 'lcd', dark: true },
    fakeFetch(200, { sha256: SHA, exact: false, bindings: { 'card.range': '0 – 100' } }, seen),
  );
  // Only the design: the example's values are not this card's.
  assert.deepEqual(resolved, { sha256: SHA, exact: false });
  assert.equal(seen.length, 1);
  assert.equal(seen[0].init.method, 'POST');
  // A simple request, so a page on any origin needs no preflight.
  assert.equal(seen[0].init.headers['Content-Type'], 'text/plain');
  assert.deepEqual(JSON.parse(seen[0].init.body), { card, cardStyle: 'lcd', dark: true });
});

test('the answer is cached; a failure is not', async () => {
  const seen = [];
  const ok = fakeFetch(200, { sha256: SHA, bindings: {} }, seen);
  await resolveDesign('https://cache.test/r', card, {}, ok);
  await resolveDesign('https://cache.test/r', card, {}, ok);
  assert.equal(seen.length, 1);
  await resolveDesign('https://cache.test/r', card, { cardStyle: 'lcd' }, ok);
  assert.equal(seen.length, 2, 'another style is another question');

  const failed = [];
  const fail = fakeFetch(500, 'down', failed);
  await assert.rejects(resolveDesign('https://fail.test/r', card, {}, fail));
  await assert.rejects(resolveDesign('https://fail.test/r', card, {}, fail));
  assert.equal(failed.length, 2, 'a failure is asked again');
});

test('no design for the card is an error saying why', async () => {
  await assert.rejects(
    resolveDesign('https://x/api/cards/resolve', card, {}, fakeFetch(404, 'no library design draws this card in classic')),
    /no design for this card: no library design draws this card in classic \(404\)/,
  );
  await assert.rejects(
    resolveDesign('https://y/api/cards/resolve', card, {}, fakeFetch(200, { sha256: 'nope' })),
    /the resolver returned no design/,
  );
});
