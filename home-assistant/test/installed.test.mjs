// CI-only: a fresh real HA container, real WASM build, real TS player, synthetic home/user.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const { chromium, request } = await import(`${root}embed/node_modules/playwright/index.mjs`);
const base = 'http://127.0.0.1:8123';
const evidence = `${root}build/ha-plugin-evidence`;
mkdirSync(evidence, { recursive: true });
const template = readFileSync(`${root}design-server/src/test/resources/tile-light.rc`);
const sha = createHash('sha256').update(template).digest('hex');

test('installed HA /terrazzo and Lovelace custom card render and act as the signed-in user', {timeout: 180_000}, async () => {
  const api = await request.newContext({baseURL:base});
  const user = await api.post('/api/onboarding/users', {data:{client_id:`${base}/`, name:'Test panel user', username:'panel', password:'fixture-only-password', language:'en'}});
  assert.equal(user.ok(), true, 'fresh HA must accept onboarding');
  const {auth_code} = await user.json();
  const response = await api.post('/auth/token', {form:{client_id:`${base}/`, code:auth_code, grant_type:'authorization_code'}});
  assert.equal(response.ok(), true);
  const tokens = await response.json();
  const headers = {Authorization:`Bearer ${tokens.access_token}`};
  for (const step of ['core_config','analytics']) {
    const result = await api.post(`/api/onboarding/${step}`, {headers,data:{}});
    assert.equal(result.ok(), true, `onboarding ${step}`);
  }
  await api.post('/api/onboarding/integration', {headers,data:{client_id:`${base}/`,redirect_uri:`${base}/`}});
  assert.equal((await api.get('/api/')).status(), 401, 'no anonymous HA API');
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({viewport:{width:1200,height:800},deviceScaleFactor:1});
    const errors = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.addInitScript(({tokens,base}) => localStorage.setItem('hassTokens', JSON.stringify({
      ...tokens, hassUrl:base, clientId:`${base}/`, expires:Date.now() + tokens.expires_in * 1000,
    })), {tokens,base});
    // Deterministic design data, served without user credentials. Real player remains local.
    await page.route('https://ha.coo.ee/**', async (route) => {
      assert.equal(route.request().headers().authorization, undefined, 'HA credentials must stay local');
      const path = new URL(route.request().url()).pathname;
      if (path === '/api/cards/resolve') return route.fulfill({json:{sha256:sha,exact:true},headers:{'Access-Control-Allow-Origin':'*'}});
      if (path.endsWith('.rc')) return route.fulfill({body:template,contentType:'application/octet-stream',headers:{'Access-Control-Allow-Origin':'*'}});
      return route.fulfill({status:404});
    });
    await page.goto(`${base}/terrazzo`);
    const panel = page.frameLocator('terrazzo-panel iframe');
    const toggle = panel.getByRole('switch');
    await toggle.waitFor({timeout:90_000});
    assert.equal(await toggle.isChecked(), false);
    await page.screenshot({path:`${evidence}/dashboard-before.png`});
    await toggle.click({force:true});
    await page.waitForFunction(async () => {
      const auth = document.querySelector('home-assistant').hass.auth;
      const result = await fetch('/api/states/input_boolean.wall_light', {headers:{Authorization:`Bearer ${auth.accessToken}`}});
      return (await result.json()).state === 'on';
    });
    await page.screenshot({path:`${evidence}/dashboard-after.png`});
    await page.goto(`${base}/wall-panel/0`);
    const card = page.locator('remote-compose-card');
    await card.waitFor({timeout:60_000});
    await page.waitForFunction(() => {
      const visit = (node) => {
        if (node.localName === 'remote-compose-card') return node;
        for (const child of [...(node.shadowRoot?.children ?? []), ...(node.children ?? [])]) {
          const found = visit(child); if (found) return found;
        }
      };
      const card = visit(document.documentElement);
      return card?.dataset.status === 'live' && card.pushed.get('USER:light.s0.state') === 'On';
    });
    await page.screenshot({path:`${evidence}/lovelace-before.png`});
    await card.locator('canvas').click({position:{x:100,y:35}});
    await page.waitForFunction(async () => {
      const auth = document.querySelector('home-assistant').hass.auth;
      const result = await fetch('/api/states/input_boolean.wall_light', {headers:{Authorization:`Bearer ${auth.accessToken}`}});
      return (await result.json()).state === 'off';
    });
    await page.screenshot({path:`${evidence}/lovelace-after.png`});
    assert.deepEqual(errors, []);
    // A real read-only HA user can render, but cannot turn the light on through either host.
    const userId = await page.evaluate(async () => {
      const hass = document.querySelector('home-assistant').hass;
      const created = await hass.callWS({type:'config/auth/create',name:'Read only panel',group_ids:['system-read-only']});
      await hass.callWS({type:'config/auth_provider/homeassistant/create',user_id:created.user.id,
        username:'readonly',password:'fixture-readonly-password'});
      return created.user.id;
    });
    assert.ok(userId);
    const flowResponse = await api.post('/auth/login_flow', {data:{client_id:`${base}/`,handler:['homeassistant',null],redirect_uri:`${base}/`}});
    const flow = await flowResponse.json();
    const loginResponse = await api.post(`/auth/login_flow/${flow.flow_id}`, {data:{client_id:`${base}/`,username:'readonly',password:'fixture-readonly-password'}});
    const login = await loginResponse.json();
    assert.equal(login.type, 'create_entry');
    const readOnlyResponse = await api.post('/auth/token', {form:{client_id:`${base}/`,code:login.result,grant_type:'authorization_code'}});
    assert.equal(readOnlyResponse.ok(), true);
    const readOnlyTokens = await readOnlyResponse.json();
    const limitedContext = await browser.newContext({viewport:{width:1200,height:800}});
    await limitedContext.addInitScript(({tokens,base}) => localStorage.setItem('hassTokens', JSON.stringify({
      ...tokens,hassUrl:base,clientId:`${base}/`,expires:Date.now()+tokens.expires_in*1000,
    })), {tokens:readOnlyTokens,base});
    const limited = await limitedContext.newPage();
    await limited.goto(`${base}/terrazzo`);
    const limitedToggle = limited.frameLocator('terrazzo-panel iframe').getByRole('switch');
    await limitedToggle.waitFor({timeout:60_000});
    await limitedToggle.click({force:true});
    await limited.frameLocator('terrazzo-panel iframe').getByText('The action could not be completed.', {exact:true}).waitFor();
    const state = await api.get('/api/states/input_boolean.wall_light', {headers});
    assert.equal((await state.json()).state, 'off', 'read-only user cannot act through WASM');
    await limited.screenshot({path:`${evidence}/dashboard-readonly-denied.png`});
    await limitedContext.close();
  } finally { await browser.close(); await api.dispose(); }
});
