import { test } from 'node:test';
import assert from 'node:assert/strict';

const base = process.env.STUDIO_SERVER_URL;
let chromium;
try {
  ({ chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? '../../embed/node_modules/playwright/index.mjs'));
} catch {
  // The design-server CI job installs Playwright and requires these browser checks.
}
const skip = !base ? 'STUDIO_SERVER_URL is unset' : !chromium ? 'playwright is unavailable' : false;
if (skip && process.env.STUDIO_SMOKE_REQUIRED) throw new Error(skip);

test('console light explains the empty result and recovers to rendered dark designs', { skip }, async () => {
  const browser = await chromium.launch({
    ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}),
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const response = await page.goto(`${base}/designs?type=all&style=console&mode=light&q=&preview=example`);
    assert.equal(response.status(), 200);
    assert.equal(await page.locator('.count').textContent(), '0 shown');
    assert.equal(await page.locator('.mode-hint').count(), 1, 'Explain the unsupported theme/mode combination');
    assert.match(await page.locator('.mode-hint').textContent(), /console.*has no light designs.*Available modes: dark/);
    assert.equal(await page.locator('select[name=mode]').inputValue(), 'light');
    await Promise.all([
      page.waitForURL('**/designs?type=all&style=console&mode=dark'),
      page.getByRole('link', { name: 'dark', exact: true }).click(),
    ]);
    assert.equal(await page.locator('select[name=mode]').inputValue(), 'dark');
    assert.match(await page.locator('.count').textContent(), /^[1-9]\d* shown$/);
    const preview = page.locator('.grid img').first();
    await preview.waitFor();
    await preview.evaluate(image => image.decode());
    assert.ok(await preview.evaluate(image => image.naturalWidth > 0));
    const asset = await page.request.get(new URL(await preview.getAttribute('src'), page.url()).href);
    assert.equal(asset.status(), 200);
    assert.match(asset.headers()['content-type'], /^image\/png/);
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});
