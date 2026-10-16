import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { DashboardTransaction, bindTemplate, canonical, previewSnapshot, parseJson, copy, createTemplate } from '../src/main/resources/dashboard/model.mjs';

test('save and undo detect conflicts, preserve custom config and retain recovery state', async () => {
  const original = { title: 'Home', views: [{ type: 'sections', sections: [{ cards: [{ type: 'custom:test', opaque: [null, { x: 7 }] }] }] }], future: true };
  let remote = structuredClone(original), writes = 0;
  const connection = { async send(message) {
    if (message.type === 'lovelace/config') return structuredClone(remote);
    assert.equal(message.type, 'lovelace/config/save');
    assert.equal(message.url_path, 'test-dashboard');
    remote = structuredClone(message.config); writes++;
  } };
  const edit = new DashboardTransaction(connection, 'test-dashboard', original);
  const draft = { ...original, title: 'Edited' };
  remote.title = 'External';
  await assert.rejects(edit.save(draft), /conflict/);
  assert.equal(writes, 0);
  assert.deepEqual(edit.baseline, original);
  remote = original;
  await edit.save(draft);
  assert.deepEqual(remote, draft);
  remote = { ...draft, external: true };
  await assert.rejects(edit.undo(), /conflict/);
  assert.deepEqual(edit.previous, original);
  remote = draft;
  await edit.undo();
  assert.deepEqual(remote, original);
  assert.equal(writes, 2);
  assert.equal(edit.previous, null);
});

test('key order does not create conflicts, failed writes retry, concurrent writes refuse', async () => {
  let fail = true, release;
  const gate = new Promise(resolve => { release = resolve; });
  let remote = { views: [], title: 'Home' };
  const connection = { async send(message) {
    if (message.type === 'lovelace/config') { await gate; return remote; }
    if (fail) throw new Error('offline');
    remote = message.config;
  } };
  const edit = new DashboardTransaction(connection, null, { title: 'Home', views: [] });
  const saving = edit.save({ views: [], title: 'New' });
  await assert.rejects(edit.save({ views: [] }), /in progress/);
  release();
  await assert.rejects(saving, /offline/);
  assert.equal(edit.busy, false);
  fail = false;
  await edit.save({ views: [], title: 'New' });
  assert.equal(remote.title, 'New');
  assert.equal(canonical({ b: 1, a: 2 }), canonical({ a: 2, b: 1 }));
});

test('YAML and non-admin draft sessions never issue a save', async () => {
  for (const reason of ['YAML dashboard: export a draft', 'Administrator access required']) {
    const edit = new DashboardTransaction({ send() { assert.fail('must not connect'); } }, null, {}, reason);
    await assert.rejects(edit.save({ views: [] }), new RegExp(reason));
  }
});

test('published section/view templates bind by domain and device class without mutating config', async () => {
  const templates = JSON.parse(await readFile(new URL('../../lovelace-catalog/templates.json', import.meta.url)));
  const states = { 'climate.boiler': { state: 'heat' }, 'sensor.room': { state: '21', attributes: { device_class: 'temperature' } }, 'sensor.power': { state: '500', attributes: { device_class: 'power' } } };
  const bindings = { 'climate.s0': 'climate.boiler', 'sensor.s0': 'sensor.room' };
  for (const template of templates) {
    const before = canonical(template);
    const placed = bindTemplate(template, bindings, states);
    assert.deepEqual(new Set(['climate.boiler', 'sensor.room']), new Set((JSON.stringify(placed).match(/(?:climate\.boiler|sensor\.room)/g))));
    assert.equal(canonical(template), before);
    assert.throws(() => bindTemplate(template, { ...bindings, 'sensor.s0': 'sensor.power' }, states), /does not match/);
    assert.throws(() => bindTemplate(template, { 'climate.s0': 'climate.boiler' }, states), /exactly once/);
  }
});

test('preview passes only referenced states and leaves credentials and signed images in host', () => {
  const snapshot = previewSnapshot({ type: 'tile', entity: 'light.sofa' }, {
    'light.sofa': { state: 'on', attributes: { friendly_name: 'Sofa', brightness: 128, access_token: 'secret', entity_picture: '/signed?token=secret' } },
    'sensor.unrelated': { state: 'private' },
  });
  assert.deepEqual(Object.keys(snapshot.states), ['light.sofa']);
  assert.equal(snapshot.states['light.sofa'].attributes.brightness, 128);
  assert.ok(!JSON.stringify(snapshot).includes('secret'));
});

test('unknown large integers survive import, copying, conflict checks and export', () => {
  const source = '{"views":[],"future":9007199254740993}';
  const value = parseJson(source);
  assert.equal(JSON.stringify(copy(value)), source);
  assert.equal(canonical(value), canonical(parseJson('{"future":9007199254740993,"views":[]}')));
  assert.notEqual(canonical(value), canonical(parseJson('{"views":[],"future":9007199254740992}')));
});

test('saving an unchanged draft does not write or erase the last real undo', async () => {
  let remote = {title:'Original',views:[]}, writes=0;
  const edit = new DashboardTransaction({async send(message) {
    if (message.type==='lovelace/config') return remote;
    remote=message.config; writes++;
  }},null,remote);
  assert.equal(await edit.save({views:[],title:'Original'}),false); assert.equal(writes,0);
  assert.equal(await edit.save({views:[],title:'Edited'}),true); assert.equal(writes,1);
  assert.equal(await edit.save({title:'Edited',views:[]}),false); assert.equal(writes,1);
  await edit.undo(); assert.equal(remote.title,'Original'); assert.equal(writes,2);
});

test('capturing templates replaces references, preserves literals and binds to another home', () => {
  const source = {title:'Heating',cards:[{type:'tile',entity:'sensor.room',name:'sensor.room',future:parseJson('9007199254740993')}]};
  const template = createTemplate('Section',source,{'sensor.room':{attributes:{device_class:'temperature'}}});
  assert.equal(template.config.cards[0].entity,'sensor.s0'); assert.equal(template.config.cards[0].name,'sensor.room');
  assert.deepEqual(template.slots,[{id:'sensor.s0',domain:'sensor',deviceClass:'temperature'}]);
  const placed = bindTemplate(template,{'sensor.s0':'sensor.other'},{'sensor.other':{attributes:{device_class:'temperature'}}});
  assert.equal(placed.cards[0].entity,'sensor.other'); assert.equal(placed.cards[0].name,'sensor.room');
  assert.equal(JSON.stringify(placed.cards[0].future),'9007199254740993');
  assert.equal(source.cards[0].entity,'sensor.room');
  assert.throws(()=>createTemplate('View',{type:'panel',cards:[]},{}),/sections views/);
});


test('unknown decimal precision, exponents and negative zero survive the host JSON lane', () => {
  for (const literal of ['0.123456789012345678901', '1.0000000000000000001', '1e999', '1e-999', '-0', '1.0', '1e2']) {
    const source = `{"views":[],"future":${literal}}`;
    const value = parseJson(source);
    assert.equal(JSON.stringify(copy(value)),source);
    assert.equal(canonical(value),canonical(parseJson(source)));
  }
  assert.notEqual(canonical(parseJson('{"future":0.123456789012345678901}')),canonical(parseJson('{"future":0.123456789012345678902}')));
});


test('a browser without number source context refuses rather than rounding config', () => {
  const original = JSON.parse;
  try {
    JSON.parse = (text, reviver) => original(text, function(key,value) { return reviver.call(this,key,value); });
    assert.throws(() => parseJson('{"future":1.0000000000000000001}'), /cannot preserve JSON numbers/);
    assert.throws(() => parseJson('{"future":-0}'), /cannot preserve JSON numbers/);
  } finally { JSON.parse = original; }
});
