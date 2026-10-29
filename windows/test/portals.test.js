'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { PortalRepository, applyFilter, categoriesOf, blankPortal } = require('../src/core/portals');

const tmpFile = () => path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'th-')), 'portals.json');
const draft = (name = 'Test Portal', url = 'https://example.com', over = {}) => ({ name, url, ...over });

test('starts empty', () => assert.deepEqual(new PortalRepository(tmpFile()).all(), []));

test('add accepts user-entered name and URL', () => {
  const r = new PortalRepository(tmpFile());
  const res = r.add(draft('My Portal', 'portal.example.org'));
  assert.equal(res.status, 'saved');
  assert.equal(res.portal.url, 'https://portal.example.org/');
  assert.ok(res.portal.id);
  assert.equal(r.all().length, 1);
});

test('rejects blank name and bad URL', () => {
  const r = new PortalRepository(tmpFile());
  assert.equal(r.add(draft('  ')).status, 'invalid');
  assert.equal(r.add(draft('x', 'javascript:alert(1)')).status, 'invalid');
  assert.equal(r.all().length, 0);
});

test('http requires explicit opt-in', () => {
  const r = new PortalRepository(tmpFile());
  assert.equal(r.add(draft('a', 'http://example.com')).status, 'invalid');
  const ok = r.add(draft('a', 'http://example.com', { allowHttp: true }));
  assert.equal(ok.status, 'saved');
  assert.equal(ok.portal.allowHttp, true);
});

test('duplicate URL detected (scheme/case/slash-insensitive)', () => {
  const r = new PortalRepository(tmpFile());
  r.add(draft('A', 'https://example.com/x'));
  assert.equal(r.add(draft('B', 'http://EXAMPLE.com/x/', { allowHttp: true })).status, 'duplicate');
  assert.equal(r.all().length, 1);
});

test('edit keeps id/order and detects duplicates', () => {
  const r = new PortalRepository(tmpFile());
  const a = r.add(draft('A', 'https://a.example.com')).portal;
  const b = r.add(draft('B', 'https://b.example.com')).portal;
  const e = r.update({ ...a, name: 'A2', category: 'Hotels' });
  assert.equal(e.status, 'saved');
  assert.equal(e.portal.id, a.id);
  assert.equal(e.portal.sortIndex, a.sortIndex);
  assert.equal(r.get(a.id).name, 'A2');
  assert.equal(r.update({ ...b, url: 'https://a.example.com' }).status, 'duplicate');
  assert.equal(r.update({ ...a, name: 'A3' }).status, 'saved');
});

test('delete removes', () => {
  const r = new PortalRepository(tmpFile());
  const a = r.add(draft()).portal;
  assert.equal(r.delete(a.id), true);
  assert.equal(r.delete(a.id), false);
  assert.equal(r.all().length, 0);
});

test('data persists across instances', () => {
  const f = tmpFile();
  const a = new PortalRepository(f).add(draft('Persist', 'https://persist.example.com', { category: 'Visa', favorite: true })).portal;
  const loaded = new PortalRepository(f).all();
  assert.equal(loaded.length, 1);
  assert.deepEqual(loaded[0], a);
});

test('corrupted file is quarantined and the app starts empty', () => {
  const f = tmpFile();
  fs.writeFileSync(f, '{ not json');
  const r = new PortalRepository(f);
  assert.equal(r.all().length, 0);
  assert.ok(fs.existsSync(f + '.corrupt'));
  assert.equal(r.add(draft()).status, 'saved');
});

test('move reorders', () => {
  const r = new PortalRepository(tmpFile());
  r.add(draft('A', 'https://a.example.com'));
  const b = r.add(draft('B', 'https://b.example.com')).portal;
  r.move(b.id, -1);
  assert.deepEqual(r.all().map((p) => p.name), ['B', 'A']);
  r.move(b.id, -1);
  assert.deepEqual(r.all().map((p) => p.name), ['B', 'A']);
});

test('autoSubmit requires autoFill; unknown keys are ignored', () => {
  const r = new PortalRepository(tmpFile());
  const p = r.add(draft('a', 'https://a.example.com', { autoFill: false, autoSubmit: true, evil: 'x', password: 'secret' })).portal;
  assert.equal(p.autoSubmit, false);
  assert.equal('evil' in p, false);
  assert.equal('password' in p, false);
});

test('portal file contains no credential fields', () => {
  const f = tmpFile();
  new PortalRepository(f).add(draft('a', 'https://a.example.com', { username: 'bob', password: 'hunter2' }));
  const text = fs.readFileSync(f, 'utf8').toLowerCase();
  assert.ok(!text.includes('password') && !text.includes('username') && !text.includes('hunter2'));
});

const A = blankPortal({ id: '1', name: 'Alpha Hotels', url: 'https://alpha.example.com/', category: 'Hotels', sortIndex: 0, lastOpened: 5 });
const B = blankPortal({ id: '2', name: 'Bravo Flights', url: 'https://bravo.example.net/', category: 'Flights', sortIndex: 1, favorite: true, lastOpened: 9 });
const C = blankPortal({ id: '3', name: 'Charlie', url: 'https://charlie.example.org/', category: 'hotels', sortIndex: 2, enabled: false, pinned: true });
const ids = (o) => applyFilter([A, B, C], o).map((p) => p.id);

test('filter: hides disabled by default; pinned first when shown', () => {
  assert.deepEqual(ids({}), ['1', '2']);
  assert.deepEqual(ids({ showDisabled: true }), ['3', '1', '2']);
});
test('filter: search by name and domain, category case-insensitive, favorites', () => {
  assert.deepEqual(ids({ query: 'bravo' }), ['2']);
  assert.deepEqual(ids({ query: 'alpha.example' }), ['1']);
  assert.deepEqual(ids({ category: 'Hotels', showDisabled: true }), ['3', '1']);
  assert.deepEqual(ids({ favoritesOnly: true }), ['2']);
});
test('filter: sorting and categories', () => {
  assert.deepEqual(ids({ sort: 'RECENT' }), ['2', '1']);
  assert.deepEqual(ids({ sort: 'ALPHABETICAL' }), ['1', '2']);
  assert.deepEqual(categoriesOf([A, B, C]), ['Flights', 'Hotels']);
});
