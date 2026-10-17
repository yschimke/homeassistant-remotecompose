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

test('console renders the same preview in light and dark modes', { skip }, async () => {
  const browser = await chromium.launch({
    ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}),
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    const response = await page.goto(`${base}/designs?type=all&style=console&mode=light&q=&preview=example`);
    assert.equal(response.status(), 200);
    let designLink;
    let previewUrl;
    for (const mode of ['light', 'dark']) {
      if (mode === 'dark') {
        await page.locator('select[name=mode]').selectOption('dark');
        await page.getByRole('button', { name: 'Apply', exact: true }).click();
        await page.waitForURL('**/designs?type=all&style=console&mode=dark&q=&preview=example');
      }
      assert.equal(await page.locator('select[name=mode]').inputValue(), mode);
      assert.match(await page.locator('.count').textContent(), /^[1-9]\d* shown$/);
      assert.equal(await page.locator('.mode-hint').count(), 0);
      const link = await page.locator('.grid a').first().getAttribute('href');
      const preview = page.locator('.grid img').first();
      await preview.waitFor();
      await preview.evaluate(image => image.decode());
      assert.ok(await preview.evaluate(image => image.naturalWidth > 0));
      const src = await preview.getAttribute('src');
      const asset = await page.request.get(new URL(src, page.url()).href);
      assert.equal(asset.status(), 200);
      assert.match(asset.headers()['content-type'], /^image\/png/);
      if (mode === 'light') {
        designLink = link;
        previewUrl = src;
        if (process.env.DESIGN_BROWSER_SCREENSHOT) {
          await page.screenshot({ path: process.env.DESIGN_BROWSER_SCREENSHOT });
        }
      } else {
        assert.equal(link, designLink, 'Both modes link to the same design');
        assert.equal(src, previewUrl, 'Both modes reuse the same preview');
      }
    }
    assert.deepEqual(errors, []);
  } finally {
    await browser.close();
  }
});
