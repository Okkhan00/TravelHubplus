'use strict';
const crypto = require('crypto');
const { promisify } = require('util');
const scrypt = promisify(crypto.scrypt);

const FORMAT = /^\d{4,12}$/;
const FREE_ATTEMPTS = 5;
const BASE_LOCK_MS = 30000;
const MAX_LOCK_MS = 3600000;
const SCRYPT = { N: 16384, r: 8, p: 1 };

/**
 * App-lock PIN: scrypt with a unique random salt, constant-time comparison, escalating lockouts.
 * The PIN itself is never stored. `kv` is a tiny get/set object so it can be unit-tested.
 */
class PinManager {
  /** @param {{get:(k:string)=>any, set:(k:string,v:any)=>void, remove:(...k:string[])=>void}} kv */
  constructor(kv, clock = () => Date.now(), params = SCRYPT) {
    this.kv = kv;
    this.clock = clock;
    this.params = params;
  }

  isSet() { return !!this.kv.get('hash'); }
  isValidFormat(pin) { return FORMAT.test(String(pin)); }

  async setPin(pin) {
    if (!this.isValidFormat(pin)) return false;
    const salt = crypto.randomBytes(16);
    const hash = await scrypt(String(pin), salt, 32, this.params);
    this.kv.set('salt', salt.toString('base64'));
    this.kv.set('hash', Buffer.from(hash).toString('base64'));
    this.kv.remove('fails', 'lockUntil');
    return true;
  }

  /** @returns {Promise<{status:'success'}|{status:'wrong',attemptsLeft:number}|{status:'locked',until:number}>} */
  async verify(pin) {
    const now = this.clock();
    const until = this.kv.get('lockUntil') || 0;
    if (now < until) return { status: 'locked', until };
    const saltB64 = this.kv.get('salt');
    const hashB64 = this.kv.get('hash');
    if (!saltB64 || !hashB64) return { status: 'wrong', attemptsLeft: 0 };
    const actual = Buffer.from(await scrypt(String(pin), Buffer.from(saltB64, 'base64'), 32, this.params));
    const expected = Buffer.from(hashB64, 'base64');
    if (actual.length === expected.length && crypto.timingSafeEqual(actual, expected)) {
      this.kv.remove('fails', 'lockUntil');
      return { status: 'success' };
    }
    const fails = (this.kv.get('fails') || 0) + 1;
    this.kv.set('fails', fails);
    if (fails >= FREE_ATTEMPTS) {
      const delay = Math.min(BASE_LOCK_MS * 2 ** Math.min(fails - FREE_ATTEMPTS, 7), MAX_LOCK_MS);
      this.kv.set('lockUntil', now + delay);
      return { status: 'locked', until: now + delay };
    }
    return { status: 'wrong', attemptsLeft: FREE_ATTEMPTS - fails };
  }

  clear() { this.kv.remove('salt', 'hash', 'fails', 'lockUntil'); }
}

/** File-backed key/value store for the PIN record. */
class FileKv {
  constructor(file, io) { this.file = file; this.io = io; this.data = io.readJson(file) || {}; }
  get(k) { return this.data[k]; }
  set(k, v) { this.data[k] = v; this._save(); }
  remove(...ks) { ks.forEach((k) => delete this.data[k]); this._save(); }
  _save() { this.io.writeAtomic(this.file, JSON.stringify(this.data)); }
}

module.exports = { PinManager, FileKv, FREE_ATTEMPTS };
