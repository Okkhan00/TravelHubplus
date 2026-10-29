'use strict';
const crypto = require('crypto');
const { writeAtomic, readJson } = require('./fileio');
const url = require('./url');

const MAX_NAME = 80;
const MAX_NOTES = 2000;
const COLOR = /^#[0-9A-Fa-f]{6}$/;
const validColor = (c) => (COLOR.test(c || '') ? c.toUpperCase() : '');

/** Fields a portal record may contain. No secrets: credentials live only in the encrypted vault. */
const DEFAULT_PORTAL = {
  id: '', name: '', url: '', category: '', notes: '', iconText: '', colorHex: '',
  favorite: false, pinned: false, enabled: true, openMode: 'IN_APP', allowHttp: false,
  autoFill: false, autoSubmit: false, rememberSession: true, extraTrustedHosts: [], logoutUrl: '',
  sortIndex: 0, lastOpened: 0, createdAt: 0,
};

/** Builds a portal record from known fields only (unknown keys in `over` are ignored). */
function blankPortal(over = {}) {
  const o = { ...DEFAULT_PORTAL, extraTrustedHosts: [] };
  for (const k of Object.keys(DEFAULT_PORTAL)) if (over && k in over) o[k] = over[k];
  return o;
}

const trustedHostsOf = (p) => [...new Set([url.hostOf(p.url), ...p.extraTrustedHosts].filter(Boolean))];

/** Validates and cleans a draft. @returns {{ok:true,portal:object}|{ok:false,error:string}} */
function clean(p) {
  const name = String(p.name || '').trim();
  if (!name) return { ok: false, error: 'Enter a portal name' };
  if (name.length > MAX_NAME) return { ok: false, error: 'The name is too long' };
  const n = url.normalize(p.url);
  if (!n.ok) return n;
  if (n.isHttp && !p.allowHttp) return { ok: false, error: 'Plain http:// was not confirmed for this portal' };
  const hosts = (p.extraTrustedHosts || []).map((h) => String(h).trim().toLowerCase()).filter(Boolean);
  if (hosts.some((h) => !url.isValidHost(h))) return { ok: false, error: 'An extra trusted domain is invalid' };
  const lo = url.normalizeLogoutUrl(p.logoutUrl);
  if (!lo.ok) return lo;
  const autoFill = !!p.autoFill;
  return {
    ok: true,
    portal: {
      ...blankPortal(p),
      name, url: n.url,
      allowHttp: n.isHttp && !!p.allowHttp,
      category: String(p.category || '').trim().slice(0, 40),
      notes: String(p.notes || '').slice(0, MAX_NOTES),
      iconText: [...String(p.iconText || '').trim()].slice(0, 2).join(''),
      colorHex: validColor(p.colorHex),
      openMode: p.openMode === 'EXTERNAL' ? 'EXTERNAL' : 'IN_APP',
      favorite: !!p.favorite, pinned: !!p.pinned, enabled: p.enabled !== false,
      autoFill, autoSubmit: autoFill && !!p.autoSubmit, rememberSession: p.rememberSession !== false,
      extraTrustedHosts: [...new Set(hosts)], logoutUrl: lo.url,
    },
  };
}

class PortalRepository {
  constructor(file, clock = () => Date.now()) {
    this.file = file;
    this.clock = clock;
    this.items = this._load();
  }

  all() { return [...this.items].sort((a, b) => a.sortIndex - b.sortIndex); }
  get(id) { return this.items.find((p) => p.id === id) || null; }

  add(draft) {
    const c = clean(draft);
    if (!c.ok) return { status: 'invalid', error: c.error };
    const dup = this._dup(c.portal, null);
    if (dup) return { status: 'duplicate', existing: dup };
    const saved = {
      ...c.portal, id: crypto.randomUUID(),
      sortIndex: this.items.reduce((m, p) => Math.max(m, p.sortIndex), -1) + 1,
      createdAt: this.clock(), lastOpened: 0,
    };
    this.items.push(saved);
    this._persist();
    return { status: 'saved', portal: saved };
  }

  update(edited) {
    const old = this.get(edited.id);
    if (!old) return { status: 'invalid', error: 'This portal no longer exists' };
    const c = clean(edited);
    if (!c.ok) return { status: 'invalid', error: c.error };
    const dup = this._dup(c.portal, old.id);
    if (dup) return { status: 'duplicate', existing: dup };
    const saved = { ...c.portal, id: old.id, sortIndex: old.sortIndex, createdAt: old.createdAt, lastOpened: old.lastOpened };
    this.items[this.items.findIndex((p) => p.id === old.id)] = saved;
    this._persist();
    return { status: 'saved', portal: saved };
  }

  /** Flag-style change (favorite, pinned, enabled…) with no re-validation. */
  modify(id, change) {
    const i = this.items.findIndex((p) => p.id === id);
    if (i < 0) return null;
    this.items[i] = { ...this.items[i], ...change, id };
    this._persist();
    return this.items[i];
  }

  delete(id) {
    const n = this.items.length;
    this.items = this.items.filter((p) => p.id !== id);
    if (this.items.length !== n) { this._persist(); return true; }
    return false;
  }

  markOpened(id) { this.modify(id, { lastOpened: this.clock() }); }

  move(id, delta) {
    const sorted = this.all();
    const i = sorted.findIndex((p) => p.id === id);
    const j = i + delta;
    if (i < 0 || j < 0 || j >= sorted.length) return;
    [sorted[i], sorted[j]] = [sorted[j], sorted[i]];
    this.items = sorted.map((p, idx) => ({ ...p, sortIndex: idx }));
    this._persist();
  }

  importAll(incoming) {
    let added = 0, duplicates = 0, invalid = 0;
    for (const p of incoming) {
      const r = this.add({ ...p, id: '' });
      if (r.status === 'saved') added++; else if (r.status === 'duplicate') duplicates++; else invalid++;
    }
    return { added, duplicates, invalid };
  }

  clear() { this.items = []; this._persist(); }

  _dup(p, ignoreId) {
    const key = url.duplicateKey(p.url);
    return this.items.find((q) => q.id !== ignoreId && url.duplicateKey(q.url) === key) || null;
  }

  _persist() { writeAtomic(this.file, JSON.stringify({ version: 1, portals: this.items })); }

  _load() {
    const root = readJson(this.file);
    if (!root || !Array.isArray(root.portals)) return [];
    const out = [];
    for (const o of root.portals) {
      if (!o || typeof o !== 'object' || !o.id) continue;
      const c = clean({ ...o, allowHttp: o.allowHttp });
      if (c.ok) out.push({ ...c.portal, id: String(o.id), sortIndex: Number(o.sortIndex) || 0, lastOpened: Number(o.lastOpened) || 0, createdAt: Number(o.createdAt) || 0 });
    }
    return out;
  }
}

const SUGGESTED_CATEGORIES = ['Flights', 'Hotels', 'Visa', 'Umrah', 'Tours', 'Finance', 'Other'];

function categoriesOf(all) {
  const seen = new Map();
  for (const p of all) { const c = p.category.trim(); if (c && !seen.has(c.toLowerCase())) seen.set(c.toLowerCase(), c); }
  return [...seen.values()].sort((a, b) => a.toLowerCase().localeCompare(b.toLowerCase()));
}

/** Search / filter / sort for the dashboard. Pinned portals always come first (stable). */
function applyFilter(all, { query = '', category = null, favoritesOnly = false, showDisabled = false, sort = 'MANUAL' } = {}) {
  const q = query.trim().toLowerCase();
  const list = all.filter((p) =>
    (showDisabled || p.enabled) && (!favoritesOnly || p.favorite) &&
    (category == null || p.category.toLowerCase() === category.toLowerCase()) &&
    (!q || p.name.toLowerCase().includes(q) || (url.hostOf(p.url) || '').includes(q) || p.category.toLowerCase().includes(q)));
  const byName = (a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase());
  if (sort === 'ALPHABETICAL') list.sort(byName);
  else if (sort === 'RECENT') list.sort((a, b) => b.lastOpened - a.lastOpened || byName(a, b));
  else list.sort((a, b) => a.sortIndex - b.sortIndex);
  return [...list.filter((p) => p.pinned), ...list.filter((p) => !p.pinned)];
}

module.exports = { PortalRepository, blankPortal, clean, trustedHostsOf, categoriesOf, applyFilter, SUGGESTED_CATEGORIES, MAX_NAME, MAX_NOTES };
