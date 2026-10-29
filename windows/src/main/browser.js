'use strict';
const { WebContentsView, session, shell, Menu } = require('electron');
const { decide } = require('../core/navigation');
const { isTrusted, hostOf } = require('../core/url');
const loginScript = require('../core/loginScript');

const TOOLBAR_H = 104; // height of the toolbar rendered by the UI above the portal view

const configured = new WeakSet();

/** Per-portal session hardening: deny all permission prompts (camera, mic, location, notifications…). */
function hardenSession(ses, portalOf) {
  if (configured.has(ses)) return;
  configured.add(ses);
  ses.setPermissionRequestHandler((wc, permission, cb) => cb(false));
  ses.setPermissionCheckHandler(() => false);
  ses.on('will-download', (event, item) => {
    const u = item.getURL();
    const p = portalOf();
    const ok = /^https:/i.test(u) || (p && p.allowHttp && /^http:/i.test(u) && isTrusted(u, [p.host, ...p.extraTrustedHosts], true));
    if (!ok) event.preventDefault(); // blob:/data:/file: and unexpected http downloads are refused
  });
}

/**
 * Hosts ONE portal in a WebContentsView with its own session partition, so — unlike Android WebView —
 * cookies and storage are isolated per portal. No preload and no IPC bridge are given to portal pages.
 */
class BrowserManager {
  constructor({ win, getVault, notify, toast }) {
    this.win = win;
    this.getVault = getVault;
    this.notify = notify;
    this.toast = toast;
    this.view = null;
    this.portal = null;
    this.st = null;
    this.autoAttempted = false;
    this.destroyed = true;
    win.on('resize', () => this.layout());
  }

  static partitionFor(portal) { return 'persist:portal-' + portal.id; }

  isOpen() { return !!this.view; }
  trusted(url) { return !!this.portal && isTrusted(url, this.portal.trustedHosts, this.portal.allowHttp); }

  layout() {
    if (!this.view) return;
    const b = this.win.getContentBounds();
    this.view.setBounds({ x: 0, y: TOOLBAR_H, width: b.width, height: Math.max(0, b.height - TOOLBAR_H) });
  }

  open(portal) {
    this.close();
    this.portal = portal;
    this.destroyed = false;
    this.autoAttempted = false;
    this.st = { open: true, portalId: portal.id, name: portal.name, title: '', url: portal.url, loading: true, canBack: false, canForward: false, error: null, crashed: false, hasLogin: false };
    const partition = portal.rememberSession ? BrowserManager.partitionFor(portal) : 'portal-tmp-' + portal.id + '-' + Date.now();
    this.partition = partition;
    const ses = session.fromPartition(partition);
    hardenSession(ses, () => this.portal);
    this.view = new WebContentsView({
      webPreferences: {
        partition, contextIsolation: true, nodeIntegration: false, sandbox: true, webSecurity: true,
        allowRunningInsecureContent: false, devTools: false, spellcheck: true,
      },
    });
    this.win.contentView.addChildView(this.view);
    this.layout();
    const wc = this.view.webContents;
    this._wire(wc);
    const first = decide(portal.url, portal.allowHttp, portal.trustedHosts);
    if (first.action !== 'allow') { this._fail('This portal address is not allowed to load.'); return this.st; }
    wc.loadURL(portal.url).catch(() => { /* surfaced by did-fail-load */ });
    this._push();
    return this.st;
  }

  _wire(wc) {
    const guard = (event, url) => {
      const d = decide(url, this.portal.allowHttp, this.portal.trustedHosts);
      if (d.action === 'allow') return;
      event.preventDefault();
      if (d.action === 'redirect') wc.loadURL(d.url).catch(() => {});
      else if (d.action === 'external') shell.openExternal(d.url).catch(() => {});
      else this.toast(d.reason);
    };
    wc.on('will-navigate', (event, url) => guard(event, event.url || url));
    wc.on('will-redirect', (event, url) => guard(event, event.url || url));
    wc.setWindowOpenHandler(({ url }) => {
      const d = decide(url, this.portal.allowHttp, this.portal.trustedHosts);
      if (d.action === 'allow') wc.loadURL(url).catch(() => {}); // open "new tab" links in the same view
      else if (d.action === 'redirect') wc.loadURL(d.url).catch(() => {});
      else if (d.action === 'external') shell.openExternal(d.url).catch(() => {});
      else this.toast(d.reason);
      return { action: 'deny' };
    });
    // Never proceed through certificate errors.
    wc.on('certificate-error', (event, url, error, cert, callback) => { event.preventDefault(); callback(false); });
    wc.on('did-start-loading', () => this._set({ loading: true, error: null }));
    wc.on('did-stop-loading', () => this._set({ loading: false }));
    wc.on('page-title-updated', (e, title) => this._set({ title }));
    const nav = () => this._set({ url: wc.getURL(), canBack: wc.navigationHistory.canGoBack(), canForward: wc.navigationHistory.canGoForward() });
    wc.on('did-navigate', nav);
    wc.on('did-navigate-in-page', nav);
    wc.on('did-fail-load', (e, code, desc, url, isMainFrame) => {
      if (!isMainFrame || code === -3) return; // -3 = aborted (user navigated away)
      this._fail(String(desc).startsWith('ERR_CERT')
        ? "The site's security certificate is not valid, so the page was not loaded."
        : `Couldn't load this page (${desc}).`);
    });
    wc.on('render-process-gone', () => this._fail('The page stopped responding. Close it and open the portal again.', true));
    wc.on('did-finish-load', () => { nav(); this._maybeAutoFill(wc); });
    wc.on('context-menu', (e, params) => {
      const t = [];
      if (params.isEditable) t.push({ role: 'cut' }, { role: 'copy' }, { role: 'paste' }, { role: 'selectAll' });
      else if (params.selectionText) t.push({ role: 'copy' });
      if (t.length) Menu.buildFromTemplate(t).popup({ window: this.win });
    });
  }

  _fail(message, crashed = false) {
    this._set({ error: message, crashed, loading: false });
    if (this.view) this.view.setVisible(false);
  }

  _set(patch) {
    if (!this.st || this.destroyed) return;
    this.st = { ...this.st, ...patch };
    this._push();
  }

  _push() { if (this.st) this.notify(this.st); }

  setVisible(v) { if (this.view && !(this.st && this.st.error)) this.view.setVisible(!!v); }

  back() { const n = this.view && this.view.webContents.navigationHistory; if (n && n.canGoBack()) n.goBack(); }
  forward() { const n = this.view && this.view.webContents.navigationHistory; if (n && n.canGoForward()) n.goForward(); }
  reload() {
    if (!this.view) return;
    this._set({ error: null, crashed: false });
    this.view.setVisible(true);
    this.view.webContents.reload();
  }
  stop() { if (this.view) this.view.webContents.stop(); }

  signOut() {
    if (!this.view) return;
    if (!this.portal.logoutUrl) { this.toast('No sign-out address set. Add one in the portal settings.'); return; }
    this.view.webContents.loadURL(this.portal.logoutUrl).catch(() => {});
  }

  openExternally() {
    if (!this.view) return;
    const u = this.view.webContents.getURL() || this.portal.url;
    if (/^https?:\/\//i.test(u)) shell.openExternal(u).catch(() => {});
  }

  /** Fills the login form only if the CURRENT page is on a trusted host (re-checked right before injection). */
  async fill(submit, manual) {
    if (!this.view) return;
    const wc = this.view.webContents;
    if (!this.trusted(wc.getURL())) {
      if (manual) this.toast("This page isn't on the portal's trusted domain, so your login was not filled.");
      return;
    }
    const creds = this.getVault().get(this.portal.id);
    if (!creds) { if (manual) this.toast('No saved login for this portal (or it cannot be decrypted).'); return; }
    if (this.destroyed || !this.view || !this.trusted(this.view.webContents.getURL())) return;
    let r;
    try { r = await wc.executeJavaScript(loginScript.fill(creds.username, creds.password, submit)); } catch (e) { r = 'error'; }
    if (manual) {
      this.toast({ filled: 'Login filled. Complete any CAPTCHA/OTP, then sign in.', submitted: 'Login submitted', nofield: 'No login form found on this page' }[r] || "Couldn't fill this page");
    }
  }

  _maybeAutoFill(wc) {
    if (!this.portal || !this.portal.autoFill || this.autoAttempted || this.destroyed || !this.trusted(wc.getURL())) return;
    setTimeout(async () => {
      if (this.destroyed || this.autoAttempted || !this.view || !this.trusted(wc.getURL())) return;
      let found = false;
      try { found = await wc.executeJavaScript(loginScript.DETECT); } catch (e) { found = false; }
      // One automatic attempt per visit: a failed login can never become a retry loop.
      if (found === true && !this.autoAttempted && !this.destroyed && this.trusted(wc.getURL())) {
        this.autoAttempted = true;
        this.fill(!!this.portal.autoSubmit, false);
      }
    }, 800);
  }

  async clearSession(portal) {
    const ses = session.fromPartition(portal.rememberSession ? BrowserManager.partitionFor(portal) : this.partition || 'x');
    await ses.clearStorageData();
    await ses.clearCache();
    if (this.view && this.portal && this.portal.id === portal.id) this.reload();
  }

  close() {
    if (!this.view) return;
    this.destroyed = true;
    const v = this.view;
    const keep = this.portal && this.portal.rememberSession;
    const ses = v.webContents.session;
    this.view = null;
    try { this.win.contentView.removeChildView(v); } catch (e) { /* already removed */ }
    try { v.webContents.close(); } catch (e) { /* already closed */ }
    if (!keep) ses.clearStorageData().catch(() => {});
    this.portal = null;
    this.st = null;
    this.notify({ open: false });
  }

  static hostLabel(url) { return hostOf(url) || ''; }
}

module.exports = { BrowserManager, TOOLBAR_H };
