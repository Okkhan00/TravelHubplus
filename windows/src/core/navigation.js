'use strict';
const { isTrusted } = require('./url');

const FALLBACK = /S\.browser_fallback_url=([^;]+)/;

/**
 * Decides what an in-app browser may do with a navigation. Same policy as the Android app:
 *  - https loads (credentials are still only filled on trusted hosts);
 *  - http only for explicitly opted-in portals and only on their trusted hosts;
 *  - tel:/mailto: go to the OS (only on explicit user-initiated navigations);
 *  - intent:// never executed, only an https fallback URL is followed;
 *  - file:, javascript:, data:, blob:, ftp:, custom schemes are blocked.
 * @returns {{action:'allow'}|{action:'redirect',url:string}|{action:'external',url:string}|{action:'block',reason:string}}
 */
function decide(url, allowHttp, trustedHosts, depth = 0) {
  let u;
  try { u = new URL(String(url).trim()); } catch (e) { return { action: 'block', reason: 'Invalid address' }; }
  const scheme = u.protocol.replace(':', '').toLowerCase();
  switch (scheme) {
    case 'https':
      return { action: 'allow' };
    case 'http':
      return allowHttp && isTrusted(url, trustedHosts, true)
        ? { action: 'allow' }
        : { action: 'block', reason: 'Blocked an insecure http:// page' };
    case 'about':
      return String(url).toLowerCase() === 'about:blank' ? { action: 'allow' } : { action: 'block', reason: 'Blocked' };
    case 'tel':
    case 'mailto':
    case 'sms':
      return { action: 'external', url };
    case 'intent': {
      const m = FALLBACK.exec(url);
      let fallback = null;
      if (m) { try { fallback = decodeURIComponent(m[1]); } catch (e) { fallback = null; } }
      if (fallback && depth === 0 && /^https:\/\//i.test(fallback) && decide(fallback, allowHttp, trustedHosts, 1).action === 'allow') {
        return { action: 'redirect', url: fallback };
      }
      return { action: 'block', reason: 'Blocked an app-launch link' };
    }
    default:
      return { action: 'block', reason: 'Blocked an unsupported link type' };
  }
}

module.exports = { decide };
