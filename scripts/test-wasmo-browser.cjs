#!/usr/bin/env node
// Real Wasm UI against the loopback-only Wasmo settings fixture. No live HA or Wasmo account.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const readline = require('node:readline');
const puppeteer = require(process.env.PUPPETEER_MODULE || './browser-tests/node_modules/puppeteer-core');
const distribution = path.resolve(process.argv[2]);
const output = path.resolve('_wasm-test');
const sharing = 'terrazzo.usage.sharing';
const ledger = 'terrazzo.usage.ledger';

async function startFixture(staticMode = false) {
  const args = [path.join(__dirname, 'wasmo-sim.py'), distribution, '--port', '0'];
  if (staticMode) args.push('--static');
  if (process.env.WASMO_BEFORE_DIST) args.push('--before', path.resolve(process.env.WASMO_BEFORE_DIST));
  const child = spawn('python3', args, { stdio: ['ignore', 'pipe', 'inherit'] });
  const lines = readline.createInterface({ input: child.stdout });
  const base = await new Promise((resolve, reject) => {
    child.once('exit', code => reject(new Error(`Fixture exited (${code})`)));
    lines.on('line', line => {
      const match = line.match(/http:\/\/127\.0\.0\.1:\d+/);
      if (match) resolve(match[0]);
    });
  });
  return { base, process: child };
}

async function ownerContext(browser) {
  const context = await browser.createBrowserContext();
  await context.setCookie({ name: 'wasmo-fixture-owner', value: '1', domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Strict' });
  return context;
}

async function seed(page, value = 'Enabled') {
  await page.evaluateOnNewDocument((sharing, ledger, value) => {
    if (!localStorage.getItem('fixture-seeded')) {
      localStorage.setItem('fixture-seeded', '1');
      localStorage.setItem(sharing, value);
      localStorage.setItem(ledger, '{"days":{}}');
      localStorage.setItem('unrelated', 'keep');
    }
  }, sharing, ledger, value);
}

async function settings(page, url) {
  await page.setViewport({ width: 1200, height: 800 });
  await page.goto(url);
  await page.waitForSelector('pierce/canvas', { timeout: 20000 });
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await until(async () => {
    if (await page.$('pierce/[role="switch"]')) return true;
    // Cold Wasm composition may expose semantics before the canvas receives pointer handlers.
    await page.mouse.click(210, 772);
    return false;
  }, 'Settings screen did not open');
}

async function text(page) {
  return page.$$eval('pierce/[role]', elements => elements.map(element => element.textContent).join('\n'));
}
async function until(check, message) {
  const end = Date.now() + 15000;
  while (Date.now() < end) {
    if (await check()) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error(message);
}
async function label(page, value) {
  await until(async () => (await text(page)).includes(value), `Missing status: ${value}`);
}
async function toggle(page) {
  const element = await page.$('pierce/[role="switch"]');
  const bounds = await element.boundingBox();
  assert.ok(bounds, 'Sharing switch has no bounds');
  await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
}
async function checked(page) {
  return page.$eval('pierce/[role="switch"]', element => element.getAttribute('aria-checked') === 'true');
}
async function values(page) {
  return page.evaluate(async () => (await fetch('/api/wasmo/v1/kv', { cache: 'no-store' })).json());
}
async function failure(page, status) {
  await page.evaluate(status => fetch('/__fixture__/failure', { method: 'PUT', body: String(status) }), status);
}
async function screenshot(page, name) {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  await page.screenshot({ path: path.join(output, name) });
}

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const fixtures = [];
  let browser;
  try {
    const cloud = await startFixture(); fixtures.push(cloud);
    const local = await startFixture(true); fixtures.push(local);
    browser = await puppeteer.launch({
      executablePath: process.env.CHROMIUM_PATH,
      headless: true,
      args: ['--disk-cache-size=1', '--media-cache-size=1', '--disable-gpu-shader-disk-cache', '--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    });
    const anonymous = await browser.createBrowserContext();
    const anonPage = await anonymous.newPage();
    assert.equal((await anonPage.goto(`${cloud.base}/api/wasmo/v1/kv`)).status(), 404);
    assert.equal((await anonPage.goto(`${cloud.base}/terrazzo-backend.js`)).status(), 404);
    await anonymous.close();

    console.log('Checking owner-only routing');
    if (process.env.WASMO_BEFORE_DIST) {
      const before = await ownerContext(browser);
      const page = await before.newPage();
      await settings(page, `${cloud.base}/before/`);
      await screenshot(page, 'wasmo-settings-before.png');
      await before.close();
    }
    console.log('Checking cloud migration and settings UI');
    const first = await ownerContext(browser);
    const page = await first.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await seed(page);
    await settings(page, cloud.base);
    await label(page, 'Usage settings stored on your Wasmo.');
    assert.equal(await checked(page), true);
    assert.equal((await values(page))[sharing], 'Enabled');
    assert.equal((await values(page))[ledger], '{"days":{}}');
    assert.equal(await page.evaluate(key => localStorage.getItem(key), sharing), null);
    assert.equal(await page.evaluate(() => localStorage.getItem('unrelated')), 'keep');
    assert.equal(await page.evaluate(() => !!localStorage.getItem('terrazzo-oauth')), true);
    assert.equal(Object.hasOwn(await values(page), 'terrazzo-oauth'), false);
    await screenshot(page, 'wasmo-settings-after.png');

    await toggle(page);
    await until(async () => (await values(page))[sharing] === 'Disabled', 'Sharing choice did not persist');
    await toggle(page);
    await until(async () => (await values(page))[sharing] === 'Enabled', 'Latest sharing choice did not persist');
    console.log('Checking second device');
    const second = await ownerContext(browser);
    const device = await second.newPage();
    await settings(device, cloud.base);
    await label(device, 'Usage settings stored on your Wasmo.');
    assert.equal(await checked(device), true);
    assert.equal(await device.evaluate(key => localStorage.getItem(key), sharing), null);
    await second.close();

    console.log('Checking failed-write recovery');
    await failure(page, 503);
    await toggle(page);
    await label(page, 'Could not save usage settings.');
    assert.equal(await checked(page), false);
    await screenshot(page, 'wasmo-settings-unsaved.png');
    await failure(page, 0);
    assert.equal((await values(page))[sharing], 'Enabled');
    await toggle(page);
    await label(page, 'Usage settings stored on your Wasmo.');

    await failure(page, 503);
    console.log('Checking failed startup');
    const failing = await ownerContext(browser);
    const retry = await failing.newPage();
    await seed(retry, 'Disabled');
    await retry.goto(cloud.base);
    await retry.waitForSelector('button');
    assert.match(await retry.$eval('body', element => element.innerText), /Settings on your Wasmo could not be loaded/);
    assert.equal(await retry.evaluate(key => localStorage.getItem(key), sharing), 'Disabled');
    assert.equal(await retry.$('pierce/canvas'), null);
    await screenshot(retry, 'wasmo-settings-retry.png');
    await failure(page, 0);
    await Promise.all([retry.waitForNavigation(), retry.click('button')]);
    await settings(retry, cloud.base);
    await label(retry, 'Usage settings stored on your Wasmo.');
    assert.equal(await checked(retry), true);
    await failing.close();

    console.log('Checking static browser storage');
    const staticContext = await browser.createBrowserContext();
    const staticPage = await staticContext.newPage();
    await seed(staticPage);
    await settings(staticPage, local.base);
    await label(staticPage, 'Usage settings stored in this browser.');
    assert.equal(await checked(staticPage), true);
    await toggle(staticPage);
    assert.equal(await staticPage.evaluate(key => localStorage.getItem(key), sharing), 'Disabled');
    await settings(staticPage, local.base);
    assert.equal(await checked(staticPage), false);
    await screenshot(staticPage, 'wasmo-settings-static.png');
    assert.deepEqual(errors, []);
    console.log('PASS: owner-only fixture, atomic migration, second device, write-through, retry, fail-closed startup, static persistence');
  } finally {
    if (browser) await browser.close();
    for (const fixture of fixtures) fixture.process.kill();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
