'use strict';
const { writeAtomic, readJson } = require('./fileio');

const DEFAULTS = {
  theme: 'SYSTEM', view: 'LIST', sort: 'MANUAL', defaultOpenMode: 'IN_APP',
  autoLockSeconds: 300, blockScreenshots: true, showDisabled: false,
};
const ENUMS = {
  theme: ['SYSTEM', 'LIGHT', 'DARK'], view: ['LIST', 'GRID'], sort: ['MANUAL', 'ALPHABETICAL', 'RECENT'],
  defaultOpenMode: ['IN_APP', 'EXTERNAL'],
};
const LOCK_CHOICES = [60, 300, 900, 1800, -1]; // seconds; -1 = never

/** Returns a sanitized copy: unknown keys dropped, invalid values replaced by defaults. */
function sanitize(input) {
  const o = input && typeof input === 'object' ? input : {};
  const out = { ...DEFAULTS };
  for (const k of Object.keys(ENUMS)) if (ENUMS[k].includes(o[k])) out[k] = o[k];
  if (LOCK_CHOICES.includes(o.autoLockSeconds)) out.autoLockSeconds = o.autoLockSeconds;
  if (typeof o.blockScreenshots === 'boolean') out.blockScreenshots = o.blockScreenshots;
  if (typeof o.showDisabled === 'boolean') out.showDisabled = o.showDisabled;
  return out;
}

class SettingsStore {
  constructor(file) { this.file = file; this.value = sanitize(readJson(file)); }
  get() { return { ...this.value }; }
  update(patch) { this.value = sanitize({ ...this.value, ...patch }); writeAtomic(this.file, JSON.stringify(this.value)); return this.get(); }
}

module.exports = { SettingsStore, sanitize, DEFAULTS, LOCK_CHOICES };
