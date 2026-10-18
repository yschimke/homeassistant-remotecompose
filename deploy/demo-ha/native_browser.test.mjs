// Real HA frontend through the native read-only boundary. All data is synthetic.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const { chromium, request } = await import(process.env.PLAYWRIGHT_MODULE ?? `${root}embed/node_modules/playwright/index.mjs`);
const base = process.env.NATIVE_DEMO_URL ?? 'http://127.0.0.1:8129';
const evidence = `${root}build/native-demo-evidence`;
mkdirSync(evidence, { recursive: true });

test('public native HA login, dashboard, live updates and server-enforced read-only access', { timeout: 180_000 }, async () => {
  const api = await request.newContext();
  let ready = false;
  for (let attempt = 0; attempt < 60; attempt++) {
    try {
      if ((await api.get(`${base}/auth/providers`)).ok()) { ready = true; break; }
    } catch {}
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  assert.equal(ready, true, 'native gateway must be reachable and serve HA auth providers before browser login');
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 }, deviceScaleFactor: 1 });
  try {
    await page.goto(`${base}/lovelace/home`);
    await page.getByLabel('Username', { exact: true }).waitFor();
    await page.screenshot({ path: `${evidence}/login.png` });
    await page.getByLabel('Username', { exact: true }).fill('demo');
    await page.getByLabel('Password', { exact: true }).fill('demo');
    await page.getByRole('button', { name: 'Log in', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('home-assistant')?.hass?.states?.['sensor.demo_temperature'], null, { timeout: 60_000 });
    await page.locator('hui-thermostat-card').waitFor({ state: 'visible' });
    await page.locator('hui-gauge-card').first().waitFor({ state: 'visible' });
    await page.screenshot({ path: `${evidence}/dashboard-before.png` });
    const initial = await page.evaluate(() => {
      const hass = document.querySelector('home-assistant').hass;
      return { state: hass.states['sensor.demo_temperature'].state, admin: hass.user.is_admin };
    });
    assert.equal(initial.admin, false);
    const rejected = await page.evaluate(async () => {
      const hass = document.querySelector('home-assistant').hass;
      const failures = [];
      for (const command of [
        { type: 'call_service', domain: 'switch', service: 'toggle', target: { entity_id: 'switch.demo_coffee' } },
        { type: 'call_service', domain: 'climate', service: 'set_temperature', service_data: { temperature: 22.5 }, target: { entity_id: 'climate.demo_thermostat' } },
        { type: 'lovelace/config/save', config: { views: [] } },
        { type: 'fire_event', event_type: 'test' },
        { type: 'auth/long_lived_access_token', client_name: 'denied', lifespan: 1 },
        { type: 'frontend/set_user_data', key: 'test', value: {} },
        { type: 'render_template', template: '{{ states }}' },
      ]) {
        try { await hass.callWS(command); failures.push('unexpected-success'); }
        catch (error) { failures.push(error.code); }
      }
      const token = hass.auth.accessToken;
      const statuses = [];
      for (const path of ['/api/services/switch/toggle', '/api/states/sensor.demo_battery', '/api/events/test']) {
        const result = await fetch(path, { method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: '{}' });
        statuses.push(result.status);
      }
      return { failures, statuses, battery: hass.states['sensor.demo_battery'].state,
        views: (await hass.callWS({ type: 'lovelace/config' })).views.length };
    });
    assert.deepEqual(rejected.failures, Array(7).fill('read_only'));
    assert.deepEqual(rejected.statuses, [403, 403, 403]);
    assert.ok(Number(rejected.battery) >= 45 && Number(rejected.battery) <= 95, 'battery follows bounded simulation while writes remain rejected');
    assert.equal(rejected.views, 1);
    await page.waitForFunction((previous) => document.querySelector('home-assistant').hass.states['sensor.demo_temperature'].state !== previous,
      initial.state, { timeout: 80_000 });
    await page.screenshot({ path: `${evidence}/dashboard-after.png` });
    // Session refresh goes through the same constrained auth endpoint.
    await page.evaluate(() => document.querySelector('home-assistant').hass.auth.refreshAccessToken());
    assert.equal(await page.evaluate(() => document.querySelector('home-assistant').hass.user.is_admin), false);
    const revoked = await page.evaluate(async () => {
      const tokens = JSON.parse(localStorage.getItem('hassTokens'));
      const result = await fetch('/auth/token', { method: 'POST',
        body: new URLSearchParams({ action: 'revoke', token: tokens.refresh_token }) });
      return result.status;
    });
    assert.equal(revoked, 200, 'standard HA logout must revoke only the supplied session token');
  } catch (error) {
    await page.screenshot({ path: `${evidence}/failure.png` });
    throw error;
  } finally {
    await browser.close();
    await api.dispose();
  }
});
