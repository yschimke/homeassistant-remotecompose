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
if (process.env.STUDIO_RENDER_SMOKE_REQUIRED && !process.env.STUDIO_OPERATOR_TOKEN) {
  throw new Error('Rendered preview checks require STUDIO_OPERATOR_TOKEN');
}

async function renderedPixels(page) {
  const editor = new URL(page.url());
  const id = editor.pathname.split('/').at(-1);
  const response = await page.request.get(`${editor.origin}/api/ui-builder/v1/designs/${id}/export.png?token=${encodeURIComponent(process.env.STUDIO_OPERATOR_TOKEN)}`);
  assert.equal(response.status(), 200, 'The draft must export successfully');
  assert.match(response.headers()['content-type'], /^image\/png/);
  const pixels = await page.evaluate(async (encoded) => {
    const bytes = Uint8Array.from(atob(encoded), c => c.charCodeAt(0));
    const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
    if (bitmap.width > 1800 || bitmap.height > 1800) throw new Error('Preview exceeds test image size limit');
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d');
    context.drawImage(bitmap, 0, 0);
    const data = Array.from(context.getImageData(0, 0, canvas.width, canvas.height).data);
    bitmap.close();
    return data;
  }, (await response.body()).toString('base64'));
  return Buffer.from(pixels);
}

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
      // Opt in when testing a matching staged/published catalog generation. CI's shared catalog
      // may still carry the previous main policy while this source change is under review.
      if (process.env.STUDIO_RENDER_SMOKE_REQUIRED) {
        const before = await renderedPixels(page);
        const seed = await page.request.post(`${base}/api/studio/seed`, { data: {
          card: { type: 'entities', title: 'Browser smoke', entities: ['light.smoke'] },
          states: { 'light.smoke': { state: 'off', attributes: { friendly_name: 'Test light' } } },
        } });
        assert.equal(seed.status(), 200);
        const handoff = await page.request.post(`${base}/api/studio/handoffs`, {
          data: await seed.text(), headers: { 'Content-Type': 'application/json' },
        });
        assert.equal(handoff.status(), 200);
        await page.goto(`${base}${(await handoff.json()).path}`);
        await Promise.all([
          page.waitForURL(/\/ui-builder\/card-/, { waitUntil: 'domcontentloaded', timeout: 60_000 }),
          page.getByRole('button', { name: 'Open this draft in UI Builder' }).click(),
        ]);
        const after = await renderedPixels(page);
        assert.equal(before.length, after.length, 'Only the state changed, not the frame');
        assert.ok(!before.equals(after), 'Updated state must change rendered pixels; blank/error previews fail this check');
      }
    }
  } finally {
    await browser?.close();
    await new Promise((resolve) => server.close(resolve));
  }
});
