'use strict';
// URL / host validation. Mirrors the Android app's UrlValidator + TrustedDomain.

const SCHEME = /^[A-Za-z][A-Za-z0-9+.-]*:\/\//;
const HOST = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/;
const IPV4 = /^(?:\d{1,3}\.){3}\d{1,3}$/;

function isValidHost(host) {
  return HOST.test(host) || IPV4.test(host);
}

/** @returns {{ok:true,url:string,isHttp:boolean}|{ok:false,error:string}} */
function normalize(input) {
  const t = String(input == null ? '' : input).trim();
  if (!t) return { ok: false, error: 'Enter the website address' };
  if (t.length > 2048) return { ok: false, error: 'The address is too long' };
  if (/[\s\u0000-\u001f\u007f]/.test(t)) return { ok: false, error: 'The address must not contain spaces' };
  const candidate = SCHEME.test(t) ? t : 'https://' + t;
  let u;
  try { u = new URL(candidate); } catch (e) { return { ok: false, error: "That doesn't look like a valid address" }; }
  const scheme = u.protocol.replace(':', '').toLowerCase();
  if (scheme !== 'https' && scheme !== 'http') return { ok: false, error: 'Only https:// (or http://) addresses are supported' };
  if (u.username || u.password) return { ok: false, error: "Don't put a username or password in the address" };
  const host = u.hostname.toLowerCase();
  if (!isValidHost(host)) return { ok: false, error: 'Enter a full domain name such as example.com' };
  const port = u.port ? ':' + u.port : '';
  return { ok: true, url: `${scheme}://${host}${port}${u.pathname || '/'}${u.search}${u.hash}`, isHttp: scheme === 'http' };
}

function hostOf(url) {
  try { return new URL(url).hostname.toLowerCase() || null; } catch (e) { return null; }
}

/** Scheme-insensitive key used to detect duplicate portals. */
function duplicateKey(url) {
  try {
    const u = new URL(url);
    return u.hostname.toLowerCase() + (u.port ? ':' + u.port : '') + u.pathname.replace(/\/+$/, '') + u.search;
  } catch (e) { return String(url).toLowerCase(); }
}

/** Parses comma/space separated hosts. Returns null if any entry is invalid. */
function parseHosts(text) {
  const parts = String(text || '').split(/[,\s;]+/).map((s) => s.trim().toLowerCase()).filter(Boolean);
  if (parts.length > 10) return null;
  if (parts.some((p) => !isValidHost(p))) return null;
  return [...new Set(parts)];
}

function normalizeLogoutUrl(input) {
  if (!String(input || '').trim()) return { ok: true, url: '', isHttp: false };
  const r = normalize(input);
  if (r.ok && r.isHttp) return { ok: false, error: 'The sign-out address must use https://' };
  return r;
}

/**
 * True only if url is https (or http when explicitly opted in), has no embedded user info, and its host
 * equals — or is a subdomain of — one of trustedHosts. Checked before any credential is filled.
 */
function isTrusted(url, trustedHosts, allowHttp) {
  if (!url) return false;
  let u;
  try { u = new URL(url); } catch (e) { return false; }
  const scheme = u.protocol.replace(':', '').toLowerCase();
  if (scheme !== 'https' && !(scheme === 'http' && allowHttp)) return false;
  if (u.username || u.password) return false;
  const host = u.hostname.toLowerCase();
  if (!host) return false;
  return trustedHosts.some((t) => {
    const th = String(t).trim().toLowerCase();
    return th !== '' && (host === th || host.endsWith('.' + th));
  });
}

module.exports = { isValidHost, normalize, hostOf, duplicateKey, parseHosts, normalizeLogoutUrl, isTrusted };
