// CI-only, after installed-mcp.test.mjs: the dashboard tools registered as a Home Assistant LLM API
// (llm_api.py), played through Home Assistant's own MCP Server integration, which serves an LLM API
// as its tools: what any conversation agent the person sets up to use "Terrazzo dashboards" gets.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const { request } = await import(`${root}embed/node_modules/playwright/index.mjs`);
const base = 'http://127.0.0.1:8123';
const client_id = `${base}/`;

/** Tokens for the panel user, whom installed.test.mjs onboarded. */
async function signIn(api, username, password) {
  const flow = await (await api.post('/auth/login_flow', {data:{client_id, handler:['homeassistant', null], redirect_uri:client_id}})).json();
  const login = await (await api.post(`/auth/login_flow/${flow.flow_id}`, {data:{client_id, username, password}})).json();
  assert.equal(login.type, 'create_entry', `${username} signs in`);
  return (await api.post('/auth/token', {form:{client_id, code:login.result, grant_type:'authorization_code'}})).json();
}

test('the dashboard tools are an LLM API a conversation agent or the MCP Server can use', {timeout:180_000}, async () => {
  const api = await request.newContext({baseURL:base});
  const owner = await signIn(api, 'panel', 'fixture-only-password');
  const headers = {Authorization:`Bearer ${owner.access_token}`};

  // Home Assistant's MCP Server serves the LLM APIs its entry chooses. Since 2026.11 a new entry
  // serves every registered API and the choice is the entry's options; before, its setup form.
  const form = await (await api.post('/api/config/config_entries/flow', {headers, data:{handler:'mcp_server'}})).json();
  assert.equal(form.type, 'form', JSON.stringify(form));
  const offersApi = (schema) => {
    const field = (schema ?? []).find((item) => item.name === 'llm_hass_api');
    if (!field) return false;
    const option = field.selector.select.options.find((o) => o.value === 'terrazzo_dashboards');
    assert.ok(option, `offered: ${field.selector.select.options.map((o) => o.value).join(', ')}`);
    assert.equal(option.label, 'Terrazzo dashboards');
    return true;
  };
  const chooseAtSetup = offersApi(form.data_schema);
  const created = await (await api.post(`/api/config/config_entries/flow/${form.flow_id}`,
    {headers, data:chooseAtSetup ? {llm_hass_api:['terrazzo_dashboards']} : {}})).json();
  assert.equal(created.type, 'create_entry', JSON.stringify(created));
  if (!chooseAtSetup) {
    // Only ours, so the tool list below is exactly the dashboard tools.
    const options = await (await api.post('/api/config/config_entries/options/flow',
      {headers, data:{handler:created.result.entry_id}})).json();
    assert.equal(options.type, 'form', JSON.stringify(options));
    assert.ok(offersApi(options.data_schema), `options: ${JSON.stringify(options.data_schema)}`);
    const chosen = await (await api.post(`/api/config/config_entries/options/flow/${options.flow_id}`, {headers,
      data:{all_llm_apis:false, llm_hass_api:['terrazzo_dashboards'], require_admin:true, read_only:false}})).json();
    assert.equal(chosen.type, 'create_entry', JSON.stringify(chosen));
  }

  const rpc = async (method, params) => {
    let response;
    // The entry sets up (and may install the MCP SDK) after the flow returns.
    for (let i = 0; ; i++) {
      response = await api.post('/api/mcp', {
        headers:{...headers, Accept:'application/json, text/event-stream', 'Content-Type':'application/json'},
        data:{jsonrpc:'2.0', id:1, method, params},
      });
      if (response.status() !== 404 || i >= 60) break;
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    assert.equal(response.status(), 200, `${method}: ${await response.text()}`);
    return (await response.json()).result;
  };
  const tool = async (name, args = {}) => {
    const result = await rpc('tools/call', {name, arguments:args});
    assert.notEqual(result.isError, true, `${name}: ${result.content?.[0]?.text}`);
    return JSON.parse(result.content[0].text);
  };

  const tools = (await rpc('tools/list', {})).tools;
  const names = tools.map((t) => t.name).sort();
  assert.deepEqual(names, ['find_designs', 'find_entities', 'get_card_guide', 'get_dashboard', 'get_edit_session',
    'get_entity_state', 'home_overview', 'list_dashboards', 'list_edit_sessions', 'start_edit_session', 'submit_draft']);
  const designs = tools.find((t) => t.name === 'find_designs');
  assert.equal(designs.inputSchema.properties.entity_id.type, 'string', 'arguments are plain values');

  // Run as the token's own user, with what that user may read.
  const found = (await tool('find_entities', {query:'wall light'})).entities.map((e) => e.entity_id);
  assert.ok(found.includes('input_boolean.wall_light'), found.join());
  assert.ok((await tool('get_card_guide')).remote_compose.some((rule) => rule.includes('find_designs')));
  assert.ok(Array.isArray((await tool('home_overview')).areas));
  assert.ok((await tool('list_dashboards')).dashboards.length > 0);
  // A refusal reaches the model as text, not a failed call.
  assert.ok((await tool('get_entity_state', {entity_id:'light.does_not_exist'})).error);

  // Drafting a new dashboard: the session reserves its path and takes a draft, but nothing is
  // created or saved until a person applies it, so the other tests' dashboards are untouched.
  const session = await tool('start_edit_session', {new_dashboard_title:'LLM API test', request:'A switch'});
  assert.ok(session.session_id, JSON.stringify(session));
  const config = {views:[{type:'sections', sections:[{type:'grid', cards:[
    {type:'heading', heading:'Switches'}, {type:'tile', entity:'input_boolean.wall_light'}]}]}]};
  const draft = await tool('submit_draft', {session_id:session.session_id, config, summary:'One switch'});
  assert.ok(draft.draft_id, JSON.stringify(draft));
  const dashboards = (await tool('list_dashboards')).dashboards.map((d) => d.url_path);
  assert.ok(!dashboards.includes(session.url_path), 'not created until applied');
  // A draft naming an entity the home doesn't have is refused, with the reason for the model.
  const refused = await tool('submit_draft', {session_id:session.session_id, summary:'Bad',
    config:{views:[{cards:[{type:'tile', entity:'light.made_up'}]}]}});
  assert.ok(refused.error, JSON.stringify(refused));
});
