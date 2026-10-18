import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../../', import.meta.url));
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? `${root}embed/node_modules/playwright/index.mjs`);
const playerPath = process.env.RC_PLAYER_JS ?? `${root}design-server/build/generated/embed/embed/v1/rc-player.js`;
const frontend = `${root}custom_components/terrazzo/frontend/`;

test('message card renders real RC inputs, preserves drafts/focus, and sends via authenticated HA action handling', { timeout: 45000 }, async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 640, height: 900 }, deviceScaleFactor: 1 });
    const errors = [];
    page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
    page.setDefaultTimeout(10000);
    await page.route('https://ha.test/**', route => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('.js')) {
        const name = path.split('/').pop();
        const file = name === 'ha-card.js' ? `${root}embed/ha-card.js` : name === 'rc-player.js' ? playerPath : `${frontend}${name}`;
        return route.fulfill({ contentType: 'text/javascript', body: readFileSync(file) });
      }
      if (path === '/api/cards/resolve') throw new Error('input data must never reach the design resolver');
      return route.fulfill({ contentType: 'text/html', body: `<!doctype html><meta charset="utf-8"><style>body{font:16px system-ui;background:#f4f4f4;margin:24px}remote-compose-card{width:440px;background:white;border:1px solid #ddd;border-radius:16px;padding:12px}h2{font-size:18px}</style>
        <h2>Send to a room</h2><script>customElements.define('ha-card', class extends HTMLElement {});</script>
        <script type="module" src="/terrazzo-static/remote-compose-card.js"></script>` });
    });
    await page.goto('https://ha.test/lovelace/0');
    await page.evaluate(async () => {
      await Promise.race([customElements.whenDefined('remote-compose-card'), new Promise((_, reject) => setTimeout(() => reject(new Error('Custom card did not register')), 8000))]);
      window.calls = [];
      window.cardConfig = { type: 'custom:terrazzo-message', inputs: [
        { key: 'recipient_type', label: 'Recipient type', type: 'choice', value: 'room', options: ['room', 'contact'] },
        { key: 'channel', label: 'Channel', entity: 'input_select.channel', type: 'choice' },
        { key: 'room', label: 'Contact / Room', type: 'choice', value: 'Kitchen', options: ['Kitchen', 'Living room'] },
        { key: 'message', label: 'Message', type: 'multiline' },
      ], tap_action: { action: 'perform-action', perform_action: 'script.send_room_message',
        data: { message: '{{input.message}}', recipient_type: '{{input.recipient_type}}', channel: '{{input.channel}}', room: '{{input.room}}' } } };
      window.hassFixture = { states: { 'input_select.channel': { state: 'matrix', attributes: { options: ['matrix', 'telegram'] } } },
        themes: { darkMode: false }, user: { id: 'fixture' },
        callService: async (...args) => window.calls.push(args) };
      window.card = document.createElement('remote-compose-card');
      card.hass = hassFixture;
      card.setConfig({ card: cardConfig });
      document.body.appendChild(card);
    });
    const message = page.getByRole('textbox', { name: 'Message' });
    await message.waitFor().catch(async error => { console.error(await page.evaluate(() => ({ status: card.shadowRoot.innerHTML, player: !!card.player, runtime: Object.keys(window.RC ?? {}), tree: card.player?.getDocument()?.getRootLayoutComponent()?.deepToString(""), ops: card.player?.getDocument()?.getOperations().map(o => o.constructor.name), host: card.player?.getCustomHost()?.inputs ? true : false }))); throw error; });
    const out = `${root}docs/evidence/editable-message`;
    mkdirSync(out, { recursive: true });
    await page.screenshot({ path: `${out}/before-entry.png` });
    await page.getByRole('combobox', { name: 'Contact / Room' }).selectOption('Living room');
    await message.fill('Hello "room" 🏡\nDinner is ready');
    await message.focus();
    await message.press('Enter');
    assert.equal(await page.evaluate(() => calls.length), 0, 'multiline Enter inserts a newline without sending');
    const entered = await message.inputValue();
    await page.evaluate(() => {
      card.hass = { ...hassFixture, states: { ...hassFixture.states, 'sensor.delivery': { state: 'now' } } };
    });
    assert.equal(await message.inputValue(), entered);
    assert.equal(await message.evaluate(el => el === el.getRootNode().activeElement), true, 'normal live updates preserve focus');
    await page.evaluate(() => {
      card.hass = { ...hassFixture, states: { 'input_select.channel': { state: 'telegram', attributes: { options: ['matrix', 'telegram', 'signal'] } } } };
    });
    await page.waitForFunction(() => Array.from(card.shadowRoot.querySelector('select[aria-label="Channel"]')?.options ?? card.shadowRoot.querySelectorAll('select')[1]?.options ?? []).some(o => o.value === 'signal'));
    assert.equal(await message.inputValue(), entered, 're-encoded choice list keeps edited message');
    await page.getByRole('button', { name: 'Send' }).click();
    await page.waitForFunction(() => calls.length === 1);
    assert.deepEqual(await page.evaluate(() => calls[0]), ['script', 'send_room_message', {
      message: entered, recipient_type: 'room', channel: 'telegram', room: 'Living room',
    }, undefined]);
    await page.screenshot({ path: `${out}/after-entry.png` });
    await page.evaluate(() => { card.hass = { ...card._hass, callService: async () => { throw new Error('fixture service failure'); } }; });
    await page.getByRole('button', { name: 'Send' }).click();
    await page.getByText('Action failed: fixture service failure').waitFor();
    assert.equal(await message.inputValue(), entered, 'failed actions retain the draft');
    await page.evaluate(() => card.setAttribute('read-only', ''));
    await page.getByText(/Open in app to edit/).first().waitFor();
    assert.equal(await page.getByRole('textbox').count(), 0);
    assert.equal(await page.getByRole('button', { name: 'Send' }).count(), 0);
    await page.screenshot({ path: `${out}/read-only.png` });
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});
