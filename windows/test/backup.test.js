'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const backup = require('../src/core/backup');
const { PortalRepository, blankPortal } = require('../src/core/portals');

const P = blankPortal({ id: 'x', name: 'My Portal', url: 'https://example.com/', category: 'Visa', notes: 'n', favorite: true,
  autoFill: true, autoSubmit: true, extraTrustedHosts: ['sso.example.com'], logoutUrl: 'https://example.com/logout' });

test('export has no secrets or security-relevant login settings', () => {
  const entry = JSON.parse(backup.exportPortals([P], 1)).portals[0];
  const keys = Object.keys(entry).map((k) => k.toLowerCase());
  assert.ok(keys.includes('name'));
  for (const bad of ['password', 'username', 'credential', 'autofill', 'autosubmit', 'trusted', 'allowhttp', 'cookie']) {
    assert.ok(keys.every((k) => !k.includes(bad)), bad);
  }
});

test('round trip restores configuration but resets login automation', () => {
  const r = backup.parse(backup.exportPortals([P], 1));
  assert.equal(r.ok, true);
  const p = r.portals[0];
  assert.equal(p.name, 'My Portal');
  assert.equal(p.category, 'Visa');
  assert.equal(p.favorite, true);
  assert.equal(p.autoFill, false);
  assert.equal(p.autoSubmit, false);
  assert.deepEqual(p.extraTrustedHosts, []);
  assert.equal(r.rejected, 0);
});

test('rejects garbage / wrong format / wrong version / oversize', () => {
  assert.equal(backup.parse('nope').ok, false);
  assert.equal(backup.parse('{"format":"other","version":1,"portals":[]}').ok, false);
  assert.equal(backup.parse('{"format":"travelhub-portals","version":99,"portals":[]}').ok, false);
  assert.equal(backup.parse('{"format":"travelhub-portals","version":1}').ok, false);
  assert.equal(backup.parse('x'.repeat(backup.MAX_BYTES + 1)).ok, false);
});

test('skips invalid and http entries', () => {
  const text = JSON.stringify({ format: 'travelhub-portals', version: 1, portals: [
    { name: 'Good', url: 'https://good.example.com' }, { name: '', url: 'https://noname.example.com' },
    { name: 'Evil', url: 'javascript:alert(1)' }, { name: 'Plain', url: 'http://plain.example.com' }, 'junk'] });
  const r = backup.parse(text);
  assert.deepEqual(r.portals.map((p) => p.name), ['Good']);
  assert.equal(r.rejected, 4);
});

test('accepts the file format produced by the Android app', () => {
  // Shape produced by Android BackupCodec.export (keys/types identical).
  const android = JSON.stringify({ format: 'travelhub-portals', version: 1, exportedAt: 1700000000000,
    note: 'Contains no usernames or passwords. Re-enter credentials after restoring.',
    portals: [{ name: 'From Phone', url: 'https://phone.example.com/', category: 'Tours', notes: '', iconText: '', colorHex: '#1565C0',
      favorite: false, pinned: true, enabled: true, openMode: 'EXTERNAL', rememberSession: true, logoutUrl: '' }] });
  const r = backup.parse(android);
  assert.equal(r.ok, true);
  assert.equal(r.portals[0].openMode, 'EXTERNAL');
  assert.equal(r.portals[0].pinned, true);
});

test('importing into a repository skips duplicates', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'th-'));
  const repo = new PortalRepository(path.join(dir, 'p.json'));
  repo.add({ name: 'Existing', url: 'https://example.com/' });
  const s = repo.importAll(backup.parse(backup.exportPortals([P], 1)).portals);
  assert.deepEqual(s, { added: 0, duplicates: 1, invalid: 0 });
});
