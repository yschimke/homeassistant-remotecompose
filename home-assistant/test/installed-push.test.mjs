// CI-only, after installed-edits.test.mjs: the Terrazzo app's silent push inside a real HA
// container. A mobile_app device registers its FCM token with the integration, points its
// registration's push_url at it, and a notify call made while it holds no websocket is held for
// it — never sent anywhere with its content — until the device takes it, once.
import { test } from 'node:test';
import assert from 'node:assert/strict';

const base = 'http://127.0.0.1:8123';
const client_id = `${base}/`;
const json = (body) => ({method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)});

/** Tokens for [username], onboarding the instance first when nothing has yet. */
async function signIn(username, password, name) {
  const onboarding = await (await fetch(`${base}/api/onboarding`)).json();
  if (onboarding.some((step) => step.step === 'user' && !step.done)) {
    const {auth_code} = await (await fetch(`${base}/api/onboarding/users`,
      json({client_id, name, username, password, language: 'en'}))).json();
    const tokens = await token({code: auth_code});
    const headers = {'Content-Type': 'application/json', Authorization: `Bearer ${tokens.access_token}`};
    for (const step of ['core_config', 'analytics']) {
      await fetch(`${base}/api/onboarding/${step}`, {method: 'POST', headers, body: '{}'});
    }
    await fetch(`${base}/api/onboarding/integration`,
      {method: 'POST', headers, body: JSON.stringify({client_id, redirect_uri: client_id})});
    return tokens;
  }
  const flow = await (await fetch(`${base}/auth/login_flow`,
    json({client_id, handler: ['homeassistant', null], redirect_uri: client_id}))).json();
  const login = await (await fetch(`${base}/auth/login_flow/${flow.flow_id}`,
    json({client_id, username, password}))).json();
  assert.equal(login.type, 'create_entry', `${username} signs in`);
  return token({code: login.result});
}

async function token({code}) {
  const form = new URLSearchParams({client_id, code, grant_type: 'authorization_code'});
  return (await fetch(`${base}/auth/token`, {method: 'POST', body: form})).json();
}

/** A WebSocket signed in as [accessToken]; `call` runs one command and answers its result. */
async function connect(accessToken) {
  const ws = new WebSocket(`${base.replace('http', 'ws')}/api/websocket`);
  const waiting = new Map();
  let id = 0;
  const authed = new Promise((resolve, reject) => {
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.type === 'auth_required') ws.send(JSON.stringify({type: 'auth', access_token: accessToken}));
      else if (message.type === 'auth_ok') resolve();
      else if (message.type === 'auth_invalid') reject(new Error('auth_invalid'));
      else if (message.type === 'result') waiting.get(message.id)?.(message);
    };
  });
  await authed;
  const call = (command) => new Promise((resolve) => {
    const next = ++id;
    waiting.set(next, resolve);
    ws.send(JSON.stringify({id: next, ...command}));
  });
  return {call, close: () => ws.close()};
}

test('a notification for a device with no websocket is held by the integration and taken once', async () => {
  const tokens = await signIn('panel', 'fixture-only-password', 'Test panel user');
  const auth = {'Content-Type': 'application/json', Authorization: `Bearer ${tokens.access_token}`};

  // The app's mobile_app registration, as it makes it (without encryption, so the test can update it).
  const registration = await (await fetch(`${base}/api/mobile_app/registrations`, {
    method: 'POST', headers: auth, body: JSON.stringify({
      device_id: 'terrazzo-push-test', app_id: 'ee.schimke.harc', app_name: 'Terrazzo', app_version: '1.0',
      device_name: 'Push test', manufacturer: 'Test', model: 'Phone', os_name: 'Android', os_version: '16',
      supports_encryption: false, app_data: {push_websocket_channel: true},
    }),
  })).json();
  const webhookId = registration.webhook_id;
  assert.ok(webhookId, 'mobile_app registered the device');

  const ws = await connect(tokens.access_token);
  try {
    // A relay that can't be reached: the wake fails and the message is still held.
    const registered = await ws.call({
      type: 'terrazzo/push/register', webhook_id: webhookId,
      fcm_token: 'f'.repeat(40), relay_url: 'https://127.0.0.1:9/haPush',
    });
    assert.equal(registered.success, true, JSON.stringify(registered));
    const {push_url, push_token} = registered.result;
    assert.equal(push_url, `http://127.0.0.1:8123/api/terrazzo/push/${webhookId}`);

    const someoneElses = await ws.call({type: 'terrazzo/push/register', webhook_id: 'not-mine',
      fcm_token: 'f'.repeat(40), relay_url: 'https://127.0.0.1:9/haPush'});
    assert.equal(someoneElses.success, false, 'only the caller\'s own registration');

    // The app hands HA the integration as its push URL.
    const updated = await fetch(`${base}/api/webhook/${webhookId}`, json({
      type: 'update_registration',
      data: {app_data: {push_websocket_channel: true, push_url, push_token},
        app_version: '1.0', device_name: 'Push test', manufacturer: 'Test', model: 'Phone', os_version: '16'},
    }));
    assert.equal(updated.status, 200);

    // A forged POST without the device's secret is refused.
    const forged = await fetch(push_url, json({message: 'Forged', push_token: 'guess'}));
    assert.equal(forged.status, 403);

    // HA core's notify, with no websocket push channel open: HA posts to the push URL.
    let sent;
    for (let i = 0; i < 20; i++) {
      sent = await fetch(`${base}/api/services/notify/mobile_app_push_test`, {
        method: 'POST', headers: auth,
        body: JSON.stringify({message: 'Washer done', title: 'Laundry', data: {tag: 'washer'}}),
      });
      if (sent.status === 200) break;
      // The notify service appears once mobile_app reloads its notify platform.
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    assert.equal(sent.status, 200, await sent.text());

    let taken;
    for (let i = 0; i < 20; i++) {
      taken = await ws.call({type: 'terrazzo/push/take', webhook_id: webhookId});
      if (taken.result?.messages?.length) break;
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    assert.deepEqual(taken.result.messages, [{message: 'Washer done', title: 'Laundry', data: {tag: 'washer'}}]);
    const again = await ws.call({type: 'terrazzo/push/take', webhook_id: webhookId});
    assert.deepEqual(again.result.messages, [], 'each message is taken once');
  } finally {
    ws.close();
  }
});
