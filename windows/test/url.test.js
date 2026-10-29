'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const u = require('../src/core/url');

test('missing scheme becomes https and path defaults to /', () => {
  const r = u.normalize('Example.com');
  assert.deepEqual(r, { ok: true, url: 'https://example.com/', isHttp: false });
});
test('http is flagged, never upgraded or downgraded', () => {
  const r = u.normalize('http://example.com/login');
  assert.equal(r.isHttp, true);
  assert.equal(r.url, 'http://example.com/login');
});
test('keeps port, path and query', () => {
  assert.equal(u.normalize('https://a.example.com:8443/x/y?z=1').url, 'https://a.example.com:8443/x/y?z=1');
});
test('rejects malformed and dangerous input', () => {
  for (const bad of ['', '   ', 'javascript:alert(1)', 'file:///etc/passwd', 'ftp://example.com',
    'intent://scan/#Intent;scheme=zxing;end', 'https://user:pass@example.com', 'not a url', 'https://localhost',
    'https://exa_mple.com', 'https://', 'https://example.com:abc', 'a'.repeat(3000)]) {
    assert.equal(u.normalize(bad).ok, false, `expected error for ${JSON.stringify(bad).slice(0, 40)}`);
  }
});
test('duplicate key ignores scheme, case and trailing slash', () => {
  assert.equal(u.duplicateKey('https://Example.com/a/'), u.duplicateKey('http://example.com/a'));
  assert.notEqual(u.duplicateKey('https://example.com/a'), u.duplicateKey('https://example.com/b'));
});
test('parseHosts', () => {
  assert.deepEqual(u.parseHosts('A.com, b.org a.com'), ['a.com', 'b.org']);
  assert.equal(u.parseHosts('a.com, bad host!'), null);
  assert.deepEqual(u.parseHosts(''), []);
});
test('logout URL must be https', () => {
  assert.equal(u.normalizeLogoutUrl('').ok, true);
  assert.equal(u.normalizeLogoutUrl('http://example.com/out').ok, false);
  assert.equal(u.normalizeLogoutUrl('example.com/out').ok, true);
});

const hosts = ['portal.example.com'];
test('trusted: exact and subdomains', () => {
  assert.ok(u.isTrusted('https://portal.example.com/login', hosts, false));
  assert.ok(u.isTrusted('https://sso.portal.example.com/', hosts, false));
});
test('trusted: look-alikes and parents rejected', () => {
  assert.ok(!u.isTrusted('https://evilportal.example.com/', hosts, false));
  assert.ok(!u.isTrusted('https://portal.example.com.evil.net/', hosts, false));
  assert.ok(!u.isTrusted('https://example.com/', hosts, false));
  assert.ok(!u.isTrusted('https://evil.net/?u=portal.example.com', hosts, false));
  assert.ok(!u.isTrusted('https://portal.example.com@evil.net/', hosts, false));
});
test('trusted: http only with explicit opt-in; other schemes and null rejected', () => {
  assert.ok(!u.isTrusted('http://portal.example.com/', hosts, false));
  assert.ok(u.isTrusted('http://portal.example.com/', hosts, true));
  assert.ok(!u.isTrusted(null, hosts, true));
  assert.ok(!u.isTrusted('javascript:alert(1)', hosts, true));
  assert.ok(!u.isTrusted('file://portal.example.com/x', hosts, true));
});
