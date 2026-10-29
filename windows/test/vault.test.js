'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { CredentialStore } = require('../src/core/vault');

/** Test cipher: AES-256-GCM (stand-in for DPAPI/safeStorage, which only exists inside Electron on Windows). */
function aesCipher(key = crypto.randomBytes(32)) {
  return {
    encrypt(buf) {
      const iv = crypto.randomBytes(12);
      const c = crypto.createCipheriv('aes-256-gcm', key, iv);
      const ct = Buffer.concat([c.update(buf), c.final()]);
      return Buffer.concat([iv, ct, c.getAuthTag()]);
    },
    decrypt(blob) {
      const iv = blob.subarray(0, 12), tag = blob.subarray(blob.length - 16), ct = blob.subarray(12, blob.length - 16);
      const d = crypto.createDecipheriv('aes-256-gcm', key, iv);
      d.setAuthTag(tag);
      return Buffer.concat([d.update(ct), d.final()]);
    },
  };
}
const file = () => path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'th-')), 'vault.json');

test('save / get / delete', () => {
  const s = new CredentialStore(file(), aesCipher());
  assert.equal(s.save('p1', { username: 'alice', password: 'pa55' }), true);
  assert.deepEqual(s.get('p1'), { username: 'alice', password: 'pa55' });
  assert.equal(s.has('p1'), true);
  s.delete('p1');
  assert.equal(s.get('p1'), null);
  assert.equal(s.has('p1'), false);
});

test('never written in plaintext', () => {
  const f = file();
  new CredentialStore(f, aesCipher()).save('p1', { username: 'alice_user', password: 'SuperSecretPassw0rd' });
  const raw = fs.readFileSync(f, 'utf8');
  assert.ok(!raw.includes('SuperSecretPassw0rd') && !raw.includes('alice_user'));
});

test('persists across instances', () => {
  const f = file(); const key = crypto.randomBytes(32);
  new CredentialStore(f, aesCipher(key)).save('p1', { username: 'u', password: 'p' });
  assert.deepEqual(new CredentialStore(f, aesCipher(key)).get('p1'), { username: 'u', password: 'p' });
});

test('wrong key returns null instead of throwing', () => {
  const f = file();
  new CredentialStore(f, aesCipher()).save('p1', { username: 'u', password: 'p' });
  const s = new CredentialStore(f, aesCipher());
  assert.equal(s.has('p1'), true);
  assert.equal(s.get('p1'), null);
});

test('blob swapped between portals is rejected', () => {
  const f = file(); const key = crypto.randomBytes(32);
  const s = new CredentialStore(f, aesCipher(key));
  s.save('p1', { username: 'u1', password: 'p1' });
  s.save('p2', { username: 'u2', password: 'p2' });
  const j = JSON.parse(fs.readFileSync(f, 'utf8'));
  j.entries.p2 = j.entries.p1;
  fs.writeFileSync(f, JSON.stringify(j));
  assert.equal(new CredentialStore(f, aesCipher(key)).get('p2'), null);
});

test('corrupted file is quarantined; corrupted entry returns null', () => {
  const f = file();
  fs.writeFileSync(f, 'garbage{{');
  const s = new CredentialStore(f, aesCipher());
  assert.equal(s.count(), 0);
  assert.ok(fs.existsSync(f + '.corrupt'));
  assert.equal(s.save('p1', { username: 'u', password: 'p' }), true);
  const j = JSON.parse(fs.readFileSync(f, 'utf8'));
  j.entries.p1 = 'AAAA';
  fs.writeFileSync(f, JSON.stringify(j));
  assert.equal(new CredentialStore(f, s.cipher).get('p1'), null);
});

test('deleteAll clears everything', () => {
  const f = file(); const c = aesCipher();
  const s = new CredentialStore(f, c);
  s.save('a', { username: 'u', password: 'p' }); s.save('b', { username: 'u', password: 'p' });
  s.deleteAll();
  assert.equal(new CredentialStore(f, c).count(), 0);
});

test('a failing cipher yields false/null and leaks nothing', () => {
  const bad = { encrypt() { throw new Error('boom hunter2'); }, decrypt() { throw new Error('boom hunter2'); } };
  const s = new CredentialStore(file(), bad);
  assert.equal(s.save('p', { username: 'u', password: 'hunter2' }), false);
  assert.equal(s.get('p'), null);
});
