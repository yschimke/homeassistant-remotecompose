// CI-only, after installed-agent-live.test.mjs: a cloud agent edit, end to end. A real design
// server (DESIGN_SERVER_BIN, started here) relays between the real panel in a real Home Assistant
// and an agent that only reaches the design server. Setup happens once: the person connects the
// agent in Settings. After that a request goes straight to the listening agent with nothing to
// copy; it drafts, the person comments on a card and applies. The design server is restarted
// mid-session, which neither side should notice beyond a pause.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const { chromium, request } = await import(`${root}embed/node_modules/playwright/index.mjs`);
const base = 'http://127.0.0.1:8123';
const client_id = `${base}/`;
const evidence = `${root}build/ha-plugin-evidence`;
mkdirSync(evidence, { recursive: true });
const serverBin = process.env.DESIGN_SERVER_BIN;
const designPort = 8090;
const design = `http://127.0.0.1:${designPort}`;

async function signIn(api, username, password) {
  const flow = await (await api.post('/auth/login_flow', {data:{client_id, handler:['homeassistant', null], redirect_uri:client_id}})).json();
  const login = await (await api.post(`/auth/login_flow/${flow.flow_id}`, {data:{client_id, username, password}})).json();
  assert.equal(login.type, 'create_entry', `${username} signs in`);
  return (await api.post('/auth/token', {form:{client_id, code:login.result, grant_type:'authorization_code'}})).json();
}

/** A design server with an empty library and sessions kept in [sessions]; resolves once it answers. */
async function startDesignServer(library, sessions) {
  const server = spawn(serverBin, [library], {
    env:{...process.env, PORT:String(designPort), HOST:'127.0.0.1', AGENT_SESSIONS_DIR:sessions},
    stdio:['ignore', 'ignore', 'inherit'],
  });
  for (let i = 0; ; i++) {
    const up = await fetch(`${design}/healthz`).then((r) => r.ok, () => false);
    if (up) return server;
    assert.ok(i < 120, 'the design server starts');
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

async function stop(server) {
  if (server.exitCode !== null) return;
  const exited = new Promise((resolve) => server.once('exit', resolve));
  server.kill('SIGTERM');
  await exited;
}

const BASELINE = {title:'Cloud fixture', views:[{title:'Home', type:'sections', sections:[
  {type:'grid', cards:[{type:'heading', heading:'Lights'}, {type:'tile', entity:'light.wall_light'}]}]}]};
const DRAFT = {title:'Cloud fixture', views:[{title:'Home', type:'sections', sections:[
  {type:'grid', cards:[
    {type:'heading', heading:'Lights'},
    {type:'tile', entity:'light.wall_light', grid_options:{columns:6}},
    {type:'tile', entity:'input_boolean.wall_light', grid_options:{columns:6}}]}]}]};

test('Cloud agent edit: joined by code, relayed through a restart, applied', {skip:!serverBin && 'set DESIGN_SERVER_BIN', timeout:300_000}, async () => {
  const library = mkdtempSync(join(tmpdir(), 'design-library-'));
  writeFileSync(join(library, 'manifest.json'),
    JSON.stringify({schema:1, serial:1, generatedAtEpochMs:0, expiresAtEpochMs:4102444800000, designs:[]}));
  const sessions = mkdtempSync(join(tmpdir(), 'agent-sessions-'));
  let server = await startDesignServer(library, sessions);
  const api = await request.newContext({baseURL:base});
  const browser = await chromium.launch();
  const page = await browser.newPage({viewport:{width:1200, height:900}, deviceScaleFactor:1});
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    const ownerTokens = await signIn(api, 'panel', 'fixture-only-password');
    await page.addInitScript(({tokens, base}) => localStorage.setItem('hassTokens', JSON.stringify({
      ...tokens, hassUrl:base, clientId:`${base}/`, expires:Date.now() + tokens.expires_in * 1000,
    })), {tokens:ownerTokens, base});
    // The panel's design server is https://ha.coo.ee: send it to the local one, and check that no
    // Home Assistant credential ever leaves for it.
    await page.route('https://ha.coo.ee/**', async (route) => {
      const url = new URL(route.request().url());
      const auth = route.request().headers().authorization ?? '';
      assert.ok(!auth.includes(ownerTokens.access_token), 'the HA token stays with Home Assistant');
      try {
        const response = await route.fetch({url:`${design}${url.pathname}${url.search}`, timeout:60_000});
        await route.fulfill({response});
      } catch {
        await route.abort('connectionrefused').catch(() => {});
      }
    });
    await page.goto(base);
    await page.waitForFunction(() => document.querySelector('home-assistant')?.hass?.connection);
    const ws = (message) => page.evaluate((message) =>
      document.querySelector('home-assistant').hass.callWS(message).catch((e) => ({error:{code:e.code, message:e.message}})), message);
    assert.ok(!(await ws({type:'lovelace/dashboards/create', url_path:'cloud-fixture', title:'Cloud fixture',
      mode:'storage', require_admin:false, show_in_sidebar:false}))?.error);
    assert.ok(!(await ws({type:'lovelace/config/save', url_path:'cloud-fixture', config:BASELINE}))?.error);

    // The agent knows only the design server; it never reaches Home Assistant.
    const agent = async (name, args = {}) => {
      const response = await fetch(`${design}/api/agent/mcp`, {method:'POST',
        body:JSON.stringify({jsonrpc:'2.0', id:1, method:'tools/call', params:{name, arguments:args}})});
      assert.equal(response.status, 200, `${name} answers`);
      const result = (await response.json()).result;
      assert.notEqual(result.isError, true, `${name}: ${result.content?.[0]?.text}`);
      return result.structuredContent;
    };

    const panel = page.frameLocator('terrazzo-panel iframe');
    const typeInto = async (label, text) => {
      await panel.getByRole('textbox', {name:label}).click({force:true});
      await page.waitForTimeout(400);
      await page.keyboard.type(text, {delay:20});
    };
    await page.goto(`${base}/terrazzo?dashboard=cloud-fixture`);
    await panel.getByText('Lights', {exact:true}).first().waitFor({timeout:90_000});

    // Setup, once: Settings, Connect an agent; the agent connects with the code and listens.
    await panel.getByRole('button', {name:'Settings'}).click({force:true});
    await panel.getByText('Connect an agent', {exact:true}).click({force:true});
    const codeNode = panel.getByText(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
    await codeNode.waitFor({timeout:30_000});
    const setupCode = (await codeNode.innerText()).trim();
    await page.screenshot({path:`${evidence}/agent-cloud-0-setup.png`});
    const {link} = await agent('connect', {code:setupCode, agentName:'Claude'});
    assert.ok(link, 'a setup code gives the agent a lasting link');
    let waitingForRequest = agent('await_request', {link, since:0});
    await panel.getByText('Claude is connected and listening', {exact:true}).waitFor({timeout:30_000});
    await page.screenshot({path:`${evidence}/agent-cloud-1-connected.png`});

    // From now on a request goes straight to the agent: no code, nothing to copy.
    await page.goto(`${base}/terrazzo?dashboard=cloud-fixture`);
    await panel.getByText('Lights', {exact:true}).first().waitFor({timeout:90_000});
    await panel.getByRole('button', {name:'More options'}).click({force:true});
    await panel.getByText('Ask an agent', {exact:true}).click({force:true});
    await panel.getByText('Use a cloud agent', {exact:true}).click({force:true});
    await panel.getByText('Claude is connected and listening', {exact:true}).waitFor({timeout:30_000});
    await typeInto('Request', 'Add the switch next to the light');
    await panel.getByText('Start edit session', {exact:true}).click({force:true});
    let inbox = await waitingForRequest;
    while (inbox.requests.length === 0) inbox = await agent('await_request', {link, since:inbox.cursor});
    const [delivered] = inbox.requests;
    assert.equal(delivered.kind, 'dashboard');
    const session = delivered.session;
    assert.equal(await panel.getByText(/^[A-Z2-9]{4}-[A-Z2-9]{4}$/).count(), 0, 'no code to hand over');
    const read = await agent('get_session', {session});
    assert.deepEqual(read.dashboard, BASELINE);
    assert.equal(read.request, 'Add the switch next to the light');
    await agent('set_status', {session, status:'Drafting the switch tile'});
    await panel.getByText('Claude · Drafting the switch tile', {exact:true}).waitFor();
    await agent('submit_draft', {session, revision:read.revision, dashboard:DRAFT, summary:'Switch beside the light'});
    await agent('send_message', {session, text:'Should the switch sit beside the light?'});
    // The draft reaches Home Assistant's edit session through the panel, as the person.
    await panel.getByText('Apply', {exact:true}).waitFor({timeout:30_000});
    await panel.getByText('Should the switch sit beside the light?', {exact:true}).waitFor();
    await page.screenshot({path:`${evidence}/agent-cloud-2-draft.png`});
    // The agent sees the draft exactly as the person's panel drew it.
    let render;
    for (let i = 0; i < 30; i++) {
      const response = await fetch(`${design}/api/agent/mcp`, {method:'POST',
        body:JSON.stringify({jsonrpc:'2.0', id:1, method:'tools/call', params:{name:'get_render', arguments:{session}}})});
      render = (await response.json()).result;
      if (render.content?.[0]?.type === 'image') break;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    assert.equal(render.content[0].type, 'image', 'the panel shared its render');
    writeFileSync(`${evidence}/agent-cloud-2-agent-view.png`, Buffer.from(render.content[0].data, 'base64'));

    // A deploy restarts the design server; the session, presence and conversation survive it.
    await new Promise((resolve) => setTimeout(resolve, 1_500)); // let the change be saved
    await stop(server);
    server = await startDesignServer(library, sessions);
    const resumed = await agent('get_session', {session});
    assert.equal(resumed.revision, 1, 'the draft survived the restart');

    const listening = agent('await_feedback', {session, since:resumed.cursor});
    await panel.getByText('Claude · Waiting for feedback', {exact:true}).waitFor({timeout:60_000});
    await panel.getByText('Comment', {exact:true}).first().click({force:true});
    await panel.getByText(/^About light\.wall_light \(.*card 2\)/).waitFor();
    await typeInto('Tell the agent what to change', 'Yes, side by side');
    await panel.getByText('Send', {exact:true}).click({force:true});
    const feedback = await listening;
    assert.equal(feedback.feedback[0].text, 'Yes, side by side');
    assert.match(feedback.feedback[0].target, /^light\.wall_light \(.*card 2\)$/);
    await page.screenshot({path:`${evidence}/agent-cloud-3-comment.png`});

    const done = agent('await_feedback', {session, since:feedback.cursor});
    await panel.getByText('Apply', {exact:true}).click({force:true});
    assert.equal((await done).decision, 'accepted', 'the agent hears the draft was applied');
    assert.deepEqual(await ws({type:'lovelace/config', url_path:'cloud-fixture'}), DRAFT);
    await page.screenshot({path:`${evidence}/agent-cloud-4-applied.png`});
    assert.deepEqual(errors, [], 'no page errors');
  } catch (error) {
    await page.screenshot({path:`${evidence}/agent-cloud-failure.png`}).catch(() => {});
    console.error('Cloud agent diagnostics:', JSON.stringify({errors}));
    throw error;
  } finally {
    await browser.close();
    await api.dispose();
    await stop(server);
  }
});
