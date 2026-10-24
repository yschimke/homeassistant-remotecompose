// CI-only, after installed-mcp.test.mjs: dashboard edit sessions inside a real HA container. A
// person starts a session over the WebSocket, an agent (the MCP server's non-admin user) drafts,
// and only an administrator applies, while the dashboard still holds the session's starting config.
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

/** A page signed in as [tokens]' user, whose `ws` runs one WebSocket command as them. */
async function signedInPage(browser, tokens) {
  const page = await browser.newPage();
  await page.addInitScript(({tokens, base}) => localStorage.setItem('hassTokens', JSON.stringify({
    ...tokens, hassUrl:base, clientId:`${base}/`, expires:Date.now() + tokens.expires_in * 1000,
  })), {tokens, base});
  await page.goto(base);
  await page.waitForFunction(() => document.querySelector('home-assistant')?.hass?.connection);
  /** The command's result, or `{error: {code, message}}` when Home Assistant refuses it. */
  const ws = (message) => page.evaluate(async (message) => {
    try {
      return await document.querySelector('home-assistant').hass.callWS(message);
    } catch (error) {
      return {error:{code:error.code, message:error.message}};
    }
  }, message);
  return {page, ws};
}

async function setMcpUser(api, headers, userId) {
  const [entry] = await (await api.get('/api/config/config_entries/entry?domain=terrazzo', {headers})).json();
  const form = await (await api.post('/api/config/config_entries/options/flow', {headers, data:{handler:entry.entry_id}})).json();
  const saved = await api.post(`/api/config/config_entries/options/flow/${form.flow_id}`,
    {headers, data:{title:'Terrazzo', dashboard:'wall-panel', mcp_user:userId}});
  assert.equal((await saved.json()).type, 'create_entry');
}

const BASELINE = {title:'Edit fixture', views:[{title:'Home', type:'sections', sections:[
  {type:'grid', cards:[{type:'heading', heading:'Lights'}, {type:'tile', entity:'light.wall_light'}]}]}]};
const DRAFT = {title:'Edit fixture', views:[{title:'Home', type:'sections', sections:[
  {type:'grid', cards:[
    {type:'heading', heading:'Lights'},
    {type:'tile', entity:'light.wall_light', grid_options:{columns:6}},
    {type:'tile', entity:'input_boolean.wall_light', grid_options:{columns:6}}]}]}]};

test('Edit sessions: agents draft, administrators apply', {timeout:180_000}, async () => {
  const api = await request.newContext({baseURL:base});
  const browser = await chromium.launch();
  try {
    const ownerTokens = await signIn(api, 'panel', 'fixture-only-password', 'Test panel user');
    const headers = {Authorization:`Bearer ${ownerTokens.access_token}`};
    for (let i = 0; ; i++) {
      const config = await (await api.get('/api/config', {headers})).json();
      const entries = await (await api.get('/api/config/config_entries/entry?domain=terrazzo', {headers})).json();
      if (config.state === 'RUNNING' && entries.length === 1) break;
      assert.ok(i < 120, 'Home Assistant finishes starting');
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    const owner = await signedInPage(browser, ownerTokens);

    // A storage dashboard to edit, and a non-admin user for the agent.
    const created = await owner.ws({type:'lovelace/dashboards/create', url_path:'edit-fixture',
      title:'Edit fixture', mode:'storage', require_admin:false, show_in_sidebar:false});
    assert.ok(!created.error, `dashboard created: ${created.error?.message}`);
    assert.ok(!(await owner.ws({type:'lovelace/config/save', url_path:'edit-fixture', config:BASELINE}))?.error);
    const agentUser = await owner.ws({type:'config/auth/create', name:'Dashboard agent', group_ids:['system-users']});
    await owner.ws({type:'config/auth_provider/homeassistant/create', user_id:agentUser.user.id,
      username:'agent', password:'fixture-agent-password'});
    const agentTokens = await signIn(api, 'agent', 'fixture-agent-password', 'Dashboard agent');
    const agent = await signedInPage(browser, agentTokens);
    await setMcpUser(api, headers, agentUser.user.id);

    // Home Assistant itself refuses a non-admin save: the reason agents only draft.
    const refused = await agent.ws({type:'lovelace/config/save', url_path:'edit-fixture', config:DRAFT});
    assert.equal(refused.error?.code, 'unauthorized', 'a non-admin cannot save a dashboard');

    // The person starts a session; the prompt names the session and the endpoint, nothing else.
    const started = await owner.ws({type:'terrazzo/edit/start', url_path:'edit-fixture', request:'Add the switch'});
    assert.ok(!started.error, `session started: ${started.error?.message}`);
    const sessionId = started.session.session_id;
    assert.deepEqual(started.session.baseline, BASELINE);
    assert.ok(started.prompt.includes(sessionId) && started.prompt.includes('/api/terrazzo/mcp'), started.prompt);
    assert.ok(!started.prompt.includes(ownerTokens.access_token) && !started.prompt.includes('light.wall_light'));
    assert.equal((await owner.ws({type:'terrazzo/edit/get', session_id:sessionId})).prompt, started.prompt,
      'a resumed session hands out the same prompt');
    const listed = await owner.ws({type:'terrazzo/edit/list', url_path:'edit-fixture'});
    assert.deepEqual(listed.sessions.map((s) => s.session_id), [sessionId]);

    // The owner watches the session while the agent works.
    await owner.page.evaluate((sessionId) => {
      window.sessionUpdates = [];
      document.querySelector('home-assistant').hass.connection.subscribeMessage(
        (event) => window.sessionUpdates.push(event.session), {type:'terrazzo/edit/subscribe', session_id:sessionId});
    }, sessionId);

    const mcp = async (name, args = {}) => {
      const response = await api.post('/api/terrazzo/mcp', {
        headers:{Authorization:`Bearer ${agentTokens.access_token}`, Accept:'application/json, text/event-stream'},
        data:{jsonrpc:'2.0', id:1, method:'tools/call', params:{name, arguments:args}},
      });
      assert.equal(response.status(), 200, `${name} answers`);
      return (await response.json()).result;
    };
    const read = await mcp('get_edit_session', {session_id:sessionId});
    assert.notEqual(read.isError, true, read.content?.[0]?.text);
    assert.deepEqual(read.structuredContent.dashboard, BASELINE);
    assert.equal(read.structuredContent.request, 'Add the switch');
    assert.ok((await mcp('get_card_guide')).structuredContent.native_card_types.includes('tile'));
    const invented = await mcp('submit_draft', {session_id:sessionId,
      config:{views:[{cards:[{type:'tile', entity:'light.not_here'}]}]}});
    assert.equal(invented.isError, true, 'a draft naming a missing entity is refused');
    const submitted = await mcp('submit_draft', {session_id:sessionId, config:DRAFT, summary:'Add the switch'});
    assert.notEqual(submitted.isError, true, submitted.content?.[0]?.text);
    const draftId = submitted.structuredContent.draft_id;
    await owner.page.waitForFunction(() => window.sessionUpdates.some((s) => s.drafts.length === 1));

    // Only an administrator applies; the agent's own WebSocket can't.
    const agentApply = await agent.ws({type:'terrazzo/edit/apply', session_id:sessionId, draft_id:draftId});
    assert.equal(agentApply.error?.code, 'unauthorized');

    // A dashboard changed since the session started is never overwritten.
    const elsewhere = structuredClone(BASELINE);
    elsewhere.title = 'Changed elsewhere';
    assert.ok(!(await owner.ws({type:'lovelace/config/save', url_path:'edit-fixture', config:elsewhere}))?.error);
    const conflict = await owner.ws({type:'terrazzo/edit/apply', session_id:sessionId, draft_id:draftId});
    assert.equal(conflict.error?.code, 'conflict');
    assert.equal((await owner.ws({type:'lovelace/config', url_path:'edit-fixture'})).title, 'Changed elsewhere');
    assert.ok(!(await owner.ws({type:'lovelace/config/save', url_path:'edit-fixture', config:BASELINE}))?.error);

    // The person asks for a change; the agent reads the note on its next call.
    assert.ok(!(await owner.ws({type:'terrazzo/edit/note', session_id:sessionId, text:'Looks good'}))?.error);
    assert.equal((await mcp('get_edit_session', {session_id:sessionId})).structuredContent.notes[0].text, 'Looks good');

    const applied = await owner.ws({type:'terrazzo/edit/apply', session_id:sessionId, draft_id:draftId});
    assert.ok(!applied.error, `applied: ${applied.error?.message}`);
    assert.equal(applied.session.status, 'applied');
    assert.deepEqual(await owner.ws({type:'lovelace/config', url_path:'edit-fixture'}), DRAFT);
    const again = await mcp('submit_draft', {session_id:sessionId, config:DRAFT});
    assert.equal(again.isError, true, 'an applied session takes no more drafts');

    const undone = await owner.ws({type:'terrazzo/edit/undo', session_id:sessionId});
    assert.ok(!undone.error, `undone: ${undone.error?.message}`);
    assert.equal(undone.session.status, 'open');
    assert.deepEqual(await owner.ws({type:'lovelace/config', url_path:'edit-fixture'}), BASELINE);

    // An agent can start a session itself, and a person can discard it.
    const own = await mcp('start_edit_session', {url_path:'edit-fixture', request:'Tidy up'});
    assert.notEqual(own.isError, true, own.content?.[0]?.text);
    const discarded = await owner.ws({type:'terrazzo/edit/discard', session_id:own.structuredContent.session_id});
    assert.equal(discarded.session.status, 'discarded');

    await setMcpUser(api, headers, 'off');
  } finally { await browser.close(); await api.dispose(); }
});
