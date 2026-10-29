'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { decide } = require('../src/core/navigation');
const LS = require('../src/core/loginScript');

const hosts = ['portal.example.com'];
const d = (url, allowHttp = false) => decide(url, allowHttp, hosts);

test('https allowed', () => assert.equal(d('https://pay.gateway.net/x').action, 'allow'));
test('http blocked unless opted in and trusted', () => {
  assert.equal(d('http://portal.example.com/').action, 'block');
  assert.equal(d('http://portal.example.com/', true).action, 'allow');
  assert.equal(d('http://other.net/', true).action, 'block');
});
test('dangerous schemes blocked', () => {
  for (const x of ['file:///c:/x', 'javascript:alert(1)', 'data:text/html,hi', 'ftp://x.com', 'ms-msdt:/id', 'blob:https://x.com/1', 'vbscript:x']) {
    assert.equal(d(x).action, 'block', x);
  }
});
test('tel/mailto/sms go external', () => {
  for (const x of ['tel:+123456', 'mailto:a@b.com', 'sms:+123']) assert.equal(d(x).action, 'external');
});
test('intent never executed; only https fallback followed', () => {
  assert.equal(d('intent://scan/#Intent;scheme=zxing;package=com.x;end').action, 'block');
  assert.deepEqual(d('intent://x#Intent;scheme=a;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fapp;end'), { action: 'redirect', url: 'https://example.com/app' });
  assert.equal(d('intent://x#Intent;scheme=a;S.browser_fallback_url=http%3A%2F%2Fexample.com;end').action, 'block');
  assert.equal(d('intent://x#Intent;scheme=a;S.browser_fallback_url=file%3A%2F%2F%2Fetc;end').action, 'block');
});
test('about:blank only', () => {
  assert.equal(d('about:blank').action, 'allow');
  assert.equal(d('about:config').action, 'block');
});

test('login script quoting neutralises injection attempts', () => {
  const q = LS.quote('"; alert(1); //\\ \n </script> \u2028');
  assert.ok(!q.includes('\n') && !q.includes('</script>') && !q.includes('\u2028'));
  assert.equal(JSON.parse(q.replace(/\\u003c/g, '<').replace(/\\u003e/g, '>').replace(/\\u2028/g, '\u2028')), '"; alert(1); //\\ \n </script> \u2028');
});
test('fill script embeds only quoted values', () => {
  const s = LS.fill('user\'"x', 'p\\w', true);
  assert.ok(s.endsWith('("user\'\\"x","p\\\\w",true)'));
});
