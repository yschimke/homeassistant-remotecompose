// Exercises actual browser preflights against a running design server, from another origin.
// STUDIO_SERVER_URL=http://localhost:8080 node --test design-server/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';

const base = process.env.STUDIO_SERVER_URL;
let chromium;
try {
  ({ chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? '../../embed/node_modules/playwright/index.mjs'));
} catch {
  // Local runs may omit browser dependencies; CI makes missing prerequisites a failure.
}
const skip = !base ? 'STUDIO_SERVER_URL is unset' : !chromium ? 'playwright is unavailable' : false;
if (skip && process.env.STUDIO_SMOKE_REQUIRED) throw new Error(skip);

test('cross-origin browser can seed a card and create an anonymous handoff', { skip }, async () => {
  const server = createServer((request, response) => {
    response.setHeader('Content-Type', 'text/html');
    response.end('<!doctype html><title>Card Studio smoke</title>');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({
      ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}),
    });
    const page = await browser.newPage();
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    const result = await page.evaluate(async (base) => {
      const seed = await fetch(`${base}/api/studio/seed`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          card: { type: 'entities', title: 'Browser smoke', entities: ['light.smoke'] },
          states: { 'light.smoke': { state: 'on', attributes: { friendly_name: 'Test light' } } },
        }),
      });
      // Preserve the Kotlin JSON numeric representation, just as the app client does.
      const document = await seed.text();
      const handoff = await fetch(`${base}/api/studio/handoffs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: document,
      });
      const link = handoff.ok ? await handoff.json() : null;
      return { seed: seed.status, handoff: handoff.status, path: link?.path };
    }, base.replace(/\/$/, ''));
    assert.equal(result.seed, 200);
    assert.equal(result.handoff, 200);
    assert.match(result.path, /^\/studio\/[A-Za-z0-9_-]{32}$/);
    if (process.env.STUDIO_OPERATOR_TOKEN) {
      await page.goto(`${base}/edit?token=${encodeURIComponent(process.env.STUDIO_OPERATOR_TOKEN)}`);
      await page.goto(`${base}${result.path}`);
      await Promise.all([
        page.waitForURL(/\/ui-builder\/card-/, { waitUntil: 'domcontentloaded', timeout: 60_000 }),
        page.getByRole('button', { name: 'Open this draft in UI Builder' }).click(),
      ]);
      // The real Compose editor must boot, beyond the server accepting a design or redirecting.
      await page.waitForSelector('canvas', { timeout: 60_000 });
    }
  } finally {
    await browser?.close();
    await new Promise((resolve) => server.close(resolve));
  }
});
