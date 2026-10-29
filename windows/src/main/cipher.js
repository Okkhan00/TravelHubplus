'use strict';
const { safeStorage } = require('electron');

/**
 * Credential cipher backed by Electron safeStorage. On Windows this is DPAPI: data is encrypted with a key
 * bound to the signed-in Windows user account and cannot be decrypted on another PC or by another Windows user.
 * It does NOT protect against other programs running as the same Windows user. There is deliberately NO
 * plaintext fallback: if encryption is unavailable, saving credentials fails.
 */
module.exports = {
  available: () => safeStorage.isEncryptionAvailable(),
  encrypt(buf) {
    if (!safeStorage.isEncryptionAvailable()) throw new Error('encryption unavailable');
    return safeStorage.encryptString(buf.toString('utf8'));
  },
  decrypt(blob) {
    return Buffer.from(safeStorage.decryptString(blob), 'utf8');
  },
};
