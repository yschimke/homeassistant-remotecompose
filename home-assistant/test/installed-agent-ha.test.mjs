// CI-only, after the other installed tests: one of Home Assistant's own conversation agents answers
// Terrazzo's edit sessions. It is a real Ollama agent (Home Assistant's Ollama integration), with
// "Terrazzo dashboards" on among its APIs, talking to a fake Ollama server this test serves, which
// scripts the model's tool calls. A person starts a session building a new dashboard; Terrazzo
// hands the request to the agent as that person; the agent reads the session, finds the entity and
// drafts through the dashboard tools; its reply and steps land in the session; applying the draft
// creates the dashboard with Lovelace's own command, and undo deletes it.
//
// The container reaches this test's fake server at HA_TEST_HOST (the Docker bridge's gateway by
// default), so the fake listens on every interface.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

const base = 'http://127.0.0.1:8123';
const client_id = `${base}/`;
const hostFromContainer = process.env.HA_TEST_HOST || '172.17.0.1';
const MODEL = 'fake:latest';

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
 * A fake Ollama server with one model. Asked to chat, it reads the edit session it was told about,
 * looks the wall light up, drafts a dashboard with it, then says it's done: one step per request,
 * by how many tool results the conversation already has. Keeps every chat request it gets.
 */
function fakeOllama() {
  const chats = [];
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      const send = (status, value, type = 'application/json') => {
        res.writeHead(status, {'Content-Type':type});
        res.end(typeof value === 'string' ? value : JSON.stringify(value));
      };
      if (req.url === '/api/tags') {
        return send(200, {models:[{name:MODEL, model:MODEL, modified_at:'2026-01-01T00:00:00Z', size:1, digest:'fake',
          details:{format:'gguf', family:'fake', parameter_size:'1B', quantization_level:'Q4'}}]});
      }
      if (req.url !== '/api/chat') return send(404, {error:'not found'});
      const request = JSON.parse(body || '{}');
      chats.push(request);
      const messages = request.messages || [];
      const tools = (request.tools || []).map((t) => t.function.name);
      const tool = (suffix) => tools.find((name) => name === suffix || name.endsWith(`__${suffix}`)) || suffix;
      const system = messages.filter((m) => m.role === 'system').map((m) => m.content).join('\n');
      const sessionId = (system.match(/session_id is ([A-Za-z0-9_-]+)/) || [])[1];
      // Results since the person's newest message: each turn starts its own three steps.
      const lastUser = messages.map((m) => m.role).lastIndexOf('user');
      const step = messages.slice(lastUser).filter((m) => m.role === 'tool').length;
      let message;
      if (step === 0) {
        message = {role:'assistant', content:'', tool_calls:[{function:{name:tool('get_edit_session'), arguments:{session_id:sessionId}}}]};
      } else if (step === 1) {
        message = {role:'assistant', content:'', tool_calls:[{function:{name:tool('find_entities'), arguments:{query:'wall light'}}}]};
      } else if (step === 2) {
        const config = {views:[{title:'Garden', type:'sections', sections:[{type:'grid', cards:[
          {type:'heading', heading:'Lights'}, {type:'tile', entity:'light.wall_light'}]}]}]};
        message = {role:'assistant', content:'', tool_calls:[{function:{name:tool('submit_draft'),
          arguments:{session_id:sessionId, config, summary:'Garden with the wall light'}}}]};
      } else {
        const last = messages.at(-1).content || '';
        message = {role:'assistant', content:last.includes('"error": true') || last.includes('"error":true')
          ? `The draft was refused: ${last}` : 'I built a garden dashboard with the wall light.'};
      }
      const at = new Date().toISOString();
      send(200, [
        JSON.stringify({model:MODEL, created_at:at, message, done:false}),
        JSON.stringify({model:MODEL, created_at:at, message:{role:'assistant', content:''}, done:true, done_reason:'stop'}),
      ].join('\n') + '\n', 'application/x-ndjson');
    });
  });
  return new Promise((resolve) => server.listen(0, '0.0.0.0', () => resolve({server, chats, port:server.address().port})));
}

const api = (headers) => async (path, body) => json(await fetch(`${base}${path}`, {method:body === undefined ? 'GET' : 'POST',
  headers:{...headers, 'Content-Type':'application/json'}, body:body === undefined ? undefined : JSON.stringify(body)}));

/** Terrazzo's options, with [agent] as the dashboard agent (or none). */
async function setOptions(headers, agent) {
  const call = api(headers);
  const [entry] = await call('/api/config/config_entries/entry?domain=terrazzo');
  const form = await call('/api/config/config_entries/options/flow', {handler:entry.entry_id});
  assert.ok(form.data_schema.some((field) => field.name === 'agent'), JSON.stringify(form.data_schema));
  assert.ok(!form.data_schema.some((field) => field.name.startsWith('agent_')), 'no built-in agent fields');
  const saved = await call(`/api/config/config_entries/options/flow/${form.flow_id}`,
    {title:'Terrazzo', dashboard:'wall-panel', mcp_user:'off', ...(agent ? {agent} : {})});
  assert.equal(saved.type, 'create_entry', JSON.stringify(saved));
}

/** An Ollama entry at the fake server with one conversation agent using only Terrazzo's API. */
async function addOllamaAgent(headers, port) {
  const call = api(headers);
  const form = await call('/api/config/config_entries/flow', {handler:'ollama'});
  assert.equal(form.type, 'form', JSON.stringify(form));
  const created = await call(`/api/config/config_entries/flow/${form.flow_id}`, {url:`http://${hostFromContainer}:${port}`});
  assert.equal(created.type, 'create_entry', JSON.stringify(created));
  const entryId = created.result.entry_id;
  for (let i = 0; ; i++) {
    const [entry] = (await call('/api/config/config_entries/entry?domain=ollama')).filter((e) => e.entry_id === entryId);
    if (entry?.state === 'loaded') break;
    assert.ok(i < 60, `the Ollama entry loads: ${JSON.stringify(entry)}`);
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  const options = await call('/api/config/config_entries/subentries/flow', {handler:[entryId, 'conversation']});
  assert.equal(options.type, 'form', JSON.stringify(options));
  const offered = options.data_schema.find((field) => field.name === 'llm_hass_api');
  assert.ok(JSON.stringify(offered).includes('terrazzo_dashboards'), `Terrazzo dashboards is offered: ${JSON.stringify(offered)}`);
  const agent = await call(`/api/config/config_entries/subentries/flow/${options.flow_id}`,
    {name:'Garden agent', model:MODEL, llm_hass_api:['terrazzo_dashboards']});
  assert.equal(agent.type, 'create_entry', JSON.stringify(agent));
  return entryId;
}

/** A second agent in the same Ollama entry, without Terrazzo's API: its id. */
async function addPlainAgent(headers, entryId) {
  const call = api(headers);
  const options = await call('/api/config/config_entries/subentries/flow', {handler:[entryId, 'conversation']});
  const agent = await call(`/api/config/config_entries/subentries/flow/${options.flow_id}`, {name:'Plain agent', model:MODEL});
  assert.equal(agent.type, 'create_entry', JSON.stringify(agent));
  for (let i = 0; ; i++) {
    const ws = await connect(headers.Authorization.slice('Bearer '.length));
    const {agents} = await ws.call({type:'conversation/agent/list'});
    ws.close();
    const id = agents.find((a) => a.name === 'Plain agent' || a.id.endsWith('plain_agent'))?.id;
    if (id) return id;
    assert.ok(i < 40, 'the plain agent appears');
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

test("Home Assistant's own conversation agent builds a new dashboard; applying creates it", {timeout:300_000}, async () => {
  const ollama = await fakeOllama();
  const tokens = await signIn('panel', 'fixture-only-password', 'Test panel user');
  const headers = {Authorization:`Bearer ${tokens.access_token}`};
  const call = api(headers);
  for (let i = 0; ; i++) {
    const config = await call('/api/config');
    const entries = await call('/api/config/config_entries/entry?domain=terrazzo');
    if (config.state === 'RUNNING' && entries.length === 1 && entries[0].state === 'loaded') break;
    assert.ok(i < 120, 'Home Assistant finishes starting');
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  let ollamaEntry;
  try {
    // No agent chosen: none answers.
    await setOptions(headers);
    let ws = await connect(tokens.access_token);
    // Nothing answers, and the apps can point an administrator at Terrazzo's settings.
    assert.deepEqual(await ws.call({type:'terrazzo/agent/info'}),
      {enabled:false, agent_id:null, name:null, setup:'agent', setup_path:'/config/integrations/integration/terrazzo'});
    ws.close();

    ollamaEntry = await addOllamaAgent(headers, ollama.port);
    let agentId;
    for (let i = 0; !agentId; i++) {
      ws = await connect(tokens.access_token);
      const {agents} = await ws.call({type:'conversation/agent/list'});
      agentId = agents.find((a) => a.name === 'Garden agent' || a.id.endsWith('garden_agent'))?.id;
      ws.close();
      assert.ok(agentId || i < 40, 'the Ollama agent appears');
      if (!agentId) await new Promise((resolve) => setTimeout(resolve, 500));
    }
    await setOptions(headers, agentId);
    for (let i = 0; ; i++) {
      ws = await connect(tokens.access_token);
      const info = await ws.call({type:'terrazzo/agent/info'});
      if (info.enabled) {
        assert.deepEqual(info, {enabled:true, agent_id:agentId, name:'Garden agent', setup:null, setup_path:null});
        break;
      }
      ws.close();
      assert.ok(i < 40, 'the reloaded entry turns the agent on');
      await new Promise((resolve) => setTimeout(resolve, 500));
    }

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

    // Each tool the agent called is a step in the chat, in words.
    assert.deepEqual(answered.session.steps.map((s) => s.tool), ['get_edit_session', 'find_entities', 'submit_draft']);
    assert.ok(answered.session.steps.every((s) => s.ok), JSON.stringify(answered.session.steps));

    // The agent was asked as the person, with Terrazzo's tools, the API's prompt, this session and
    // its scope, and the request as the person's message.
    const first = ollama.chats[0];
    assert.equal(first.model, MODEL);
    assert.ok(first.tools.some((t) => t.function.name.endsWith('submit_draft')), JSON.stringify(first.tools.map((t) => t.function.name)));
    const system = first.messages.filter((m) => m.role === 'system').map((m) => m.content).join('\n');
    assert.match(system, /Terrazzo's dashboard tools/);
    assert.match(system, /NEW dashboard titled 'Garden test'/);
    assert.ok(system.includes(session.session_id), 'the session it answers');
    assert.equal(first.messages.at(-1).content, 'A garden dashboard with the wall light');
    assert.match(ollama.chats[2].messages.at(-1).content, /light\.wall_light/);

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

    // A session on an existing dashboard gets a turn too, with no memory of the first.
    const reopened = await ws.call({type:'terrazzo/edit/start', url_path:'garden-test', request:'Work on this with me.'});
    const greeted = await ws.until({type:'terrazzo/edit/subscribe', session_id:reopened.session.session_id},
      (event) => event.session.drafts.length > 0 && event.session.messages.length > 0);
    assert.equal(greeted.session.url_path, 'garden-test');
    await ws.call({type:'terrazzo/edit/discard', session_id:reopened.session.session_id});

    // Undo deletes the dashboard it created.
    const undone = await ws.call({type:'terrazzo/edit/undo', session_id:session.session_id});
    assert.ok(!undone.error, JSON.stringify(undone.error));
    assert.ok(!(await ws.call({type:'lovelace/dashboards/list'})).some((d) => d.url_path === 'garden-test'));

    // An agent without "Terrazzo dashboards": a repair says how to turn it on, the apps learn where,
    // and the chat says so instead of asking the model.
    const plainId = await addPlainAgent(headers, ollamaEntry);
    ws.close();
    await setOptions(headers, plainId);
    let plainInfo;
    for (let i = 0; ; i++) {
      ws = await connect(tokens.access_token);
      plainInfo = await ws.call({type:'terrazzo/agent/info'});
      if (plainInfo.agent_id === plainId) break;
      ws.close();
      assert.ok(i < 40, 'the reloaded entry chooses the plain agent');
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    assert.deepEqual(plainInfo, {enabled:true, agent_id:plainId, name:'Plain agent', setup:'tools',
      setup_path:'/config/integrations/integration/ollama'});
    const {issues} = await ws.call({type:'repairs/list_issues'});
    const noTools = issues.find((issue) => issue.domain === 'terrazzo' && issue.issue_id === 'agent_no_tools');
    assert.ok(noTools, JSON.stringify(issues));
    assert.equal(noTools.translation_placeholders.agent, 'Plain agent');
    assert.match(noTools.learn_more_url, /domain=ollama$/);
    const chatsBefore = ollama.chats.length;
    const plain = await ws.call({type:'terrazzo/edit/start', request:'A plain dashboard',
      scope:{kind:'new_dashboard', title:'Plain test'}});
    assert.ok(!plain.error, JSON.stringify(plain.error));
    const refused = await ws.until({type:'terrazzo/edit/subscribe', session_id:plain.session.session_id},
      (event) => event.session.messages.length > 0);
    assert.match(refused.session.messages.at(-1).text, /doesn't have "Terrazzo dashboards" turned on/);
    assert.equal(ollama.chats.length, chatsBefore, 'the model is not asked');
    await ws.call({type:'terrazzo/edit/discard', session_id:plain.session.session_id});

    // Choosing the agent with the tools clears the repair.
    await setOptions(headers, agentId);
    for (let i = 0; ; i++) {
      ws.close();
      ws = await connect(tokens.access_token);
      if ((await ws.call({type:'terrazzo/agent/info'})).agent_id === agentId) break;
      assert.ok(i < 40, 'the reloaded entry chooses the garden agent again');
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    const after = await ws.call({type:'repairs/list_issues'});
    assert.ok(!after.issues.some((issue) => issue.issue_id === 'agent_no_tools'), JSON.stringify(after.issues));
    ws.close();
  } finally {
    await setOptions(headers);
    if (ollamaEntry) await fetch(`${base}/api/config/config_entries/entry/${ollamaEntry}`, {method:'DELETE', headers});
    ollama.server.close();
  }
});
