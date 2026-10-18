// Run explicitly against isolated HA + native capture hosts; never uses a production admin cookie.
// CATALOG_SMOKE_URL, CATALOG_SOURCE_FILE and CATALOG_ADMIN_COOKIE_FILE are required.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const base = process.env.CATALOG_SMOKE_URL;
const sourceFile = process.env.CATALOG_SOURCE_FILE;
const cookieFile = process.env.CATALOG_ADMIN_COOKIE_FILE;
const evidence = process.env.CATALOG_EVIDENCE_DIR;
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? '../../embed/node_modules/playwright/index.mjs');

test('an admin imports a new UID, validates native RC, publishes without deployment', { timeout: 300_000 }, async () => {
  assert.ok(base && sourceFile && cookieFile, 'Use an isolated configured native-capture host and synthetic admin session');
  const source = JSON.parse(await readFile(sourceFile, 'utf8'));
  const browser = await chromium.launch({ headless: true });
  try {
    const anonymous = await browser.newContext({ viewport: { width: 1200, height: 900 } });
    assert.equal((await anonymous.request.post(`${base}/api/designs/catalog/publish`, { data: {} })).status(), 403);
    const initial = await (await anonymous.request.get(`${base}/designs/v1/manifest.json`)).json();
    const contract = initial.designs.find(entry => entry.key.cardType === 'tile');
    assert.ok(contract);
    const style = `website-proof-${Date.now()}`;
    const key = { ...contract.key, theme: `style:${style}:light` };
    const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
    await context.addCookies(JSON.parse(await readFile(cookieFile, 'utf8')));
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${base}/studio/catalog`);
    await page.locator('#file').setInputFiles(sourceFile);
    await page.waitForFunction(() => document.getElementById('source').value.length > 100);
    assert.deepEqual(JSON.parse(await page.locator('#source').inputValue()), source);
    await page.locator('#id').fill(style);
    await page.locator('#contract').fill(process.env.CATALOG_CONTRACT_ID ?? 'tile-contract');
    await page.locator('#key').fill(JSON.stringify(key));
    await page.locator('#validate').click();
    await page.waitForFunction(() => {
      const status = document.getElementById('status').textContent;
      return status.startsWith('Validated with') || (status && !status.includes('compiling'));
    }, null, { timeout: 180_000 });
    assert.match(await page.locator('#status').textContent(), /^Validated with/, 'Native source must validate before any publication');
    const publications = await (await anonymous.request.get(`${base}/api/designs/catalog/publications`)).json();
    assert.equal(publications.revisions.some(item => item.id === style), false, 'validation must not publish');
    const canvas = page.locator('#preview');
    await canvas.scrollIntoViewIfNeeded();
    const before = createHash('sha256').update(await canvas.screenshot()).digest('hex');
    await page.locator('#bindings').fill(JSON.stringify({ 'light.s0.name': 'Website light', 'light.s0.state': 'On', 'light.s0.is_on': 1 }));
    await page.locator('#push').click();
    await page.waitForTimeout(200);
    const after = createHash('sha256').update(await canvas.screenshot()).digest('hex');
    assert.notEqual(before, after, 'named live updates must change the same generated document');
    for (const width of ['160', '800', '360']) {
      await page.locator('#width').selectOption(width);
      assert.equal(await canvas.evaluate(node => node.width), Number(width) * 2);
    }
    await page.locator('#publish').click();
    await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('Published revision'), null, { timeout: 180_000 });
    assert.equal(await page.locator('#revision').inputValue(), '1');
    const discovered = await (await anonymous.request.get(`${base}/api/designs/catalog/styles`)).json();
    assert.ok(discovered.includes(style), 'another browser must discover a previously unknown style');
    const resolved = await anonymous.request.post(`${base}/api/cards/resolve`, { data: { card: { type: 'tile', entity: 'light.s0' }, cardStyle: style } });
    assert.equal(resolved.status(), 200, await resolved.text());
    const match = await resolved.json();
    const blob = await anonymous.request.get(`${base}${match.blob}`);
    assert.equal(createHash('sha256').update(await blob.body()).digest('hex'), match.sha256);
    assert.deepEqual(await (await anonymous.request.get(`${base}/api/designs/${style}/source.uid`)).json(), source);
    if (evidence) {
      await mkdir(evidence, { recursive: true });
      await canvas.scrollIntoViewIfNeeded();
      await page.screenshot({ path: `${evidence}/admin-published.png`, fullPage: false });
      await writeFile(`${evidence}/result.json`, JSON.stringify({ style, compiler: await page.locator('#status').textContent(), sourcePin: source.catalogPin, sha256: match.sha256, liveUpdatesChangedFrame: before !== after, discoveryWithoutRestart: true }, null, 2));
    }
    assert.deepEqual(errors, []);
    await anonymous.close();
    await context.close();
  } finally { await browser.close(); }
});
