import { test } from 'node:test';
import assert from 'node:assert/strict';
import { messageInputs, messageAction, serviceCallFor } from '../ha-card.js';

const card = { type: 'custom:terrazzo-message', inputs: [
  { key: 'recipient_type', label: 'Recipient type', type: 'choice', value: 'room', options: ['room', 'contact'] },
  { key: 'channel', label: 'Channel', entity: 'input_select.channel', type: 'choice' },
  { key: 'room', label: 'Contact / Room', type: 'choice', value: 'Kitchen', options: ['Kitchen', 'Living room'] },
  { key: 'message', label: 'Message', type: 'multiline' },
], tap_action: { action: 'perform-action', perform_action: 'notify.send_message', target: { entity_id: 'notify.rooms' },
  data: { message: '{{input.message}}', data: { room: '{{input.room}}', channel: '{{input.channel}}' } } } };
const states = { 'input_select.channel': { state: 'matrix', attributes: { options: ['matrix', 'telegram'] } } };

test('choice entities use options; message edits bind as literal JSON and keep configured service', () => {
  const fields = messageInputs(card, states);
  assert.deepEqual(fields[1].options, ['matrix', 'telegram']);
  assert.equal(fields[1].value, 'matrix');
  const message = 'Hello "room" 🏡\n{{evil}}';
  const drafts = new Map([['message', message], ['room', 'Living room']]);
  const action = messageAction(card, states, drafts);
  assert.deepEqual(serviceCallFor(action), { domain: 'notify', service: 'send_message', target: { entity_id: 'notify.rooms' },
    service_data: { message, data: { room: 'Living room', channel: 'matrix' } } });
  assert.equal(messageInputs(card, { ...states, unrelated: { state: 'on' } })[3].key, 'message');
  assert.equal(drafts.get('message'), message);
});

test('invalid choices, unsupported types, duplicate keys and unknown bindings fail closed', () => {
  assert.throws(() => messageAction(card, states, { room: 'not an option' }), /valid/);
  assert.throws(() => messageInputs({ ...card, inputs: [{ key: 'pin', type: 'password' }] }, states), /Unsupported/);
  assert.throws(() => messageInputs({ ...card, inputs: [card.inputs[0], card.inputs[0]] }, states), /duplicate/);
  assert.throws(() => messageAction({ ...card, tap_action: { ...card.tap_action, data: { message: '{{input.missing}}' } } }, states, {}), /Unknown input/);
  assert.throws(() => messageAction(card, states, { message: 'x'.repeat(4097) }), /valid/);
});
