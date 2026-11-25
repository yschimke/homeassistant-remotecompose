// A fake Ollama server, and Home Assistant's Ollama integration pointed at it: one of Home
// Assistant's own conversation agents whose model is a script. Shared by the installed tests that
// need Terrazzo's dashboard agent.
import assert from 'node:assert/strict';
import http from 'node:http';

export const MODEL = 'fake:latest';
// The container reaches a server on this machine at HA_TEST_HOST (the Docker bridge's gateway).
const hostFromContainer = process.env.HA_TEST_HOST || '172.17.0.1';

/**
 * Serves Ollama's `/api/tags` and `/api/chat`. Each chat request is answered by
 * [reply]({messages, system, tool, step}), which returns the assistant message: `tool(name)` is the
 * name the model was offered for [name], and `step` how many tool results follow the person's
 * newest message. Keeps every chat request in `chats`.
 */
export function fakeOllama(reply) {
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
      const lastUser = messages.map((m) => m.role).lastIndexOf('user');
      const step = messages.slice(lastUser).filter((m) => m.role === 'tool').length;
      let message;
      try {
        message = reply({messages, system, tool, step});
      } catch (failure) {
        // An answer, so Home Assistant never waits on a script that threw.
        message = {role:'assistant', content:`The fake model failed: ${failure}`};
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

/** A JSON call against Home Assistant's REST API at [base] with [headers]. */
export const restApi = (base, headers) => async (path, body) => {
  const response = await fetch(`${base}${path}`, {method:body === undefined ? 'GET' : 'POST',
    headers:{...headers, 'Content-Type':'application/json'}, body:body === undefined ? undefined : JSON.stringify(body)});
  const text = await response.text();
  try { return JSON.parse(text); } catch { throw new Error(`${response.status}: ${text}`); }
};

/**
 * An Ollama entry at the fake server on [port] with one conversation agent named [name] using
 * [apis]: `{entryId, agentId}`. [agents] lists the conversation agents (`conversation/agent/list`).
 */
export async function addOllamaAgent(call, port, name, apis, agents) {
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
  const agent = await call(`/api/config/config_entries/subentries/flow/${options.flow_id}`, {name, model:MODEL, llm_hass_api:apis});
  assert.equal(agent.type, 'create_entry', JSON.stringify(agent));
  const slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '_');
  for (let i = 0; ; i++) {
    const id = (await agents()).find((a) => a.name === name || a.id.endsWith(slug))?.id;
    if (id) return {entryId, agentId:id};
    assert.ok(i < 40, `${name} appears`);
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

/** Terrazzo's options with [agent] as its dashboard agent (or none). */
export async function setDashboardAgent(call, agent) {
  const [entry] = await call('/api/config/config_entries/entry?domain=terrazzo');
  const form = await call('/api/config/config_entries/options/flow', {handler:entry.entry_id});
  const saved = await call(`/api/config/config_entries/options/flow/${form.flow_id}`,
    {title:'Terrazzo', dashboard:'wall-panel', mcp_user:'off', ...(agent ? {agent} : {})});
  assert.equal(saved.type, 'create_entry', JSON.stringify(saved));
}
