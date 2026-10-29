'use strict';
const { app, BrowserWindow, ipcMain, dialog, shell, nativeTheme, powerMonitor, Menu, session } = require('electron');
const fs = require('fs');
const os = require('os');
const path = require('path');

const SELFTEST = process.argv.includes('--selftest');
if (SELFTEST) app.setPath('userData', fs.mkdtempSync(path.join(os.tmpdir(), 'travelhub-selftest-')));

// Self-test diagnostics (CI only): a stage trace that survives hangs, a watchdog, and no modal crash dialogs.
const TRACE = SELFTEST ? process.env.TRAVELHUB_SELFTEST_OUT : null;
const trace = (line) => { if (TRACE) { try { fs.appendFileSync(TRACE, line + '\n'); } catch (e) { /* ignore */ } } };
if (SELFTEST) {
  trace('stage: main.js loaded');
  process.on('uncaughtException', (e) => { trace('FAIL  uncaught exception: ' + String(e && e.stack || e).slice(0, 600)); app.exit(2); });
  process.on('unhandledRejection', (e) => { trace('FAIL  unhandled rejection: ' + String(e && e.stack || e).slice(0, 600)); app.exit(2); });
  setTimeout(() => { trace('FAIL  watchdog: self-test stalled (see last stage above)'); app.exit(3); }, 60000).unref();
}

const io = require('../core/fileio');
const { PortalRepository, trustedHostsOf, categoriesOf, SUGGESTED_CATEGORIES, blankPortal } = require('../core/portals');
const { CredentialStore } = require('../core/vault');
const { PinManager, FileKv } = require('../core/pin');
const { SettingsStore } = require('../core/settings');
const backup = require('../core/backup');
const url = require('../core/url');
const cipher = require('./cipher');
const { BrowserManager } = require('./browser');

let win = null;
let repo, vault, pin, settings, browser;
let locked = false;
let authUntil = 0; // re-authentication window for sensitive actions
let lockTimer = null;

// ---------- helpers ----------
const withTrusted = (p) => ({ ...p, host: url.hostOf(p.url) || '', trustedHosts: trustedHostsOf(p) });
const send = (ch, payload) => { if (win && !win.isDestroyed()) win.webContents.send(ch, payload); };
const toast = (msg) => send('toast', String(msg));

function buildState() {
  return {
    locked, pinSet: pin.isSet(), settings: settings.get(), suggested: SUGGESTED_CATEGORIES,
    portals: locked ? [] : repo.all().map((p) => ({ ...p, hasLogin: vault.has(p.id) })),
    categories: locked ? [] : categoriesOf(repo.all()),
    encryptionAvailable: cipher.available(),
  };
}

async function clearPortalSession(id) {
  const ses = session.fromPartition('persist:portal-' + id);
  await ses.clearStorageData();
  await ses.clearCache();
}
async function clearAllSessions() {
  if (browser) browser.close();
  for (const p of repo.all()) { try { await clearPortalSession(p.id); } catch (e) { /* best effort */ } }
}

function lockNow() {
  if (!pin.isSet() || locked) return;
  locked = true;
  authUntil = 0;
  if (browser) browser.close(); // like Android: the page is dropped on lock; sign-in cookies remain per portal
  send('lock:changed', buildState());
}

function armLockTimer() {
  clearTimeout(lockTimer);
  const s = settings.get().autoLockSeconds;
  if (!pin.isSet() || locked || s < 0) return;
  lockTimer = setTimeout(lockNow, s * 1000);
}
const disarmLockTimer = () => clearTimeout(lockTimer);

function applyWindowSettings() {
  const s = settings.get();
  nativeTheme.themeSource = s.theme.toLowerCase();
  if (win) win.setContentProtection(!!s.blockScreenshots); // blocks screen capture / blanks the preview
}

/** Registers an IPC handler that only the app's own UI window may call. */
function handle(name, fn, { allowLocked = false, sensitive = false } = {}) {
  ipcMain.handle(name, async (event, arg) => {
    if (!win || event.sender !== win.webContents) return { error: 'denied' };
    if (locked && !allowLocked) return { error: 'locked' };
    if (sensitive && pin.isSet() && Date.now() > authUntil) return { error: 'auth' };
    try { return await fn(arg || {}); } catch (e) { return { error: 'Something went wrong' }; } // never echo details
  });
}

function pickDraft(d) {
  const o = d && typeof d === 'object' ? d : {};
  return blankPortal({ ...o, extraTrustedHosts: Array.isArray(o.extraTrustedHosts) ? o.extraTrustedHosts.map(String) : [] });
}

// ---------- IPC ----------
function registerIpc() {
  handle('state:get', () => buildState(), { allowLocked: true });

  handle('portal:save', async ({ draft, username, password }) => {
    const d = pickDraft(draft);
    const u = typeof username === 'string' ? username.trim() : '';
    const pw = typeof password === 'string' ? password : '';
    const old = d.id ? repo.get(d.id) : null;
    const res = d.id ? repo.update(d) : repo.add(d);
    if (res.status === 'invalid') return { error: res.error };
    if (res.status === 'duplicate') return { error: `A portal for this address already exists: ${res.existing.name}` };
    const id = res.portal.id;
    let note = null;
    const hostChanged = !!old && url.hostOf(old.url) !== url.hostOf(res.portal.url);
    if (hostChanged && vault.has(id)) { // never silently re-point saved credentials at a different website
      vault.delete(id);
      note = 'Website changed: the saved login was removed for safety.';
    }
    if (u || pw) {
      if (!cipher.available()) return { error: 'Secure storage is unavailable on this PC, so the login was not saved.' };
      const existing = hostChanged ? null : vault.get(id);
      const ok = vault.save(id, { username: u || (existing ? existing.username : ''), password: pw || (existing ? existing.password : '') });
      if (!ok) note = 'Portal saved, but the login could not be stored securely.';
    }
    return { ok: true, note };
  });

  handle('portal:delete', async ({ id }) => {
    repo.delete(String(id)); vault.delete(String(id));
    if (browser.portal && browser.portal.id === id) browser.close();
    try { await clearPortalSession(String(id)); } catch (e) { /* best effort */ }
    return { ok: true };
  });
  handle('portal:modify', ({ id, patch }) => {
    const allowed = {};
    for (const k of ['favorite', 'pinned', 'enabled']) if (patch && typeof patch[k] === 'boolean') allowed[k] = patch[k];
    repo.modify(String(id), allowed);
    return { ok: true };
  });
  handle('portal:move', ({ id, delta }) => { repo.move(String(id), delta < 0 ? -1 : 1); return { ok: true }; });
  handle('portal:forgetLogin', ({ id }) => { vault.delete(String(id)); return { ok: true }; });
  handle('portal:clearSession', async ({ id }) => {
    const p = repo.get(String(id));
    if (!p) return { error: 'Portal not found' };
    await browser.clearSession(withTrusted(p));
    return { ok: true };
  });

  handle('portal:open', ({ id }) => {
    const p = repo.get(String(id));
    if (!p) return { error: 'Portal not found' };
    if (!p.enabled) return { error: 'This portal is disabled. Enable it from its menu.' };
    repo.markOpened(p.id);
    if (p.openMode === 'EXTERNAL') { shell.openExternal(p.url).catch(() => {}); return { external: true }; }
    const st = browser.open(withTrusted(p));
    st.hasLogin = vault.has(p.id);
    return { browser: true };
  });

  handle('portal:openExternal', ({ id }) => {
    const p = repo.get(String(id));
    if (!p) return { error: 'Portal not found' };
    if (!p.enabled) return { error: 'This portal is disabled. Enable it from its menu.' };
    repo.markOpened(p.id);
    shell.openExternal(p.url).catch(() => {});
    return { external: true };
  });

  handle('browser:action', async ({ action, submit }) => {
    switch (action) {
      case 'back': browser.back(); break;
      case 'forward': browser.forward(); break;
      case 'reload': browser.reload(); break;
      case 'stop': browser.stop(); break;
      case 'close': browser.close(); break;
      case 'external': browser.openExternally(); break;
      case 'signout': browser.signOut(); break;
      case 'fill': await browser.fill(!!submit, true); break;
      case 'show': browser.setVisible(true); break;
      case 'hide': browser.setVisible(false); break;
      default: return { error: 'Unknown action' };
    }
    return { ok: true };
  });

  handle('settings:update', (patch) => { const s = settings.update(patch); applyWindowSettings(); armLockTimer(); return { ok: true, settings: s }; });

  // --- lock ---
  handle('lock:unlock', async ({ pin: p }) => {
    const r = await pin.verify(String(p || ''));
    if (r.status === 'success') { locked = false; armLockTimer(); }
    return r;
  }, { allowLocked: true });
  handle('lock:lock', () => { lockNow(); return { ok: true }; });
  handle('lock:reauth', async ({ pin: p }) => {
    const r = await pin.verify(String(p || ''));
    if (r.status === 'success') authUntil = Date.now() + 60000;
    return r;
  });
  handle('lock:setPin', async ({ pin: p }) => {
    if (pin.isSet()) return { error: 'A PIN is already set' };
    const ok = await pin.setPin(String(p || ''));
    if (ok) armLockTimer();
    return ok ? { ok: true } : { error: 'Use 4 to 12 digits' };
  });
  handle('lock:changePin', async ({ current, next }) => {
    const r = await pin.verify(String(current || ''));
    if (r.status !== 'success') return r;
    return (await pin.setPin(String(next || ''))) ? { status: 'success' } : { error: 'Use 4 to 12 digits' };
  });
  handle('lock:removePin', async ({ current }) => {
    const r = await pin.verify(String(current || ''));
    if (r.status === 'success') { pin.clear(); disarmLockTimer(); }
    return r;
  });
  // "Forgot PIN": a PIN is never revealed or bypassed. Recovery erases saved logins and sessions, then removes the lock.
  handle('lock:forgot', async () => {
    vault.deleteAll();
    await clearAllSessions();
    pin.clear(); locked = false; disarmLockTimer();
    return { ok: true };
  }, { allowLocked: true });

  // --- data ---
  handle('data:export', async () => {
    if (browser) browser.setVisible(false);
    const r = await dialog.showSaveDialog(win, { title: 'Export portal list', defaultPath: 'travelhub-portals.json', filters: [{ name: 'JSON', extensions: ['json'] }] });
    if (r.canceled || !r.filePath) return { ok: false, canceled: true };
    fs.writeFileSync(r.filePath, backup.exportPortals(repo.all(), Date.now()), 'utf8');
    return { ok: true };
  }, { sensitive: true });
  handle('data:import', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Import portal list', properties: ['openFile'], filters: [{ name: 'JSON', extensions: ['json'] }] });
    if (r.canceled || !r.filePaths[0]) return { ok: false, canceled: true };
    const f = r.filePaths[0];
    if (fs.statSync(f).size > backup.MAX_BYTES) return { error: 'The file is too large to be a TravelHub backup' };
    const parsed = backup.parse(fs.readFileSync(f, 'utf8'));
    if (!parsed.ok) return { error: parsed.error };
    const s = repo.importAll(parsed.portals);
    const bad = s.invalid + parsed.rejected;
    return { ok: true, message: `Imported ${s.added}, skipped ${s.duplicates} duplicate(s) and ${bad} invalid/insecure entr${bad === 1 ? 'y' : 'ies'}. Re-enter logins; they are not in backups.` };
  });
  handle('data:clearBrowsing', async () => { await clearAllSessions(); await session.defaultSession.clearStorageData(); return { ok: true }; });
  handle('data:deleteCreds', () => { vault.deleteAll(); return { ok: true }; }, { sensitive: true });
  handle('data:deleteAll', async () => { await clearAllSessions(); repo.clear(); vault.deleteAll(); return { ok: true }; }, { sensitive: true });

  handle('app:about', () => ({
    version: app.getVersion(), electron: process.versions.electron, chrome: process.versions.chrome, node: process.versions.node,
    os: `${os.type()} ${os.release()} (${process.arch})`, portals: repo.all().length, logins: vault.count(), lock: pin.isSet(),
    encryptionAvailable: cipher.available(), contentProtection: settings.get().blockScreenshots,
  }), { allowLocked: true });
}

// ---------- window ----------
function createWindow() {
  win = new BrowserWindow({
    width: 1100, height: 760, minWidth: 780, minHeight: 560, show: false, autoHideMenuBar: true,
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#0F1620' : '#F5F8FC', title: 'TravelHub',
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true, devTools: !app.isPackaged,
    },
  });
  Menu.setApplicationMenu(null);
  win.setMenuBarVisibility(false);
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.once('ready-to-show', () => { if (!SELFTEST) win.show(); });
  win.on('blur', armLockTimer);
  win.on('minimize', armLockTimer);
  win.on('focus', disarmLockTimer);
  win.on('restore', disarmLockTimer);
  win.on('closed', () => { win = null; });
  if (SELFTEST) {
    win.webContents.on('console-message', (e, level, message, line, src) => trace(`renderer console[${level}]: ${String(message).slice(0, 300)} (${path.basename(String(src))}:${line})`));
    win.webContents.on('preload-error', (e, file, err) => trace('FAIL  preload error: ' + String(err && err.stack || err).slice(0, 500)));
    win.webContents.on('render-process-gone', (e, d) => trace('FAIL  renderer gone: ' + JSON.stringify(d)));
    win.webContents.on('did-fail-load', (e, code, desc) => trace(`FAIL  renderer did-fail-load ${code} ${desc}`));
  }

  browser = new BrowserManager({
    win, getVault: () => vault, toast,
    notify: (st) => { if (st && st.open) st.hasLogin = vault.has(st.portalId); send('browser:state', st); },
  });
  applyWindowSettings();
  return win.loadFile(path.join(__dirname, '..', 'renderer', 'index.html'));
}

function initData() {
  const dir = app.getPath('userData');
  repo = new PortalRepository(path.join(dir, 'portals.json'));
  vault = new CredentialStore(path.join(dir, 'vault.json'), cipher);
  pin = new PinManager(new FileKv(path.join(dir, 'lock.json'), io));
  settings = new SettingsStore(path.join(dir, 'settings.json'));
  locked = pin.isSet();
}

// ---------- self test (run in CI on the Windows runner: `electron . --selftest`) ----------
async function selftest() {
  const results = [];
  const check = (name, cond) => { results.push([name, !!cond]); trace(`${cond ? 'PASS' : 'FAIL'}  ${name}`); };
  try {
    check('safeStorage encryption available', cipher.available());
    const id = 'selftest-portal';
    check('vault save', vault.save(id, { username: 'self-user', password: 'self-pass-SECRET' }));
    const got = vault.get(id);
    check('vault round trip', got && got.username === 'self-user' && got.password === 'self-pass-SECRET');
    check('vault file has no plaintext', !fs.readFileSync(path.join(app.getPath('userData'), 'vault.json'), 'utf8').includes('self-pass-SECRET'));
    vault.deleteAll();
    check('portal list starts empty', repo.all().length === 0);

    const js = (code, label) => Promise.race([
      win.webContents.executeJavaScript(code),
      new Promise((_, rej) => setTimeout(() => rej(new Error('timeout waiting for renderer: ' + label)), 8000)),
    ]);
    // createWindow() already awaited loadFile(); give the renderer a moment to run its first refresh.
    await new Promise((r) => setTimeout(r, 1000));
    trace('stage: probing preload bridge');
    const api = await js('typeof window.travelhub === "object" && typeof window.travelhub.getState === "function"', 'bridge');
    check('preload bridge exposed', api === true);
    trace('stage: calling getState over IPC');
    const st = await js('window.travelhub.getState()', 'getState');
    check('state: empty portals, unlocked', st && Array.isArray(st.portals) && st.portals.length === 0 && st.locked === false);
    await new Promise((r) => setTimeout(r, 500));
    const emptyText = await js('(document.getElementById("emptyState")||{}).textContent||""', 'emptyState');
    check('renderer shows empty state with add action', /Add your first portal/.test(emptyText));
    const noNode = await js('typeof require === "undefined" && typeof process === "undefined"', 'nonode');
    check('renderer has no Node access', noNode === true);

    const { WebContentsView } = require('electron');
    const v = new WebContentsView({ webPreferences: { partition: 'persist:selftest', sandbox: true, contextIsolation: true } });
    win.contentView.addChildView(v); v.setBounds({ x: 0, y: 0, width: 100, height: 100 }); win.contentView.removeChildView(v); v.webContents.close();
    check('WebContentsView create/destroy', true);
  } catch (e) {
    check('no exception: ' + String(e && e.message).slice(0, 120), false);
  }
  const failed = results.filter(([, ok]) => !ok).length;
  const lines = [...results.map(([n, ok]) => `${ok ? 'PASS' : 'FAIL'}  ${n}`), failed ? `SELFTEST FAILED (${failed})` : 'SELFTEST OK'];
  lines.forEach((l) => console.log(l));
  // GUI-subsystem apps on Windows have no console, so CI reads the result from a file.
  trace(lines[lines.length - 1]);
  app.exit(failed ? 1 : 0);
}

// ---------- lifecycle ----------
if (!SELFTEST && !app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
  app.whenReady().then(async () => {
    trace('stage: app ready');
    initData();
    trace('stage: data initialised');
    registerIpc();
    trace('stage: ipc registered');
    powerMonitor.on('lock-screen', lockNow);
    await createWindow();
    trace('stage: window created, renderer loaded');
    if (SELFTEST) await selftest();
  });
  app.on('window-all-closed', () => app.quit());
  // Never allow webviews or unexpected windows anywhere in the app.
  app.on('web-contents-created', (e, contents) => {
    contents.on('will-attach-webview', (ev) => ev.preventDefault());
  });
}
