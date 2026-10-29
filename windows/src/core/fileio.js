'use strict';
const fs = require('fs');
const path = require('path');

/** Crash-safe write: temp file then rename over the target. */
function writeAtomic(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, text, 'utf8');
  fs.renameSync(tmp, file);
}

/** Moves an unreadable file aside instead of deleting user data. */
function quarantine(file) {
  try { if (fs.existsSync(file)) fs.renameSync(file, file + '.corrupt'); } catch (e) { /* best effort */ }
}

/** Reads JSON; on a missing file returns null, on corruption quarantines the file and returns null. */
function readJson(file) {
  if (!fs.existsSync(file)) return null;
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    quarantine(file);
    return null;
  }
}

module.exports = { writeAtomic, quarantine, readJson };
