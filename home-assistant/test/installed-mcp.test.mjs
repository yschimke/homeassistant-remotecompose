// CI-only, after installed.test.mjs: the MCP server inside a real HA container, pinned to one user.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const { chromium, request } = await import(`${root}embed/node_modules/playwright/index.mjs`);
const base = 'http://127.0.0.1:8123';
const client_id = `${base}/`;

/** Tokens for [username], onboarding the instance first when nothing has yet. */
async function signIn(api, username, password, name) {
  const onboarding = await (await api.get('/api/onboarding')).json();
  if (onboarding.some((step) => step.step === 'user' && !step.done)) {
    const user = await api.post('/api/onboarding/users', {data:{client_id, name, username, password, language:'en'}});
    const {auth_code} = await user.json();
    const tokens = await (await api.post('/auth/token', {form:{client_id, code:auth_code, grant_type:'authorization_code'}})).json();
    const headers = {Authorization:`Bearer ${tokens.access_token}`};
    for (const step of ['core_config', 'analytics']) await api.post(`/api/onboarding/${step}`, {headers, data:{}});
    await api.post('/api/onboarding/integration', {headers, data:{client_id, redirect_uri:client_id}});
    return tokens;
  }
  const flow = await (await api.post('/auth/login_flow', {data:{client_id, handler:['homeassistant', null], redirect_uri:client_id}})).json();
  const login = await (await api.post(`/auth/login_flow/${flow.flow_id}`, {data:{client_id, username, password}})).json();
  assert.equal(login.type, 'create_entry', `${username} signs in`);
  return (await api.post('/auth/token', {form:{client_id, code:login.result, grant_type:'authorization_code'}})).json();
}

/** Sets the MCP server's user through Configure, keeping the panel's settings. */
async function setMcpUser(api, headers, pick) {
  const [entry] = await (await api.get('/api/config/config_entries/entry?domain=terrazzo', {headers})).json();
  const form = await (await api.post('/api/config/config_entries/options/flow', {headers, data:{handler:entry.entry_id}})).json();
  const field = form.data_schema.find((item) => item.name === 'mcp_user');
  assert.ok(field, 'Configure offers the MCP server user');
  const options = field.selector.select.options;
  assert.equal(options[0].value, 'off', 'Off comes first');
  const choice = pick(options);
  assert.ok(choice, 'the user is offered');
  const saved = await api.post(`/api/config/config_entries/options/flow/${form.flow_id}`,
    {headers, data:{title:'Terrazzo', dashboard:'wall-panel', mcp_user:choice.value}});
  assert.equal((await saved.json()).type, 'create_entry');
  return choice.value;
}

test('MCP server serves only its pinned user, on this instance', {timeout:120_000}, async () => {
  const api = await request.newContext({baseURL:base});
  const browser = await chromium.launch();
  try {
    const owner = await signIn(api, 'panel', 'fixture-only-password', 'Test panel user');
    const headers = {Authorization:`Bearer ${owner.access_token}`};
    // Run alone against a fresh container, HA may still be starting: no entry, no templates yet.
    for (let i = 0; ; i++) {
      const config = await (await api.get('/api/config', {headers})).json();
      const entries = await (await api.get('/api/config/config_entries/entry?domain=terrazzo', {headers})).json();
      if (config.state === 'RUNNING' && entries.length === 1) break;
      assert.ok(i < 120, 'Home Assistant finishes starting');
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    const mcp = (body, token = owner.access_token, extra = {}) => api.post('/api/terrazzo/mcp', {
      headers:{...(token ? {Authorization:`Bearer ${token}`} : {}), Accept:'application/json, text/event-stream', ...extra},
      data:body,
    });
    const rpc = async (method, params, token) => {
      const response = await mcp({jsonrpc:'2.0', id:1, method, params}, token);
      assert.equal(response.status(), 200, `${method} answers`);
      return (await response.json()).result;
    };
    const tool = async (name, args = {}) => {
      const result = await rpc('tools/call', {name, arguments:args});
      assert.notEqual(result.isError, true, `${name}: ${result.content?.[0]?.text}`);
      return result.structuredContent;
    };
    const ping = {jsonrpc:'2.0', id:1, method:'ping'};

    assert.equal((await mcp(ping)).status(), 404, 'off until a user is chosen');
    await setMcpUser(api, headers, (options) => options.find((o) => o.label.startsWith('Test panel user')));

    const anonymous = await mcp(ping, null);
    assert.equal(anonymous.status(), 401);
    const challenge = anonymous.headers()['www-authenticate'];
    const metadataUrl = /resource_metadata="([^"]+)"/.exec(challenge)?.[1];
    assert.ok(metadataUrl, `401 points at the resource metadata: ${challenge}`);
    const metadata = await (await api.get(metadataUrl)).json();
    assert.equal(metadata.resource, `${base}/api/terrazzo/mcp`);
    assert.deepEqual(metadata.authorization_servers, [base], 'HA itself issues the tokens');
    assert.equal((await mcp(ping, 'not-a-token')).status(), 401);
    assert.equal((await mcp(ping, owner.access_token, {Origin:'https://evil.example'})).status(), 403,
      'no cross-origin browser calls');

    const init = await rpc('initialize', {protocolVersion:'2025-06-18', capabilities:{}, clientInfo:{name:'test', version:'1'}});
    assert.equal(init.protocolVersion, '2025-06-18');
    assert.equal(init.serverInfo.name, 'terrazzo');
    assert.equal((await mcp({jsonrpc:'2.0', method:'notifications/initialized'})).status(), 202);
    const tools = (await rpc('tools/list')).tools.map((t) => t.name);
    assert.deepEqual(tools.sort(), ['find_entities', 'get_dashboard', 'get_entity_state', 'list_dashboards', 'toggle', 'turn_off', 'turn_on']);

    const found = (await tool('find_entities', {query:'wall light'})).entities.map((e) => e.entity_id);
    assert.ok(found.includes('light.wall_light') && found.includes('input_boolean.wall_light'), found.join());
    await tool('turn_off', {entity_id:'input_boolean.wall_light'});
    assert.equal((await tool('get_entity_state', {entity_id:'input_boolean.wall_light'})).state, 'off');
    // The template light follows the input_boolean a moment later.
    for (let i = 0; (await tool('get_entity_state', {entity_id:'light.wall_light'})).state !== 'off'; i++) {
      assert.ok(i < 50, 'light.wall_light settles off');
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    const before = await tool('turn_on', {entity_id:'light.wall_light'});
    assert.equal(before.state, 'off', 'switching returns the state from before');
    const state = await (await api.get('/api/states/input_boolean.wall_light', {headers})).json();
    assert.equal(state.state, 'on', 'turn_on acted on this instance');
    await tool('turn_off', {entity_id:'light.wall_light'});

    const dashboards = (await tool('list_dashboards')).dashboards;
    const wall = dashboards.find((d) => d.url_path === 'wall-panel');
    assert.equal(wall?.terrazzo_url, `${base}/terrazzo?dashboard=wall-panel`);
    const config = (await tool('get_dashboard', {url_path:'wall-panel'})).config;
    assert.ok(Array.isArray(config.views), 'the dashboard config has views');

    // A long-lived token of the same user works too; another user's token never does.
    const page = await browser.newPage();
    await page.addInitScript(({tokens, base}) => localStorage.setItem('hassTokens', JSON.stringify({
      ...tokens, hassUrl:base, clientId:`${base}/`, expires:Date.now() + tokens.expires_in * 1000,
    })), {tokens:owner, base});
    await page.goto(base);
    await page.waitForFunction(() => document.querySelector('home-assistant')?.hass?.connection);
    const {longLived, other} = await page.evaluate(async () => {
      const hass = document.querySelector('home-assistant').hass;
      const longLived = await hass.callWS({type:'auth/long_lived_access_token', client_name:'MCP test', lifespan:1});
      const created = await hass.callWS({type:'config/auth/create', name:'Other user', group_ids:['system-users']});
      await hass.callWS({type:'config/auth_provider/homeassistant/create', user_id:created.user.id, username:'other', password:'fixture-other-password'});
      return {longLived, other:created.user.id};
    });
    assert.equal((await mcp(ping, longLived)).status(), 200, 'the pinned user\'s long-lived token');
    const otherTokens = await signIn(api, 'other', 'fixture-other-password', 'Other user');
    assert.equal((await mcp(ping, otherTokens.access_token)).status(), 403, 'pinned to one user');

    // Re-pinned to the other (non-admin) user, the owner is refused instead.
    await setMcpUser(api, headers, (options) => options.find((o) => o.value === other));
    assert.equal((await mcp(ping, owner.access_token)).status(), 403);
    assert.equal((await mcp(ping, otherTokens.access_token)).status(), 200);

    await setMcpUser(api, headers, (options) => options[0]);
    assert.equal((await mcp(ping)).status(), 404, 'Off switches it off at once');
    assert.equal((await api.get(metadataUrl)).status(), 404);
  } finally { await browser.close(); await api.dispose(); }
});
