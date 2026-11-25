// CI-only, after installed-edits.test.mjs: the dashboard chat, end to end in a real HA container
// with the real panel. Home Assistant's own conversation agent (its Ollama integration, against a
// fake Ollama server this test serves, with Assist and "Terrazzo dashboards" on) is Terrazzo's
// dashboard agent. The person opens a dashboard, asks the agent from its corner, sees the draft
// drawn in place with their cards, and applies it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { addOllamaAgent, fakeOllama, restApi, setDashboardAgent } from './ollama-fixture.mjs';
const root = fileURLToPath(new URL('../../', import.meta.url));
const { chromium, request } = await import(`${root}embed/node_modules/playwright/index.mjs`);
const base = 'http://127.0.0.1:8123';
const client_id = `${base}/`;
const evidence = `${root}build/ha-plugin-evidence`;
mkdirSync(evidence, { recursive: true });

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

const BASELINE = {title:'Live fixture', views:[{title:'Home', type:'sections', sections:[
  {type:'grid', cards:[{type:'heading', heading:'Lights'}, {type:'tile', entity:'light.wall_light'}]}]}]};
const DRAFT = {title:'Live fixture', views:[{title:'Home', type:'sections', sections:[
  {type:'grid', cards:[
    {type:'heading', heading:'Lights'},
    {type:'tile', entity:'light.wall_light', grid_options:{columns:6}},
    {type:'tile', entity:'input_boolean.wall_light', grid_options:{columns:6}}]}]}]};
const REPLY = 'I put the switch beside the light.';

/** The model: from the dashboard chat, read the open dashboard, draft on it, say so. */
function script({messages, system, tool, step}) {
  const chatPath = (system.match(/Its url_path is ([a-z0-9-]+)\./) || [])[1];
  if (!system.includes('chatting with you from Terrazzo') || !chatPath)
    return {role:'assistant', content:'Noted.'};
  if (step === 0)
    return {role:'assistant', content:'', tool_calls:[{function:{name:tool('get_dashboard'), arguments:{url_path:chatPath}}}]};
  if (step === 1) {
    // Home Assistant hands the model a tool's result as {data, error}.
    const result = JSON.parse(messages.at(-1).content || '{}');
    const read = result.data ?? result;
    return {role:'assistant', content:'', tool_calls:[{function:{name:tool('submit_draft'),
      arguments:{config:DRAFT, revision:read.revision, summary:'Switch beside the light'}}}]};
  }
  const last = messages.at(-1).content || '';
  return {role:'assistant', content:last.includes('"error"') ? `Refused: ${last}` : REPLY};
}

test('Dashboard chat: the person asks from the dashboard, sees the draft in place and applies it', {timeout:300_000}, async () => {
  const ollama = await fakeOllama(script);
  const api = await request.newContext({baseURL:base});
  const browser = await chromium.launch();
  const page = await browser.newPage({viewport:{width:1200, height:900}, deviceScaleFactor:1});
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  let call;
  let headers;
  let ollamaEntry;
  try {
    const ownerTokens = await signIn(api, 'panel', 'fixture-only-password', 'Test panel user');
    headers = {Authorization:`Bearer ${ownerTokens.access_token}`};
    call = restApi(base, headers);
    await page.addInitScript(({tokens, base}) => localStorage.setItem('hassTokens', JSON.stringify({
      ...tokens, hassUrl:base, clientId:`${base}/`, expires:Date.now() + tokens.expires_in * 1000,
    })), {tokens:ownerTokens, base});
    // No hosted design server in CI: the panel draws with its bundled designs.
    await page.route('https://ha.coo.ee/**', (route) => route.fulfill({status:404}));
    await page.goto(base);
    await page.waitForFunction(() => document.querySelector('home-assistant')?.hass?.connection);
    const ws = (message) => page.evaluate((message) =>
      document.querySelector('home-assistant').hass.callWS(message).catch((e) => ({error:{code:e.code, message:e.message}})), message);

    assert.ok(!(await ws({type:'lovelace/dashboards/create', url_path:'live-fixture', title:'Live fixture',
      mode:'storage', require_admin:false, show_in_sidebar:false}))?.error);
    assert.ok(!(await ws({type:'lovelace/config/save', url_path:'live-fixture', config:BASELINE}))?.error);

    // Home Assistant's agent, with Assist and Terrazzo's tools, chosen as Terrazzo's.
    const added = await addOllamaAgent(call, ollama.port, 'Live agent', ['assist', 'terrazzo_dashboards'],
      async () => (await ws({type:'conversation/agent/list'})).agents);
    ollamaEntry = added.entryId;
    await setDashboardAgent(call, added.agentId);
    for (let i = 0; ; i++) {
      const info = await ws({type:'terrazzo/agent/info'});
      if (info.enabled && info.agent_id === added.agentId) {
        assert.equal(info.chat, true);
        assert.equal(info.assist, true);
        break;
      }
      assert.ok(i < 40, `the reloaded entry chooses the agent: ${JSON.stringify(info)}`);
      await new Promise((resolve) => setTimeout(resolve, 500));
    }

    // The person opens the dashboard; the chat sits in its corner.
    await page.goto(`${base}/terrazzo?dashboard=live-fixture`);
    const panel = page.frameLocator('terrazzo-panel iframe');
    await panel.getByText('Lights', {exact:true}).first().waitFor({timeout:90_000});
    await panel.getByText('Ask Live agent', {exact:true}).click({force:true, timeout:60_000});
    await panel.getByText('Ask Live agent to switch what you see here, or to change this dashboard.', {exact:true}).waitFor();
    // Compose draws on a canvas: focus the field through its accessibility node, then type keys.
    await panel.getByRole('textbox', {name:'Message'}).click({force:true});
    // Compose moves focus on the next frame; a key sent at once is dropped.
    await page.waitForTimeout(400);
    await page.keyboard.type('Add the switch next to the light', {delay:20});
    await panel.getByRole('button', {name:'Send'}).click({force:true});
    await panel.getByText(REPLY, {exact:true}).waitFor({timeout:60_000});
    await page.screenshot({path:`${evidence}/agent-live-1-chat.png`});

    // The agent was told what is on screen, as the person.
    const system = ollama.chats[0].messages.filter((m) => m.role === 'system').map((m) => m.content).join('\n');
    assert.match(system, /looking at the dashboard 'Live fixture', on its 'Home' view/);
    assert.match(system, /- light\.wall_light \(/);
    assert.ok(ollama.chats[0].tools.some((t) => t.function.name.endsWith('submit_draft')));

    // Its draft is drawn in place, with the dashboard's own cards; Use it applies it.
    await panel.getByText('Switch beside the light', {exact:true}).first().waitFor({timeout:30_000});
    await panel.getByText('Use it', {exact:true}).waitFor();
    await page.screenshot({path:`${evidence}/agent-live-2-draft.png`});
    const {sessions} = await ws({type:'terrazzo/edit/list', url_path:'live-fixture'});
    const session = sessions.find((s) => s.status === 'open');
    assert.ok(session, 'the chat opened an edit session on this dashboard');
    await panel.getByText('Use it', {exact:true}).click({force:true});
    for (let i = 0; ; i++) {
      const saved = await ws({type:'lovelace/config', url_path:'live-fixture'});
      if (JSON.stringify(saved) === JSON.stringify(DRAFT)) break;
      assert.ok(i < 40, `the draft is applied: ${JSON.stringify(saved)}`);
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    await page.screenshot({path:`${evidence}/agent-live-3-applied.png`});
    // The chat's turn was the only one: the session's own agent didn't answer it again.
    assert.equal(ollama.chats.length, 3);
    assert.deepEqual(errors, [], 'no page errors');
  } catch (error) {
    await page.screenshot({path:`${evidence}/agent-live-failure.png`}).catch(() => {});
    const text = await page.frameLocator('terrazzo-panel iframe').locator('body').innerText({timeout:2000}).catch(() => '<unavailable>');
    console.error('Dashboard chat diagnostics:', JSON.stringify({errors, text}));
    throw error;
  } finally {
    if (call) await setDashboardAgent(call).catch(() => {});
    if (ollamaEntry) await fetch(`${base}/api/config/config_entries/entry/${ollamaEntry}`,
      {method:'DELETE', headers}).catch(() => {});
    ollama.server.close();
    await browser.close();
    await api.dispose();
  }
});
