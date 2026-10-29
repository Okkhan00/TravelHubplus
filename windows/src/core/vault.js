'use strict';
const { writeAtomic, readJson } = require('./fileio');

/**
 * Encrypted credential vault in its own file. The cipher is injected:
 *  - production: Electron safeStorage (Windows DPAPI, tied to the signed-in Windows user account);
 *  - tests: an AES-GCM cipher built on node:crypto.
 * safeStorage has no associated-data feature, so the record id is placed INSIDE the encrypted payload and
 * checked on decrypt, which stops one portal's blob from being swapped into another's slot.
 * Failures return null/false and never expose secrets in errors or logs.
 */
class CredentialStore {
  /** @param {{encrypt:(b:Buffer)=>Buffer, decrypt:(b:Buffer)=>Buffer}} cipher */
  constructor(file, cipher) {
    this.file = file;
    this.cipher = cipher;
    const root = readJson(file);
    this.entries = root && root.entries && typeof root.entries === 'object' ? { ...root.entries } : {};
  }

  has(id) { return Object.prototype.hasOwnProperty.call(this.entries, id); }
  ids() { return Object.keys(this.entries); }
  count() { return this.ids().length; }

  save(id, creds) {
    try {
      const plain = Buffer.from(JSON.stringify({ i: id, u: String(creds.username || ''), p: String(creds.password || '') }), 'utf8');
      let blob;
      try { blob = this.cipher.encrypt(plain); } finally { plain.fill(0); }
      this.entries[id] = Buffer.from(blob).toString('base64');
      this._persist();
      return true;
    } catch (e) { return false; }
  }

  /** @returns {{username:string,password:string}|null} null if missing, corrupted or undecryptable. */
  get(id) {
    if (!this.has(id)) return null;
    try {
      const plain = this.cipher.decrypt(Buffer.from(this.entries[id], 'base64'));
      try {
        const o = JSON.parse(plain.toString('utf8'));
        if (o.i !== id) return null;
        return { username: String(o.u || ''), password: String(o.p || '') };
      } finally { plain.fill(0); }
    } catch (e) { return null; }
  }

  delete(id) { if (this.has(id)) { delete this.entries[id]; this._persist(); } }
  deleteAll() { this.entries = {}; this._persist(); }
  _persist() { writeAtomic(this.file, JSON.stringify({ version: 1, entries: this.entries })); }
}

module.exports = { CredentialStore };
