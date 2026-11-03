// CI-only, after installed-edits.test.mjs: a live dashboard edit, end to end in a real HA container
// with the real panel. An agent signed in to Home Assistant (the MCP server's non-admin user) is
// listening; the person asks from the panel's menu and the request reaches it with nothing to
// copy. It drafts, says what it's doing and waits for feedback; the person sees the draft drawn
// with their cards, comments on one card, and applies it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
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

async function setMcpUser(api, headers, userId) {
  const [entry] = await (await api.get('/api/config/config_entries/entry?domain=terrazzo', {headers})).json();
  const form = await (await api.post('/api/config/config_entries/options/flow', {headers, data:{handler:entry.entry_id}})).json();
  const saved = await api.post(`/api/config/config_entries/options/flow/${form.flow_id}`,
    {headers, data:{title:'Terrazzo', dashboard:'wall-panel', mcp_user:userId}});
  assert.equal((await saved.json()).type, 'create_entry');
}

const BASELINE = {title:'Live fixture', views:[{title:'Home', type:'sections', sections:[
  {type:'grid', cards:[{type:'heading', heading:'Lights'}, {type:'tile', entity:'light.wall_light'}]}]}]};
const DRAFT = {title:'Live fixture', views:[{title:'Home', type:'sections', sections:[
  {type:'grid', cards:[
    {type:'heading', heading:'Lights'},
    {type:'tile', entity:'light.wall_light', grid_options:{columns:6}},
    {type:'tile', entity:'input_boolean.wall_light', grid_options:{columns:6}}]}]}]};

test('Live agent edit: the person watches, comments on a card and applies', {timeout:300_000}, async () => {
  const api = await request.newContext({baseURL:base});
  const browser = await chromium.launch();
  const page = await browser.newPage({viewport:{width:1200, height:900}, deviceScaleFactor:1});
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    const ownerTokens = await signIn(api, 'panel', 'fixture-only-password', 'Test panel user');
    const headers = {Authorization:`Bearer ${ownerTokens.access_token}`};
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
    const agentUser = await ws({type:'config/auth/create', name:'Live agent', group_ids:['system-users']});
    await ws({type:'config/auth_provider/homeassistant/create', user_id:agentUser.user.id,
      username:'live-agent', password:'fixture-live-agent-password'});
    const agentTokens = await signIn(api, 'live-agent', 'fixture-live-agent-password', 'Live agent');
    await setMcpUser(api, headers, agentUser.user.id);
    const mcp = async (name, args = {}) => {
      const response = await api.post('/api/terrazzo/mcp', {
        headers:{Authorization:`Bearer ${agentTokens.access_token}`, Accept:'application/json, text/event-stream'},
        data:{jsonrpc:'2.0', id:1, method:'tools/call', params:{name, arguments:args}},
        timeout:60_000,
      });
      assert.equal(response.status(), 200, `${name} answers`);
      const result = (await response.json()).result;
      assert.notEqual(result.isError, true, `${name}: ${result.content?.[0]?.text}`);
      return result.structuredContent;
    };

    // The person opens the dashboard and asks an agent from its menu.
    await page.goto(`${base}/terrazzo?dashboard=live-fixture`);
    const panel = page.frameLocator('terrazzo-panel iframe');
    await panel.getByText('Lights', {exact:true}).first().waitFor({timeout:90_000});
    await panel.getByRole('button', {name:'More options'}).click({force:true});
    await panel.getByText('Ask an agent', {exact:true}).click({force:true});
    await panel.getByText('Connect to Home Assistant', {exact:true}).click({force:true});
    // Compose draws on a canvas: focus the field through its accessibility node, then type keys.
    const typeInto = async (label, text) => {
      await panel.getByRole('textbox', {name:label}).click({force:true});
      // Compose moves focus on the next frame; a key sent at once is dropped.
      await page.waitForTimeout(400);
      await page.keyboard.type(text, {delay:20});
    };
    // The agent is already signed in to Home Assistant and listening: the person's request goes
    // straight to it, with no prompt to hand over.
    const waitingForRequest = mcp('await_request', {since:0});
    await new Promise((resolve) => setTimeout(resolve, 500));
    await typeInto('Request', 'Add the switch next to the light');
    await panel.getByText('Start edit session', {exact:true}).click({force:true});
    await panel.getByText('Your agent is listening and picks this up now.', {exact:true}).waitFor();
    assert.equal(await panel.getByText('Share prompt', {exact:true}).count(), 0, 'no prompt to hand over');
    // The first listen also returns requests already open (from earlier tests): find this one.
    const ours = (r) => r.request === 'Add the switch next to the light';
    let heard = await waitingForRequest;
    while (!heard.requests.some(ours)) heard = await mcp('await_request', {since:heard.cursor});
    const session = heard.requests.find(ours);
    assert.equal(session.request, 'Add the switch next to the light');
    await page.screenshot({path:`${evidence}/agent-live-1-listening.png`});

    // The agent works; the person sees what it's doing, its draft and its question.
    const read = await mcp('get_edit_session', {session_id:session.session_id});
    assert.deepEqual(read.dashboard, BASELINE);
    await mcp('set_status', {session_id:session.session_id, status:'Drafting the switch tile'});
    await panel.getByText('Agent · Drafting the switch tile', {exact:true}).waitFor();
    await mcp('submit_draft', {session_id:session.session_id, config:DRAFT, summary:'Switch beside the light'});
    await mcp('send_message', {session_id:session.session_id, text:'Should the switch sit beside the light?'});
    await panel.getByText('Should the switch sit beside the light?', {exact:true}).waitFor();
    await panel.getByText('Switch beside the light', {exact:true}).first().waitFor();
    await panel.getByText('Apply', {exact:true}).waitFor();
    await page.screenshot({path:`${evidence}/agent-live-2-draft.png`});
    // The agent sees the draft exactly as the person's panel drew it.
    let render;
    for (let i = 0; i < 30; i++) {
      const response = await api.post('/api/terrazzo/mcp', {
        headers:{Authorization:`Bearer ${agentTokens.access_token}`, Accept:'application/json, text/event-stream'},
        data:{jsonrpc:'2.0', id:1, method:'tools/call', params:{name:'get_draft_render', arguments:{session_id:session.session_id}}},
      });
      render = (await response.json()).result;
      if (render.content?.[0]?.type === 'image') break;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    assert.equal(render.content[0].type, 'image', 'the panel shared its render');
    assert.equal(render.content[0].mimeType, 'image/png');
    writeFileSync(`${evidence}/agent-live-2-agent-view.png`, Buffer.from(render.content[0].data, 'base64'));

    // The agent listens; the person pins a comment to a card on the draft.
    const listening = mcp('await_feedback', {session_id:session.session_id, since:0});
    await panel.getByText('Agent · Waiting for feedback', {exact:true}).waitFor();
    await panel.getByText('Comment', {exact:true}).first().click({force:true});
    // Section headings are drawn by the dashboard itself: the first card with Comment is the light.
    await panel.getByText(/^About light\.wall_light \(.*card 2\)/).waitFor();
    await typeInto('Tell the agent what to change', 'Yes, side by side');
    await panel.getByText('Send', {exact:true}).click({force:true});
    const feedback = await listening;
    assert.equal(feedback.feedback[0].text, 'Yes, side by side');
    assert.match(feedback.feedback[0].target, /^light\.wall_light \(.*card 2\)$/,
      'the comment names the card it was left on');
    await panel.getByText('Yes, side by side', {exact:true}).waitFor();
    await page.screenshot({path:`${evidence}/agent-live-3-comment.png`});

    // The person applies; the waiting agent hears that it's done.
    const done = mcp('await_feedback', {session_id:session.session_id, since:feedback.cursor});
    await panel.getByText('Apply', {exact:true}).click({force:true});
    assert.equal((await done).status, 'applied');
    assert.deepEqual(await ws({type:'lovelace/config', url_path:'live-fixture'}), DRAFT);
    await page.screenshot({path:`${evidence}/agent-live-4-applied.png`});
    assert.deepEqual(errors, [], 'no page errors');
    await setMcpUser(api, headers, 'off');
  } catch (error) {
    await page.screenshot({path:`${evidence}/agent-live-failure.png`}).catch(() => {});
    const text = await page.frameLocator('terrazzo-panel iframe').locator('body').innerText({timeout:2000}).catch(() => '<unavailable>');
    console.error('Live agent diagnostics:', JSON.stringify({errors, text}));
    throw error;
  } finally { await browser.close(); await api.dispose(); }
});
