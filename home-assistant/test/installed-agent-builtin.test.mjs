// CI-only, after the other installed tests: the integration's built-in dashboard agent inside a
// real HA container. The options name an OpenAI-compatible service (a fake one this test serves,
// which scripts the model's tool calls); a person starts a session building a new dashboard; Home
// Assistant calls the model itself, runs its tools as that person and posts its draft and reply;
// applying the draft creates the dashboard with Lovelace's own command, and undo deletes it.
//
// The container reaches this test's fake model at HA_TEST_HOST (the Docker bridge's gateway by
// default), so the fake listens on every interface.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

const base = 'http://127.0.0.1:8123';
const client_id = `${base}/`;
const hostFromContainer = process.env.HA_TEST_HOST || '172.17.0.1';

async function json(response) {
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${response.status}: ${text}`);
  }
}

/** Tokens for [username], onboarding the instance first when nothing has yet. */
async function signIn(username, password, name) {
  const post = (path, body, headers = {}) => fetch(`${base}${path}`, {
    method:'POST', headers:{'Content-Type':'application/json', ...headers}, body:JSON.stringify(body)});
  const form = (path, body) => fetch(`${base}${path}`, {method:'POST', body:new URLSearchParams(body)});
  const onboarding = await json(await fetch(`${base}/api/onboarding`));
  if (onboarding.some((step) => step.step === 'user' && !step.done)) {
    const {auth_code} = await json(await post('/api/onboarding/users', {client_id, name, username, password, language:'en'}));
    const tokens = await json(await form('/auth/token', {client_id, code:auth_code, grant_type:'authorization_code'}));
    const headers = {Authorization:`Bearer ${tokens.access_token}`};
    for (const step of ['core_config', 'analytics']) await post(`/api/onboarding/${step}`, {}, headers);
    await post('/api/onboarding/integration', {client_id, redirect_uri:client_id}, headers);
    return tokens;
  }
  const flow = await json(await post('/auth/login_flow', {client_id, handler:['homeassistant', null], redirect_uri:client_id}));
  const login = await json(await post(`/auth/login_flow/${flow.flow_id}`, {client_id, username, password}));
  assert.equal(login.type, 'create_entry', `${username} signs in`);
  return json(await form('/auth/token', {client_id, code:login.result, grant_type:'authorization_code'}));
}

/** One authenticated WebSocket: `call` runs a command, `subscribe` collects its events. */
async function connect(accessToken) {
  const socket = new WebSocket(`${base.replace('http', 'ws')}/api/websocket`);
  let next = 1;
  const pending = new Map();
  const listeners = new Map();
  await new Promise((resolve, reject) => {
    socket.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.type === 'auth_required') socket.send(JSON.stringify({type:'auth', access_token:accessToken}));
      else if (message.type === 'auth_ok') resolve();
      else if (message.type === 'auth_invalid') reject(new Error('auth_invalid'));
      else if (message.type === 'result') pending.get(message.id)?.(message);
      else if (message.type === 'event') listeners.get(message.id)?.(message.event);
    };
    socket.onerror = reject;
  });
  const send = (command) => {
    const id = next++;
    socket.send(JSON.stringify({...command, id}));
    return id;
  };
  return {
    call: (command) => new Promise((resolve) => {
      pending.set(send(command), (m) => resolve(m.success ? m.result : {error:m.error}));
    }),
    /** Resolves with the first event [matches] accepts, within [ms]. */
    until(command, matches, ms = 60_000) {
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`no matching event for ${command.type}`)), ms);
        const id = send(command);
        let last;
        listeners.set(id, (event) => {
          last = event;
          if (matches(event)) {
            clearTimeout(timer);
            listeners.delete(id);
            socket.send(JSON.stringify({id:next++, type:'unsubscribe_events', subscription:id}));
            resolve(event);
          }
        });
        pending.set(id, (m) => { if (!m.success) reject(new Error(JSON.stringify(m.error))); });
        timer.unref?.();
        void last;
      });
    },
    close: () => socket.close(),
  };
}

/**
 * A fake OpenAI-compatible model: asked anything, it looks the wall light up, drafts a dashboard
 * with it, then says it's done. Keeps every request it gets.
 */
function fakeModel() {
  const requests = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      const request = {path:req.url, headers:req.headers, body:JSON.parse(body || '{}')};
      requests.push(request);
      const messages = request.body.messages || [];
      const last = messages[messages.length - 1] || {};
      let message;
      if (last.role === 'tool' && last.name === 'submit_draft') {
        message = {role:'assistant', content:last.content.includes('error')
          ? `The draft was refused: ${last.content}` : 'I built a garden dashboard with the wall light.'};
      } else if (last.role === 'tool') {
        const config = {views:[{title:'Garden', type:'sections', sections:[{type:'grid', cards:[
          {type:'heading', heading:'Lights'}, {type:'tile', entity:'light.wall_light'}]}]}]};
        message = {role:'assistant', content:null, tool_calls:[{id:'call-2', type:'function', function:{
          name:'submit_draft', arguments:JSON.stringify({config_json:JSON.stringify(config), summary:'Garden with the wall light'})}}]};
      } else {
        message = {role:'assistant', content:null, tool_calls:[{id:'call-1', type:'function', function:{
          name:'find_entities', arguments:JSON.stringify({query:'wall light'})}}]};
      }
      res.writeHead(200, {'Content-Type':'application/json'});
      res.end(JSON.stringify({choices:[{index:0, message, finish_reason:message.tool_calls ? 'tool_calls' : 'stop'}]}));
    });
  });
  return new Promise((resolve) => server.listen(0, '0.0.0.0', () => resolve({server, requests, port:server.address().port})));
}

async function setOptions(headers, agent) {
  const [entry] = await json(await fetch(`${base}/api/config/config_entries/entry?domain=terrazzo`, {headers}));
  const post = (path, body) => fetch(`${base}${path}`, {method:'POST', headers:{...headers, 'Content-Type':'application/json'}, body:JSON.stringify(body)});
  const form = await json(await post('/api/config/config_entries/options/flow', {handler:entry.entry_id}));
  const saved = await json(await post(`/api/config/config_entries/options/flow/${form.flow_id}`,
    {title:'Terrazzo', dashboard:'wall-panel', mcp_user:'off', ...agent}));
  assert.equal(saved.type, 'create_entry', JSON.stringify(saved));
}

test('The built-in agent builds a new dashboard; applying creates it', {timeout:240_000}, async () => {
  const model = await fakeModel();
  const tokens = await signIn('panel', 'fixture-only-password', 'Test panel user');
  const headers = {Authorization:`Bearer ${tokens.access_token}`};
  for (let i = 0; ; i++) {
    const config = await json(await fetch(`${base}/api/config`, {headers}));
    const entries = await json(await fetch(`${base}/api/config/config_entries/entry?domain=terrazzo`, {headers}));
    if (config.state === 'RUNNING' && entries.length === 1 && entries[0].state === 'loaded') break;
    assert.ok(i < 120, 'Home Assistant finishes starting');
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  try {
    // An API key is required while the agent is on.
    {
      const [entry] = await json(await fetch(`${base}/api/config/config_entries/entry?domain=terrazzo`, {headers}));
      const flow = await json(await fetch(`${base}/api/config/config_entries/options/flow`, {method:'POST', headers:{...headers, 'Content-Type':'application/json'}, body:JSON.stringify({handler:entry.entry_id})}));
      const refused = await json(await fetch(`${base}/api/config/config_entries/options/flow/${flow.flow_id}`, {method:'POST', headers:{...headers, 'Content-Type':'application/json'},
        body:JSON.stringify({title:'Terrazzo', dashboard:'wall-panel', mcp_user:'off', agent_provider:'gemini'})}));
      assert.equal(refused.type, 'form');
      assert.equal(refused.errors.agent_api_key, 'agent_key_required');
    }
    await setOptions(headers, {
      agent_provider:'openai_compatible', agent_api_key:'test-key', agent_model:'fake-model',
      agent_base_url:`http://${hostFromContainer}:${model.port}/v1`,
    });
    let ws;
    for (let i = 0; ; i++) {
      ws = await connect(tokens.access_token);
      const info = await ws.call({type:'terrazzo/agent/info'});
      if (info.enabled) {
        assert.deepEqual(info, {enabled:true, provider:'openai_compatible', provider_label:'Other OpenAI-compatible service', model:'fake-model'});
        break;
      }
      ws.close();
      assert.ok(i < 40, 'the reloaded entry turns the agent on');
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    // A blank key keeps the saved one.
    await setOptions(headers, {
      agent_provider:'openai_compatible', agent_model:'fake-model',
      agent_base_url:`http://${hostFromContainer}:${model.port}/v1`,
    });
    await new Promise((resolve) => setTimeout(resolve, 1500));
    ws.close();
    ws = await connect(tokens.access_token);
    assert.equal((await ws.call({type:'terrazzo/agent/info'})).enabled, true);

    const started = await ws.call({type:'terrazzo/edit/start', request:'A garden dashboard with the wall light',
      scope:{kind:'new_dashboard', title:'Garden test'}});
    assert.ok(!started.error, JSON.stringify(started.error));
    const session = started.session;
    assert.equal(session.scope.kind, 'new_dashboard');
    assert.equal(session.url_path, 'garden-test');
    // Listed as a dashboard being built, and refused nowhere for being one Home Assistant lacks.
    const building = await ws.call({type:'terrazzo/edit/list', new_dashboards:true});
    assert.ok(building.sessions.some((s) => s.session_id === session.session_id));

    const answered = await ws.until({type:'terrazzo/edit/subscribe', session_id:session.session_id},
      (event) => event.session.drafts.length > 0 && event.session.messages.length > 0);
    assert.equal(answered.session.drafts[0].summary, 'Garden with the wall light');
    assert.equal(answered.session.messages.at(-1).text, 'I built a garden dashboard with the wall light.');
    assert.equal(answered.session.assistant_error, null);

    // The model was asked with the saved key, the tools and the request; the key went nowhere else.
    const first = model.requests[0];
    assert.equal(first.path, '/v1/chat/completions');
    assert.equal(first.headers.authorization, 'Bearer test-key');
    assert.equal(first.body.model, 'fake-model');
    assert.ok(first.body.tools.some((t) => t.function.name === 'submit_draft'));
    assert.match(first.body.messages[0].content, /NEW dashboard titled 'Garden test'/);
    assert.equal(first.body.messages.at(-1).content, 'A garden dashboard with the wall light');
    assert.match(model.requests[1].body.messages.at(-1).content, /light\.wall_light/);

    // Applying creates the dashboard with the draft as its config.
    const applied = await ws.call({type:'terrazzo/edit/apply', session_id:session.session_id, draft_id:answered.session.drafts[0].id});
    assert.ok(!applied.error, JSON.stringify(applied.error));
    assert.equal(applied.session.status, 'applied');
    const dashboards = await ws.call({type:'lovelace/dashboards/list'});
    const created = dashboards.find((d) => d.url_path === 'garden-test');
    assert.ok(created, 'the dashboard exists');
    assert.equal(created.title, 'Garden test');
    const saved = await ws.call({type:'lovelace/config', url_path:'garden-test'});
    assert.equal(saved.views[0].sections[0].cards[1].entity, 'light.wall_light');

    // A note on an existing dashboard's session runs another turn.
    const reopened = await ws.call({type:'terrazzo/edit/start', url_path:'garden-test', request:'Work on this with me.'});
    const greeted = await ws.until({type:'terrazzo/edit/subscribe', session_id:reopened.session.session_id},
      (event) => event.session.drafts.length > 0 && event.session.messages.length > 0);
    assert.equal(greeted.session.url_path, 'garden-test');
    await ws.call({type:'terrazzo/edit/discard', session_id:reopened.session.session_id});

    // Undo deletes the dashboard it created.
    const undone = await ws.call({type:'terrazzo/edit/undo', session_id:session.session_id});
    assert.ok(!undone.error, JSON.stringify(undone.error));
    assert.ok(!(await ws.call({type:'lovelace/dashboards/list'})).some((d) => d.url_path === 'garden-test'));
    ws.close();
  } finally {
    await setOptions(headers, {agent_provider:'off'});
    model.server.close();
  }
});
