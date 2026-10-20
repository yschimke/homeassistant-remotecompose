// Exercise the actual packaged panel and compiled WASM against HA protocol fixtures.
// The installed.test.mjs suite separately runs the package inside real HA.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve, extname } from 'node:path';
const root = fileURLToPath(new URL('../../', import.meta.url));
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ?? `${root}embed/node_modules/playwright/index.mjs`);
const dist = process.env.WASM_DIST ?? `${root}app-ui/build/dist/wasmJs/productionExecutable`;
const evidence = `${root}build/ha-plugin-evidence`;
mkdirSync(evidence, {recursive:true});

test('hosted WASM selects hidden dashboards, supports bookmarks and recovers from non-Lovelace Home', {timeout:180_000}, async () => {
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({viewport:{width:1200,height:800}});
    const errors = [];
    const requested = [];
    let named = [
      {url_path:'wall-panel',title:'Wall panel',mode:'yaml'},
      {url_path:'hidden-panel',title:'Hidden panel',mode:'storage',show_in_sidebar:false},
      {url_path:'generated-home',title:'Generated home',mode:'storage'},
    ];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('https://ha.test/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (path.startsWith('/terrazzo-static/wasm/')) {
        const file = resolve(dist, decodeURIComponent(path.slice('/terrazzo-static/wasm/'.length)));
        assert.ok(file.startsWith(resolve(dist) + '/'));
        const contentType = {'.wasm':'application/wasm','.js':'text/javascript','.html':'text/html','.css':'text/css'}[extname(file)] ?? 'application/octet-stream';
        return route.fulfill({contentType,body:readFileSync(file)});
      }
      if (path === '/terrazzo-panel.js') return route.fulfill({contentType:'text/javascript',
        body:readFileSync(`${root}custom_components/terrazzo/frontend/terrazzo-panel.js`)});
      return route.fulfill({contentType:'text/html',body:`<script type="module">
        import '/terrazzo-panel.js';
        const panel = document.createElement('terrazzo-panel');
        panel.hass = {auth:{expired:false,accessToken:'hosted-fixture-token'}};
        panel.panel = {config:{dashboard:'wall-panel'}};
        document.body.style.margin = '0';
        document.body.append(panel);
      </script>`});
    });
    await page.routeWebSocket('wss://ha.test/api/websocket', socket => {
      socket.onMessage(raw => {
        const message = JSON.parse(raw);
        if (message.type === 'auth') {
          assert.equal(message.access_token, 'hosted-fixture-token');
          return socket.send(JSON.stringify({type:'auth_ok',ha_version:'2026.10.0'}));
        }
        const results = {
          'lovelace/dashboards/list':named,
          get_states:[], subscribe_events:null, unsubscribe_events:null,
          'config/entity_registry/list_for_display':{entity_categories:{},entities:[]},
          'config/device_registry/list':[],
          get_config:{unit_system:{temperature:'°C',length:'km',mass:'kg',volume:'L'}},
        };
        let result = results[message.type];
        if (message.type === 'lovelace/config') {
          requested.push(message.url_path ?? null);
          if (message.url_path === 'generated-home') result = {strategy:{type:'home'}};
          else if (['wall-panel','hidden-panel'].includes(message.url_path)) result = {
            views:[{title:'Lights',cards:[{type:'markdown',content:`Selected ${message.url_path}`}]},
              {title:'Garden',cards:[{type:'markdown',content:`Garden of ${message.url_path}`}]}],
          };
          else return socket.send(JSON.stringify({id:message.id,type:'result',success:false,
            error:{code:'config_not_found',message:'No config found.'}}));
        } else assert.ok(message.type in results, `Unexpected command: ${message.type}`);
        socket.send(JSON.stringify({id:message.id,type:'result',success:true,result}));
      });
      socket.send(JSON.stringify({type:'auth_required',ha_version:'2026.10.0'}));
    });
    const frame = page.frameLocator('terrazzo-panel iframe');
    await page.goto('https://ha.test/terrazzo?dashboard=');
    await frame.getByRole('button', {name:'Hidden panel hidden-panel',exact:true}).waitFor({timeout:60_000}).catch(async error => {
      await page.screenshot({path:`${evidence}/dashboard-picker-failure.png`});
      console.error('Picker document:', await frame.locator('body').ariaSnapshot());
      console.error('Requested configs:', requested, 'Page errors:', errors);
      throw error;
    });
    assert.equal(await frame.getByRole('button', {name:/Generated home/}).count(), 0);
    assert.equal(await frame.getByRole('button', {name:/^Home(?: |$)/}).count(), 0);
    await page.screenshot({path:`${evidence}/dashboard-picker.png`});
    await frame.getByRole('button', {name:'Hidden panel hidden-panel',exact:true}).click({force:true});
    await frame.getByText('Selected hidden-panel', {exact:true}).waitFor();
    await page.screenshot({path:`${evidence}/dashboard-hidden.png`});
    await page.goto('https://ha.test/terrazzo?dashboard=hidden-panel');
    await frame.getByText('Selected hidden-panel', {exact:true}).waitFor({timeout:60_000});
    await page.goto('https://ha.test/terrazzo');
    await frame.getByText('Selected wall-panel', {exact:true}).waitFor({timeout:60_000});
    // A wall panel left idle goes back to its dashboard's first view, from another view or
    // another dashboard.
    await page.goto('https://ha.test/terrazzo?return_after=1');
    await frame.getByText('Selected wall-panel', {exact:true}).waitFor({timeout:60_000});
    await frame.getByRole('tab', {name:'Garden',exact:true}).dispatchEvent('click');
    await frame.getByText('Garden of wall-panel', {exact:true}).waitFor();
    await page.mouse.click(600, 400);
    await frame.getByText('Selected wall-panel', {exact:true}).waitFor({timeout:5_000});
    await page.screenshot({path:`${evidence}/dashboard-returned-home.png`});
    await frame.getByRole('tab', {name:'Dashboards',exact:true}).dispatchEvent('click');
    await frame.getByRole('button', {name:'Hidden panel hidden-panel',exact:true}).click({force:true});
    await frame.getByText('Selected hidden-panel', {exact:true}).waitFor();
    await frame.getByText('Selected wall-panel', {exact:true}).waitFor({timeout:5_000});
    await page.goto('https://ha.test/terrazzo?dashboard=home');
    await frame.getByText(/This dashboard is unavailable or is not a Lovelace dashboard/).waitFor({timeout:60_000});
    await page.screenshot({path:`${evidence}/dashboard-unavailable.png`});
    await frame.getByRole('tab', {name:'Dashboards',exact:true}).dispatchEvent('click');
    await frame.getByRole('button', {name:'Hidden panel hidden-panel',exact:true}).click({force:true});
    await frame.getByText('Selected hidden-panel', {exact:true}).waitFor();
    await page.goto('https://ha.test/terrazzo?dashboard=generated-home');
    await frame.getByText(/This dashboard needs Home Assistant's frontend/).waitFor({timeout:60_000});
    named = [];
    await page.goto('https://ha.test/terrazzo?dashboard=');
    await frame.getByText(/No supported dashboards found/).waitFor({timeout:60_000});
    assert.ok(requested.includes(null) && requested.includes('hidden-panel'));
    assert.deepEqual(errors, []);
  } finally { await browser.close(); }
});
