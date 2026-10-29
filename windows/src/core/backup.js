'use strict';
const url = require('./url');
const { blankPortal } = require('./portals');

// Same file format as the Android app ("travelhub-portals" v1), so a list exported on one platform can be
// imported on the other. This is a manual configuration copy, NOT synchronization. It never contains
// usernames or passwords, and security-relevant login settings are intentionally not exported/restored.
const FORMAT = 'travelhub-portals';
const VERSION = 1;
const MAX_BYTES = 1000000;
const MAX_PORTALS = 500;

function exportPortals(portals, exportedAt) {
  const list = [...portals].sort((a, b) => a.sortIndex - b.sortIndex).map((p) => ({
    name: p.name, url: p.url, category: p.category, notes: p.notes, iconText: p.iconText, colorHex: p.colorHex,
    favorite: p.favorite, pinned: p.pinned, enabled: p.enabled, openMode: p.openMode,
    rememberSession: p.rememberSession, logoutUrl: p.logoutUrl,
  }));
  return JSON.stringify({
    format: FORMAT, version: VERSION, exportedAt,
    note: 'Contains no usernames or passwords. Re-enter credentials after restoring.',
    portals: list,
  }, null, 2);
}

/** @returns {{ok:true,portals:object[],rejected:number}|{ok:false,error:string}} */
function parse(text) {
  if (String(text).length > MAX_BYTES) return { ok: false, error: 'The file is too large to be a TravelHub backup' };
  let root;
  try { root = JSON.parse(text); } catch (e) { return { ok: false, error: 'This is not a valid TravelHub backup file' }; }
  if (!root || root.format !== FORMAT) return { ok: false, error: 'This is not a TravelHub portal backup' };
  if (!(Number.isInteger(root.version) && root.version >= 1 && root.version <= VERSION)) return { ok: false, error: 'Unsupported backup version' };
  if (!Array.isArray(root.portals)) return { ok: false, error: 'The backup contains no portal list' };
  if (root.portals.length > MAX_PORTALS) return { ok: false, error: 'The backup contains too many portals' };
  const out = [];
  let rejected = 0;
  for (const o of root.portals) {
    const name = o && typeof o.name === 'string' ? o.name.trim() : '';
    const n = o && typeof o.url === 'string' ? url.normalize(o.url) : { ok: false };
    // http:// portals are not restored: that choice needs an explicit warning/confirmation.
    if (!name || !n.ok || n.isHttp) { rejected++; continue; }
    const lo = url.normalizeLogoutUrl(typeof o.logoutUrl === 'string' ? o.logoutUrl : '');
    out.push(blankPortal({
      name: name.slice(0, 80), url: n.url,
      category: String(o.category || '').trim().slice(0, 40), notes: String(o.notes || '').slice(0, 2000),
      iconText: String(o.iconText || '').trim().slice(0, 2), colorHex: /^#[0-9A-Fa-f]{6}$/.test(o.colorHex || '') ? o.colorHex.toUpperCase() : '',
      favorite: o.favorite === true, pinned: o.pinned === true, enabled: o.enabled !== false,
      openMode: o.openMode === 'EXTERNAL' ? 'EXTERNAL' : 'IN_APP',
      rememberSession: o.rememberSession !== false, logoutUrl: lo.ok ? lo.url : '',
    }));
  }
  return { ok: true, portals: out, rejected };
}

module.exports = { FORMAT, VERSION, MAX_BYTES, MAX_PORTALS, exportPortals, parse };
