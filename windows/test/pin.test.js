'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { PinManager } = require('../src/core/pin');
const { sanitize, DEFAULTS } = require('../src/core/settings');

const mapKv = () => { const m = new Map(); return { m, get: (k) => m.get(k), set: (k, v) => m.set(k, v), remove: (...ks) => ks.forEach((k) => m.delete(k)) }; };
const fast = { N: 1024, r: 8, p: 1 };
const make = () => { const kv = mapKv(); const t = { now: 1000000 }; return { kv, t, pin: new PinManager(kv, () => t.now, fast) }; };

test('no PIN by default', () => assert.equal(make().pin.isSet(), false));

test('correct PIN verifies, wrong does not', async () => {
  const { pin } = make();
  assert.equal(await pin.setPin('2468'), true);
  assert.equal(pin.isSet(), true);
  assert.equal((await pin.verify('2468')).status, 'success');
  assert.equal((await pin.verify('1111')).status, 'wrong');
});

test('PIN format enforced', async () => {
  const { pin } = make();
  for (const bad of ['123', '12ab', '1'.repeat(13), '']) assert.equal(await pin.setPin(bad), false);
  assert.equal(pin.isSet(), false);
});

test('PIN is not stored in plaintext and salt is unique', async () => {
  const { pin, kv } = make();
  await pin.setPin('2468');
  assert.ok([...kv.m.values()].every((v) => !String(v).includes('2468')));
  const s1 = kv.get('salt');
  await pin.setPin('2468');
  assert.notEqual(s1, kv.get('salt'));
});

test('lockout after repeated failures, then recovers', async () => {
  const { pin, t } = make();
  await pin.setPin('2468');
  for (let i = 0; i < 4; i++) assert.equal((await pin.verify('0000')).status, 'wrong');
  assert.equal((await pin.verify('0000')).status, 'locked');
  assert.equal((await pin.verify('2468')).status, 'locked'); // even the right PIN is refused while locked
  t.now += 31000;
  assert.equal((await pin.verify('2468')).status, 'success');
  assert.equal((await pin.verify('0000')).status, 'wrong'); // counter reset
});

test('clear removes the PIN', async () => {
  const { pin } = make();
  await pin.setPin('2468');
  pin.clear();
  assert.equal(pin.isSet(), false);
});

test('settings are sanitized', () => {
  assert.deepEqual(sanitize(null), DEFAULTS);
  const s = sanitize({ theme: 'DARK', view: 'nope', autoLockSeconds: 7, blockScreenshots: 'yes', sort: 'RECENT', evil: 1 });
  assert.equal(s.theme, 'DARK');
  assert.equal(s.view, DEFAULTS.view);
  assert.equal(s.autoLockSeconds, DEFAULTS.autoLockSeconds);
  assert.equal(s.blockScreenshots, DEFAULTS.blockScreenshots);
  assert.equal(s.sort, 'RECENT');
  assert.equal('evil' in s, false);
});
