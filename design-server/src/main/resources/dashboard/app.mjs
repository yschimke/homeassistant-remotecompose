import { sessionFor, normalizeHassUrl, applyEntitiesEvent } from '/embed/v1/ha-card.js';
import { DashboardTransaction, bindTemplate, canonical, copy, entityIds, previewSnapshot, parseJson, createTemplate } from './model.mjs';

const $ = id => document.getElementById(id);
const frame = $('editor');
let draftValid = false;
let ready = false, capabilities, current, config, selected, connection, transaction, unsubscribe;
let subscriptionKey = "";
let states = {}, templates = [], history = [], previewGeneration = 0, timer, previewAccess = false;
const previewCache = new Map();
const fixtureStates = {
  'light.sofa': { entity_id: 'light.sofa', state: 'on', attributes: { friendly_name: 'Sofa', brightness: 180 } },
  'sensor.room': { entity_id: 'sensor.room', state: '21.5', attributes: { friendly_name: 'Room', device_class: 'temperature', unit_of_measurement: '°C' } },
  'climate.boiler': { entity_id: 'climate.boiler', state: 'heat', attributes: { friendly_name: 'Boiler', current_temperature: 21.5, temperature: 22 } },
};
function status(text) { $('status').textContent = text; }
async function run(action) { try { await action(); } catch (error) { status(error.message); } }
async function json(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) throw new Error(await response.text());
  return parseJson(await response.text());
}
function send(message) { if (ready) frame.contentWindow.postMessage(message, location.origin); }
function pendingDraft() { draftValid = false; $('save').disabled = true; $('export').disabled = true; }
function openConfig(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Import a dashboard JSON object.');
  pendingDraft();
  config = copy(value);
  $('json').value = JSON.stringify(config, null, 2);
  if (capabilities) send({ type: 'compose-ui-builder/open', lovelaceConfig: JSON.stringify(config), capabilities: JSON.stringify(capabilities), label: config.title || 'Home Assistant dashboard' });
  refreshViews();
}
function openDocument(value) {
  pendingDraft();
  send({ type: 'compose-ui-builder/open', document: JSON.stringify(value), capabilities: JSON.stringify(capabilities) });
  if (selected && value.nodes[selected]) send({ type: 'compose-ui-builder/select', nodeId: selected });
}
function option(select, value, label) {
  const element = document.createElement('option'); element.value = value; element.textContent = label; select.append(element);
}
function refreshViews() {
  const previous = $('view').value; $('view').replaceChildren();
  (config?.views ?? []).forEach((view, index) => { if (view.type === 'sections') option($('view'), index, view.title || `View ${index + 1}`); });
  if ([...$('view').options].some(o => o.value === previous)) $('view').value = previous;
}
function refreshSelection() {
  const node = current?.nodes[selected];
  const card = node?.componentId.startsWith('lovelace/card/');
  const entity = node?.properties?.entity?.value;
  $('entity').replaceChildren(); option($('entity'), '', 'Choose an entity');
  Object.entries(states).sort(([a], [b]) => a.localeCompare(b)).forEach(([id, state]) => {
    option($('entity'), id, `${state.attributes?.friendly_name || id} · ${id} · ${state.state}`);
  });
  $('entity').value = entity || '';
  $('entity').disabled = !card;
  for (const [name, property] of [['columns', 'grid_columns'], ['rows', 'grid_rows']]) {
    $(name).disabled = !card;
    $(name).value = node?.properties?.[property]?.value ?? (name === 'columns' ? 'full' : 'auto');
  }
}
function updateProperty(name, value) {
  if (!current?.nodes[selected]?.componentId.startsWith('lovelace/card/')) return;
  const document = copy(current);
  if (value === '' || name === 'grid_rows' && value === 'auto') delete document.nodes[selected].properties[name];
  else document.nodes[selected].properties[name] = { type: typeof value === 'number' ? 'int' : 'string', value };
  openDocument(document);
}
function bindControls() {
  $('bindings').replaceChildren();
  const template = templates.find(t => t.id === $('template').value);
  for (const slot of template?.slots ?? []) {
    const label = document.createElement('label'); label.textContent = `${slot.id}${slot.deviceClass ? ` (${slot.deviceClass})` : ''} `;
    const select = document.createElement('select'); select.dataset.slot = slot.id;
    for (const [id, state] of Object.entries(states)) {
      if (id.split('.')[0] === slot.domain && (!slot.deviceClass || state.attributes?.device_class === slot.deviceClass)) option(select, id, `${state.attributes?.friendly_name || id} · ${id}`);
    }
    label.append(select); $('bindings').append(label);
  }
}
async function syncSubscription() {
  if (!transaction || !connection || !config) return;
  const ids = entityIds(config).sort(), key = JSON.stringify(ids);
  if (key === subscriptionKey) return;
  subscriptionKey = key; unsubscribe?.(); unsubscribe = null;
  const stop = await connection.subscribeEntities(ids, event => { applyEntitiesEvent(states, event); queuePreviews(); });
  if (key === subscriptionKey) unsubscribe = stop; else stop();
}
function queuePreviews() { clearTimeout(timer); timer = setTimeout(() => run(previews), 250); }
async function previews() {
  const generation = ++previewGeneration;
  if (!current || !config || !draftValid || !previewAccess) return;
  const requested = current;
  $('preview-status').textContent = '';
  const result = {};
  // Associate the exact config with each card id, rather than reconstructing rawConfig in JS.
  const cards = [];
  for (const [v, view] of (config.views ?? []).entries()) {
    if (view.type !== 'sections') continue;
    const viewId = current.nodes[current.roots[0]]?.slots.views?.[v];
    for (const [s, section] of (view.sections ?? []).entries()) {
      const sectionId = current.nodes[viewId]?.slots.sections?.[s];
      for (const [c, card] of (section.cards ?? []).entries()) {
        const id = current.nodes[sectionId]?.slots.cards?.[c];
        if (id) cards.push({ id, card });
      }
    }
  }
  for (const { id, card } of cards) {
    if (generation !== previewGeneration || requested !== current) return;
    const body = { card, snapshot: previewSnapshot(card, states), style: $('style').value, dark: $('dark').checked };
    const key = canonical(body);
    try {
      let preview = previewCache.get(key);
      if (!preview) {
        preview = await json('/api/studio/dashboard/preview', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        previewCache.set(key, preview);
        if (previewCache.size > 100) previewCache.delete(previewCache.keys().next().value);
      }
      result[id] = { config: card, properties: preview.properties };
      if (generation === previewGeneration && requested === current) send({ type: 'compose-ui-builder/lovelace-previews', previews: JSON.stringify(result) });
    } catch (error) {
      result[id] = {config:card,error:'Preview unavailable'};
      if (generation === previewGeneration && requested === current) send({type:'compose-ui-builder/lovelace-previews',previews:JSON.stringify(result)});
      if (generation === previewGeneration && requested === current) $('preview-status').textContent = 'Some card previews are unavailable. Their configuration is kept.';
    }
  }
}
window.addEventListener('message', event => {
  if (event.source !== frame.contentWindow || event.origin !== location.origin) return;
  const message = event.data;
  if (message?.type === 'compose-ui-builder/ready') { ready = true; if (capabilities && config) openConfig(config); }
  else if (message?.type === 'compose-ui-builder/changed') {
    const next = parseJson(message.document);
    if (canonical(next) !== canonical(current ?? {})) {
      if (current) history.push(current);
      if (history.length > 100) history.shift();
      current = next; draftValid = false; $("save").disabled = true; $("export").disabled = true;
      refreshSelection();
    }
  } else if (message?.type === 'compose-ui-builder/lovelace-config') {
    draftValid = true; $("save").disabled = !transaction || !!transaction.readOnlyReason; $("export").disabled = false;
    config = parseJson(message.config); $('json').value = JSON.stringify(config, null, 2); refreshViews(); queuePreviews(); run(syncSubscription);
  } else if (message?.type === 'compose-ui-builder/selection') { selected = message.nodeId; refreshSelection(); }
  else if (message?.type === 'compose-ui-builder/chrome') {
    $('chrome').replaceChildren();
    for (const action of message.actions) {
      const button = document.createElement('button'); button.textContent = action.label; button.disabled = !action.enabled;
      if (typeof action.checked === 'boolean') button.setAttribute('aria-pressed', String(action.checked));
      button.addEventListener('click', () => send({ type: 'compose-ui-builder/invoke', id: action.id })); $('chrome').append(button);
    }
  } else if (message?.type === 'compose-ui-builder/generated-code') {
    $('json').value = message.kotlin; $('json').closest('details').open = true;
  } else if (message?.type === 'compose-ui-builder/open-link') {
    const url = new URL(message.url,location.href);
    if (url.protocol === 'https:' || url.origin === location.origin) window.open(url.href,'_blank','noopener');
  } else if (message?.type === 'compose-ui-builder/error') status(message.message);
});

$('fixture').onclick = () => run(async () => {
  unsubscribe?.(); unsubscribe = null; transaction = null; states = copy(fixtureStates); history = []; current = null;
  $('save').disabled = true; $('undo-save').disabled = true;
  openConfig(await json('/studio/dashboard/fixture.json')); bindControls(); status('Test dashboard loaded. It is separate from Home Assistant.');
});
$('file').onchange = () => run(async () => {
  const file = $('file').files[0]; if (!file) return;
  unsubscribe?.(); unsubscribe = null; transaction = null; current = null; history = [];
  $('save').disabled = true; $('undo-save').disabled = true;
  openConfig(parseJson(await file.text())); status('Imported as a draft. Export JSON to keep your changes.');
});
function downloadJson(value, filename) {
  const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }));
  const link = document.createElement('a'); link.href = url; link.download = filename; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
}
$('export').onclick = () => { if (config && draftValid) downloadJson(config, 'dashboard.json'); };
$('capture-template').onclick = () => run(async () => {
  if (!current || !config || !draftValid) throw new Error('Open a valid dashboard and select a section or view.');
  const viewIds = current.nodes[current.roots[0]].slots.views ?? [];
  for (const [v,id] of viewIds.entries()) {
    if (selected === id) { downloadJson(createTemplate('View',config.views[v],states),'view-template.json'); return; }
    const sectionIds = current.nodes[id].slots?.sections ?? [];
    const section = sectionIds.indexOf(selected);
    if (section >= 0) { downloadJson(createTemplate('Section',config.views[v].sections[section],states),'section-template.json'); return; }
  }
  throw new Error('Select a section or a sections view in Layers.');
});
$('template-file').onchange = () => run(async () => {
  const file = $('template-file').files[0]; if (!file) return;
  const template = parseJson(await file.text());
  if (!['Section','View'].includes(template.kind) || !template.config || typeof template.config !== 'object' || Array.isArray(template.config) || !Array.isArray(template.slots) || typeof template.title !== 'string' || !template.title || !template.slots.every(slot => typeof slot.id === 'string' && typeof slot.domain === 'string' && (!slot.deviceClass || typeof slot.deviceClass === 'string'))) throw new Error('Import a section/view template JSON file.');
  template.id = `imported-${templates.length}`; templates.push(template);
  option($('template'),template.id,template.title); $('template').value=template.id; bindControls();
  status('Template imported. Bind its entity slots and choose where to place it.');
});
$('undo-edit').onclick = () => { const before = history.pop(); if (before) { current = before; openDocument(before); } };
$('entity').onchange = () => updateProperty('entity', $('entity').value);
for (const [id, name] of [['columns', 'grid_columns'], ['rows', 'grid_rows']]) {
  option($(id), id === 'columns' ? 'full' : 'auto', id === 'columns' ? 'Full' : 'Auto');
  for (let n = 1; n <= 12; n++) option($(id), n, n);
  $(id).onchange = () => updateProperty(name, /^\d+$/.test($(id).value) ? Number($(id).value) : $(id).value);
}
$('style').onchange = queuePreviews; $('dark').onchange = queuePreviews;
window.addEventListener('focus', () => run(async () => {
  const access = await json('/api/studio/dashboard/access');
  previewAccess = access.authorized;
  if (previewAccess) { $('preview-login').hidden = true; queuePreviews(); }
}));
$('template').onchange = bindControls;
$('place').onclick = () => run(async () => {
  if (!config) throw new Error('Open a dashboard first.');
  const template = templates.find(t => t.id === $('template').value);
  if (!template) throw new Error('Choose a template after the catalog loads.');
  const bindings = Object.fromEntries([...$('bindings').querySelectorAll('select')].map(select => [select.dataset.slot, select.value]));
  const placed = bindTemplate(template, bindings, states), next = copy(config);
  if (template.kind === 'View') (next.views ??= []).push(placed);
  else {
    const view = next.views?.[Number($('view').value)];
    if (view?.type !== 'sections') throw new Error('Choose a sections view.');
    (view.sections ??= []).push(placed);
  }
  openConfig(next); status(`${template.title} placed with your selected entities.`);
});

async function connect() {
  const url = normalizeHassUrl($('hass').value); localStorage.setItem('dashboard-editor.hass', url);
  const session = sessionFor(url); await session.auth.completeSignIn();
  if (!session.auth.signedIn) { session.auth.signIn(); return; }
  connection = session.connection; connection.decodeMessage = parseJson; await connection.connect();
  const dashboards = await connection.send({ type: 'lovelace/dashboards/list' });
  $('dashboard').replaceChildren(); option($('dashboard'), '', 'Overview (built-in)');
  for (const item of dashboards) {
    option($('dashboard'), item.url_path, item.title); $('dashboard').lastElementChild.dataset.mode = item.mode || '';
  }
  status('Connected. Choose a dashboard and load it.');
}
$('connect').onclick = () => run(connect);
$('load').onclick = () => run(async () => {
  if (!connection) throw new Error('Connect to Home Assistant first.');
  const path = $('dashboard').value || null;
  const baseline = await connection.send({ type: 'lovelace/config', ...(path == null ? {} : { url_path: path }) });
  const user = await connection.send({ type: 'auth/current_user' });
  const mode = $('dashboard').selectedOptions[0]?.dataset.mode;
  const reason = !user.is_admin ? 'Administrator access is required to save. Export your draft instead.' : mode === 'yaml' ? 'YAML dashboards support draft export only.' : null;
  transaction = new DashboardTransaction(connection, path, baseline, reason);
  states = Object.fromEntries((await connection.send({ type: 'get_states' })).map(state => [state.entity_id, state]));
  unsubscribe?.(); history = []; current = null; openConfig(baseline); bindControls();
  subscriptionKey = ""; await syncSubscription();
  $('save').disabled = !!reason || !draftValid; $('undo-save').disabled = true; status(reason || 'Dashboard loaded. Save checks for changes in Home Assistant first.');
});
$('save').onclick = () => run(async () => {
  if (!transaction || !config || !draftValid) return;
  $('save').disabled = true;
  try { const changed = await transaction.save(config); $('undo-save').disabled = !transaction.previous; status(changed ? 'Dashboard saved. Undo save is available.' : 'No changes to save.'); }
  finally { $('save').disabled = !!transaction.readOnlyReason || !draftValid; }
});
$('undo-save').onclick = () => run(async () => {
  if (!transaction) return;
  const saved = canonical(transaction.baseline);
  await transaction.undo(); if (canonical(config) === saved) openConfig(transaction.baseline);
  $('undo-save').disabled = true; status('Saved dashboard restored. Your unsaved edits are kept.');
});

await run(async () => {
  [capabilities, templates] = await Promise.all([json('/api/studio/dashboard/catalog'), json('/studio/dashboard/templates.json')]);
  for (const template of templates) option($('template'), template.id, template.title);
  const access = await json('/api/studio/dashboard/access'); previewAccess = access.authorized;
  if (access.signInPath) { $('preview-login').href = access.signInPath; $('preview-login').hidden = false; }
  if (ready && config) openConfig(config);
  $('hass').value = localStorage.getItem('dashboard-editor.hass') || '';
  if (new URLSearchParams(location.search).has('code') && $('hass').value) await connect();
});
