// Clock-dependent formatting the golden file can't pin (its expectations are written once):
// relative times against a fixed "now". Run: node --test embed/test/*.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatRelativeTime, formatState, isRelativeTimeState, compassPoint } from '../ha-card.js';

const now = Date.parse('2026-04-20T15:22:53Z');
const s = 1000;
const min = 60 * s;
const h = 60 * min;
const day = 24 * h;

test('relative times use Home Assistant selectUnit thresholds, like HaStateFormat.kt', () => {
  const cases = [
    [now - 10 * s, 'just now'],
    [now - 50 * s, '1 min ago'],
    [now - (5 * min + 59 * s), '6 min ago'],
    [now - 44 * min, '44 min ago'],
    [now - 50 * min, '1 h ago'],
    [now - 21 * h, '21 h ago'],
    [now - 23 * h, '1 day ago'],
    [now - 6 * day, '6 days ago'],
    [now + 20 * s, 'now'],
    [now + 5 * min + 10 * s, 'in 5 min'],
    [now + 3 * h, 'in 3 h'],
    [now + 2 * day, 'in 2 days'],
    // 2.5 min rounds half to even, as kotlin.math.round does.
    [now - 150 * s, '2 min ago'],
  ];
  for (const [time, expected] of cases) assert.equal(formatRelativeTime(time, now), expected, expected);
});

test('an event state reads relative to the given now', () => {
  const entity = { state: '2026-04-20T12:22:53.298+00:00', attributes: {} };
  assert.equal(formatState(entity, 'event.back_door_motion', null, now), '3 h ago');
  assert.ok(isRelativeTimeState(entity, 'event.back_door_motion'));
  assert.ok(!isRelativeTimeState({ state: '21.5', attributes: {} }, 'sensor.t'));
  assert.ok(!isRelativeTimeState({ state: 'unknown', attributes: {} }, 'event.x'));
});

test('registry display precision wins, as with a snapshot that carries it', () => {
  const entity = { state: '21.46', attributes: { unit_of_measurement: '°C' } };
  assert.equal(formatState(entity, 'sensor.t'), '21.5 °C');
  assert.equal(formatState(entity, 'sensor.t', 2), '21.46 °C');
});

test('compass points cover the circle', () => {
  const cases = { 0: 'N', 11: 'N', 12: 'NNE', 45: 'NE', 90: 'E', 180: 'S', 270: 'W', 349: 'N', '-90': 'W', 720: 'N' };
  for (const [deg, point] of Object.entries(cases)) assert.equal(compassPoint(Number(deg)), point, deg);
});
