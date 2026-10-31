// CI-only, last (it restarts Home Assistant): a Terrazzo page on another site signs in with HA's
// OAuth, the integration raises a repair for its origin, and fixing it changes HA's HTTP config,
// restarts and confirms it, after which HA lets that origin load images.
import { test } from 'node:test';
import assert from 'node:assert/strict';

const base = 'http://127.0.0.1:8123';
const local = `${base}/`;
const web = 'https://terrazzo-web.example.test';
const json = (body) => ({method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});

async function token(client_id, code) {
  const form = new URLSearchParams({client_id, code, grant_type: 'authorization_code'});
  return (await fetch(`${base}/auth/token`, {method: 'POST', body: form})).json();
}

/** Tokens for [username] issued to [client_id], onboarding the instance first when nothing has yet. */
async function signIn(client_id, username, password, name) {
  const onboarding = await (await fetch(`${base}/api/onboarding`)).json();
  if (onboarding.some((step) => step.step === 'user' && !step.done)) {
    const {auth_code} = await (await fetch(`${base}/api/onboarding/users`,
      json({client_id: local, name, username, password, language: 'en'}))).json();
    const tokens = await token(local, auth_code);
    const headers = {'Content-Type': 'application/json', Authorization: `Bearer ${tokens.access_token}`};
    for (const step of ['core_config', 'analytics']) {
      await fetch(`${base}/api/onboarding/${step}`, {method: 'POST', headers, body: '{}'});
    }
    await fetch(`${base}/api/onboarding/integration`,
      {method: 'POST', headers, body: JSON.stringify({client_id: local, redirect_uri: local})});
  }
  const flow = await (await fetch(`${base}/auth/login_flow`,
    json({client_id, handler: ['homeassistant', null], redirect_uri: client_id}))).json();
  const login = await (await fetch(`${base}/auth/login_flow/${flow.flow_id}`,
    json({client_id, username, password}))).json();
  assert.equal(login.type, 'create_entry', `${username} signs in`);
  return token(client_id, login.result);
}

/** A WebSocket signed in as [accessToken]; `call` runs one command and answers its result. */
async function connect(accessToken) {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/api/websocket`);
  const waiting = new Map();
  let id = 0;
  await new Promise((resolve, reject) => {
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.type === 'auth_required') ws.send(JSON.stringify({type: 'auth', access_token: accessToken}));
      else if (message.type === 'auth_ok') resolve();
      else if (message.type === 'auth_invalid') reject(new Error('auth_invalid'));
      else if (message.type === 'result') waiting.get(message.id)?.(message);
    };
    ws.onerror = () => reject(new Error('websocket failed'));
  });
  const call = (command) => new Promise((resolve) => {
    const next = ++id;
    waiting.set(next, resolve);
    ws.send(JSON.stringify({id: next, ...command}));
  });
  return {call, close: () => ws.close()};
}

const preflight = async (origin) => (await fetch(`${base}/api/states`, {method: 'OPTIONS', headers: {
  Origin: origin, 'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'authorization',
}})).status;

async function waitFor(check, what, seconds = 180) {
  for (let i = 0; i < seconds; i++) {
    try { if (await check()) return; } catch { /* HA is restarting */ }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  assert.fail(`${what} within ${seconds} s`);
}

test('an admin allows a Terrazzo web origin by fixing the repair it raises', async () => {
  const password = 'fixture-only-password';
  const panel = await signIn(local, 'panel', password, 'Test panel user');
  const page = await signIn(`${web}/`, 'panel', password, 'Test panel user');
  assert.equal(await preflight(web), 403, 'HA blocks the origin to begin with');

  // HA's own pages, and a token not issued to a web page, have nothing to allow.
  const own = await connect(panel.access_token);
  assert.deepEqual((await own.call({type: 'terrazzo/web_origin'})).result, {status: 'not_needed'});
  own.close();

  let ws = await connect(page.access_token);
  const reported = await ws.call({type: 'terrazzo/web_origin'});
  assert.deepEqual(reported.result, {status: 'needs_approval', origin: web}, JSON.stringify(reported));
  const {issues} = (await ws.call({type: 'repairs/list_issues'})).result;
  const issue = issues.find((it) => it.domain === 'terrazzo' && it.translation_key === 'web_origin');
  assert.ok(issue?.is_fixable, 'a fixable repair names the origin');
  assert.equal(issue.translation_placeholders.origin, web);
  ws.close();

  const admin = {'Content-Type': 'application/json', Authorization: `Bearer ${panel.access_token}`};
  const flow = await (await fetch(`${base}/api/repairs/issues/fix`, {method: 'POST', headers: admin,
    body: JSON.stringify({handler: 'terrazzo', issue_id: issue.issue_id})})).json();
  assert.equal(flow.step_id, 'confirm', JSON.stringify(flow));
  assert.equal(flow.description_placeholders.origin, web);
  const fixed = await (await fetch(`${base}/api/repairs/issues/fix/${flow.flow_id}`,
    {method: 'POST', headers: admin, body: '{}'})).json();
  assert.equal(fixed.type, 'create_entry', JSON.stringify(fixed));

  // HA restarts with the origin, and the integration confirms the config once it's back.
  await waitFor(async () => await preflight(web) === 200, 'HA allows the origin after restarting');
  await waitFor(async () => {
    const check = await connect(panel.access_token);
    try {
      const {result} = await check.call({type: 'http/config'});
      return result.pending === null && result.stable.cors_allowed_origins.includes(web);
    } finally { check.close(); }
  }, 'the integration confirms the HTTP config', 60);
  assert.equal(await preflight('https://cast.home-assistant.io'), 200, 'the origins HA had stay allowed');
  assert.equal(await preflight('https://elsewhere.example.test'), 403, 'no other origin is allowed');

  ws = await connect(page.access_token);
  assert.deepEqual((await ws.call({type: 'terrazzo/web_origin'})).result, {status: 'allowed', origin: web});
  ws.close();
});
