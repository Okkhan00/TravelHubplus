'use strict';
(() => {
  const api = window.travelhub;
  const $ = (id) => document.getElementById(id);
  const PALETTE = ['#1565C0', '#00897B', '#6A1B9A', '#C62828', '#EF6C00', '#2E7D32', '#37474F', '#AD1457'];
  const LOCK_CHOICES = [['After 1 minute', 60], ['After 5 minutes', 300], ['After 15 minutes', 900], ['After 30 minutes', 1800], ['Never (manual lock only)', -1]];

  const S = { state: null, query: '', category: null, favoritesOnly: false, browser: null, screen: 'dashboard' };

  /** Tiny DOM builder. Always uses textContent, never innerHTML, so portal data can't inject markup. */
  function h(tag, attrs, ...kids) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'text') el.textContent = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else if (k === 'checked') el.checked = !!v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
    return el;
  }
  const show = (el, on) => el.classList.toggle('hidden', !on);
  const fmtTime = (ms) => new Date(ms).toLocaleString();

  function toast(msg) {
    const t = h('div', { class: 'toast', text: msg });
    $('toasts').append(t);
    setTimeout(() => t.remove(), 4500);
  }

  // ---------- dialogs ----------
  // The portal page is a native view that draws ABOVE web content, so hide it while any dialog is open.
  let dialogDepth = 0;
  async function openDialog(build) {
    const dlg = $('dlg');
    if (S.browser && S.browser.open && dialogDepth++ === 0) await api.browser('hide');
    dlg.replaceChildren();
    return new Promise((resolve) => {
      const close = (val) => { dlg.close(); resolve(val); };
      build(dlg, close);
      dlg.addEventListener('close', async () => {
        resolve(undefined);
        if (S.browser && S.browser.open && --dialogDepth === 0) await api.browser('show');
      }, { once: true });
      dlg.showModal();
    });
  }
  const confirmBox = ({ title, body, ok = 'OK', danger = false }) => openDialog((dlg, close) => {
    dlg.append(h('h3', { text: title }), h('p', { text: body }),
      h('div', { class: 'actions-row' },
        h('button', { class: 'btn', text: 'Cancel', onclick: () => close(false) }),
        h('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), text: ok, onclick: () => close(true) })));
  }).then((v) => v === true);
  const infoBox = (title, text) => openDialog((dlg, close) => {
    dlg.append(h('h3', { text: title }), h('pre', { class: 'info', text }),
      h('div', { class: 'actions-row' }, h('button', { class: 'btn primary', text: 'Close', onclick: () => close() })));
  });

  /** One masked PIN field per label; `submit(values)` returns an error string or null to close. */
  const pinDialog = ({ title, labels, ok, submit }) => openDialog((dlg, close) => {
    const inputs = labels.map((l) => h('input', { type: 'password', inputmode: 'numeric', maxlength: '12', autocomplete: 'off' }));
    const err = h('div', { class: 'err' });
    const go = async () => {
      err.textContent = '';
      const e = await submit(inputs.map((i) => i.value));
      if (e) { err.textContent = e; inputs.forEach((i) => { i.value = ''; }); inputs[0].focus(); } else close(true);
    };
    inputs.forEach((i) => i.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') go(); }));
    dlg.append(h('h3', { text: title }),
      ...labels.map((l, i) => h('div', { class: 'field' }, h('label', { text: l }), inputs[i])), err,
      h('div', { class: 'actions-row' },
        h('button', { class: 'btn', text: 'Cancel', onclick: () => close(false) }),
        h('button', { class: 'btn primary', text: ok, onclick: go })));
    setTimeout(() => inputs[0].focus(), 0);
  });

  const lockMessage = (r) => r.status === 'locked' ? `Too many attempts. Try again at ${fmtTime(r.until)}.` : r.status === 'wrong' ? `Wrong PIN. ${r.attemptsLeft} attempt(s) left before a temporary lockout.` : (r.error || null);

  /** Runs an IPC call; if main says re-authentication is needed, asks for the PIN and retries once. */
  async function withAuth(fn) {
    let r = await fn();
    if (r && r.error === 'auth') {
      const done = await pinDialog({
        title: 'Enter your PIN to continue', labels: ['PIN'], ok: 'Continue',
        submit: async ([pin]) => { const a = await api.reauth(pin); return a.status === 'success' ? null : lockMessage(a); },
      });
      if (!done) return { error: 'canceled' };
      r = await fn();
    }
    return r;
  }

  // ---------- state ----------
  async function refresh() { S.state = await api.getState(); render(); }

  function render() {
    const st = S.state;
    const locked = st.locked;
    const browsing = S.browser && S.browser.open;
    show($('lockScreen'), locked);
    show($('dashboard'), !locked && !browsing && S.screen === 'dashboard');
    show($('settings'), !locked && !browsing && S.screen === 'settings');
    show($('browserBar'), !locked && !!browsing);
    show($('browserError'), !locked && !!(browsing && S.browser.error));
    if (locked) return renderLock();
    if (browsing) return renderBrowser();
    if (S.screen === 'settings') return renderSettings();
    renderDashboard();
  }

  // ---------- lock screen ----------
  function renderLock() {
    const el = $('lockScreen');
    if (el.dataset.built) return;
    el.dataset.built = '1';
    const pin = h('input', { type: 'password', inputmode: 'numeric', maxlength: '12', placeholder: 'PIN', autocomplete: 'off' });
    const err = h('div', { class: 'err' });
    const go = async () => {
      if (!pin.value) return;
      const r = await api.unlock(pin.value);
      pin.value = '';
      if (r.status === 'success') { el.dataset.built = ''; el.replaceChildren(); await refresh(); } else err.textContent = lockMessage(r) || '';
    };
    pin.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
    el.replaceChildren(
      h('div', { class: 'big', text: '🔒' }), h('h2', { text: 'TravelHub is locked' }), pin, err,
      h('button', { class: 'btn primary', text: 'Unlock', onclick: go }),
      h('button', { class: 'btn ghost', text: 'Forgot PIN?', onclick: async () => {
        const ok = await confirmBox({
          title: 'Forgot your PIN?', ok: 'Erase logins & remove lock', danger: true,
          body: "A PIN can't be recovered or bypassed. To regain access, TravelHub can remove the lock — but this ERASES all saved logins and signs you out of every portal. Your portal list is kept.",
        });
        if (ok) { await api.forgotPin(); el.dataset.built = ''; el.replaceChildren(); toast('Lock removed. Saved logins and browser sessions were erased.'); await refresh(); }
      } }));
    setTimeout(() => pin.focus(), 0);
  }

  // ---------- dashboard ----------
  function filtered() {
    const st = S.state;
    const q = S.query.trim().toLowerCase();
    const list = st.portals.filter((p) =>
      (st.settings.showDisabled || p.enabled) && (!S.favoritesOnly || p.favorite) &&
      (S.category == null || p.category.toLowerCase() === S.category.toLowerCase()) &&
      (!q || p.name.toLowerCase().includes(q) || hostOf(p.url).includes(q) || p.category.toLowerCase().includes(q)));
    const byName = (a, b) => a.name.toLowerCase().localeCompare(b.name.toLowerCase());
    const sort = st.settings.sort;
    if (sort === 'ALPHABETICAL') list.sort(byName);
    else if (sort === 'RECENT') list.sort((a, b) => b.lastOpened - a.lastOpened || byName(a, b));
    else list.sort((a, b) => a.sortIndex - b.sortIndex);
    return [...list.filter((p) => p.pinned), ...list.filter((p) => !p.pinned)];
  }
  const hostOf = (u) => { try { return new URL(u).hostname.toLowerCase(); } catch (e) { return ''; } };
  const colorOf = (p) => p.colorHex || PALETTE[[...p.name].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7) % PALETTE.length];

  function renderDashboard() {
    const st = S.state;
    const s = st.settings;
    $('sort').value = s.sort;
    $('viewToggle').textContent = s.view === 'LIST' ? 'Grid view' : 'List view';
    $('showDisabled').checked = s.showDisabled;
    show($('lockBtn'), st.pinSet);
    const empty = st.portals.length === 0;
    show($('emptyState'), empty);
    show($('chips'), !empty);
    $('search').parentElement.classList.toggle('hidden', false);
    if (empty) {
      $('emptyState').replaceChildren(
        h('div', { class: 'big', text: '🧭' }), h('h2', { text: 'No portals yet' }),
        h('p', { class: 'muted', text: 'Add the travel websites you use. TravelHub keeps their logins in an encrypted vault and opens them in one click.' }),
        h('button', { class: 'btn primary', text: 'Add your first portal', onclick: () => portalForm(null) }));
      $('portalList').replaceChildren();
      return;
    }
    const chip = (label, on, fn) => h('button', { class: 'chip' + (on ? ' on' : ''), text: label, onclick: fn });
    $('chips').replaceChildren(
      chip('All', S.category == null && !S.favoritesOnly, () => { S.category = null; S.favoritesOnly = false; render(); }),
      chip('Favorites', S.favoritesOnly, () => { S.favoritesOnly = !S.favoritesOnly; render(); }),
      ...st.categories.map((c) => chip(c, (S.category || '').toLowerCase() === c.toLowerCase(), () => { S.category = (S.category || '').toLowerCase() === c.toLowerCase() ? null : c; render(); })));
    const list = filtered();
    const box = $('portalList');
    box.classList.toggle('grid', s.view === 'GRID');
    box.replaceChildren(...(list.length ? list.map(card) : [h('p', { class: 'muted', text: 'No portals match your filters' })]));
  }

  const act = async (fn) => { const r = await fn(); if (r && r.error && r.error !== 'canceled') toast(r.error); await refresh(); return r; };

  function card(p) {
    const manual = S.state.settings.sort === 'MANUAL';
    const closeMenu = (e) => { const d = e.target.closest('details'); if (d) d.open = false; };
    const item = (label, fn, cls) => h('button', { class: cls || '', text: label, onclick: (e) => { closeMenu(e); fn(); } });
    const tags = [p.category, p.favorite && '♥ Favorite', p.pinned && '★ Pinned', p.hasLogin && '🔒 Login saved', !p.enabled && 'Disabled'].filter(Boolean).join('  ·  ');
    const avatar = h('div', { class: 'avatar', text: p.iconText || [...p.name.trim()][0].toUpperCase() });
    avatar.style.setProperty('--c', colorOf(p));
    return h('div', { class: 'card' + (p.enabled ? '' : ' disabled') },
      h('div', { class: 'head' }, avatar,
        h('div', { class: 'info' }, h('div', { class: 'name', text: p.name }), h('div', { class: 'host', text: hostOf(p.url) })),
        h('details', { class: 'menu' }, h('summary', { text: '⋯', 'aria-label': 'More options for ' + p.name }),
          h('div', { class: 'menu-items' },
            item('Open', () => openPortal(p)),
            item('Open in external browser', async () => { const r = await api.openPortalExternal(p.id); if (r.error) toast(r.error); await refresh(); }),
            item('Edit / login settings', () => portalForm(p)),
            item(p.favorite ? 'Remove from favorites' : 'Add to favorites', () => act(() => api.modifyPortal(p.id, { favorite: !p.favorite }))),
            item(p.pinned ? 'Unpin' : 'Pin to top', () => act(() => api.modifyPortal(p.id, { pinned: !p.pinned }))),
            item(p.enabled ? 'Disable' : 'Enable', () => act(() => api.modifyPortal(p.id, { enabled: !p.enabled }))),
            manual && item('Move up', () => act(() => api.movePortal(p.id, -1))),
            manual && item('Move down', () => act(() => api.movePortal(p.id, 1))),
            item('Clear session data', () => clearSession(p)),
            item('Delete…', () => deletePortal(p), 'danger')))),
      h('div', { class: 'tags', text: tags }),
      h('div', { class: 'row' },
        h('button', { class: 'btn primary', text: 'Open', disabled: !p.enabled, onclick: () => openPortal(p) }),
        h('button', { class: 'btn', text: 'Edit', onclick: () => portalForm(p) })));
  }

  async function openPortal(p) {
    const r = await api.openPortal(p.id);
    if (r.error) toast(r.error);
    await refresh();
  }
  async function deletePortal(p) {
    if (await confirmBox({ title: `Delete “${p.name}”?`, body: 'This permanently removes the portal, its saved login and its session data from this PC. This can\'t be undone.', ok: 'Delete', danger: true })) {
      await act(() => api.deletePortal(p.id));
    }
  }
  async function clearSession(p) {
    if (await confirmBox({ title: `Clear session for “${p.name}”?`, body: "Signs you out and removes this portal's cookies and site data. Each portal has its own isolated storage on Windows, so other portals are not affected.", ok: 'Clear' })) {
      const r = await api.clearPortalSession(p.id);
      toast(r.error || 'Session data cleared');
    }
  }

  // ---------- portal form ----------
  function portalForm(p) {
    const st = S.state;
    const edit = !!p;
    const v = p || { name: '', url: '', category: '', notes: '', iconText: '', colorHex: '', openMode: st.settings.defaultOpenMode, enabled: true,
      autoFill: false, autoSubmit: false, rememberSession: true, extraTrustedHosts: [], logoutUrl: '', allowHttp: false, hasLogin: false };
    return openDialog((dlg, close) => {
      let color = v.colorHex || '';
      let openMode = v.openMode;
      const f = {
        name: h('input', { type: 'text', value: v.name, maxlength: '80' }),
        url: h('input', { type: 'text', value: v.url, placeholder: 'https://example.com/login' }),
        category: h('input', { type: 'text', value: v.category, maxlength: '40', list: 'catlist' }),
        notes: h('textarea', { value: v.notes }),
        icon: h('input', { type: 'text', value: v.iconText, maxlength: '2' }),
        user: h('input', { type: 'text', autocomplete: 'off' }),
        pass: h('input', { type: 'password', autocomplete: 'new-password' }),
        hosts: h('input', { type: 'text', value: (v.extraTrustedHosts || []).join(', '), placeholder: 'sso.example.com, login.example.com' }),
        logout: h('input', { type: 'text', value: v.logoutUrl }),
        enabled: h('input', { type: 'checkbox', checked: v.enabled }),
        autoFill: h('input', { type: 'checkbox', checked: v.autoFill }),
        autoSubmit: h('input', { type: 'checkbox', checked: v.autoSubmit }),
        remember: h('input', { type: 'checkbox', checked: v.rememberSession }),
      };
      const errs = { name: h('div', { class: 'err' }), url: h('div', { class: 'err' }), hosts: h('div', { class: 'err' }), logout: h('div', { class: 'err' }), form: h('div', { class: 'err' }) };
      const field = (label, input, err, hint) => h('div', { class: 'field' }, h('label', { text: label }), input, hint && h('div', { class: 'muted', text: hint }), err);
      const sw = (input, title, sub) => h('label', { class: 'switch' }, input, h('div', {}, h('div', { text: title }), sub && h('div', { class: 's', text: sub })));
      const dots = h('div', { class: 'sw' });
      const drawDots = () => dots.replaceChildren(...['', ...PALETTE].map((hex) => {
        const d = h('button', { type: 'button', class: 'dot' + ((hex || '') === color ? ' on' : ''), title: hex || 'Automatic colour', onclick: () => { color = hex; drawDots(); } });
        if (hex) d.style.setProperty('--c', hex); else d.textContent = 'A';
        return d;
      }));
      drawDots();
      const modes = h('div', { class: 'sw' });
      const drawModes = () => modes.replaceChildren(
        h('button', { type: 'button', class: 'chip' + (openMode === 'IN_APP' ? ' on' : ''), text: 'In TravelHub', onclick: () => { openMode = 'IN_APP'; drawModes(); } }),
        h('button', { type: 'button', class: 'chip' + (openMode === 'EXTERNAL' ? ' on' : ''), text: 'External browser', onclick: () => { openMode = 'EXTERNAL'; drawModes(); } }));
      drawModes();
      const sugg = h('div', { class: 'sw' }, ...[...new Set([...st.suggested, ...st.categories])].map((c) => h('button', { type: 'button', class: 'chip', text: c, onclick: () => { f.category.value = c; } })));
      const showBtn = h('button', { type: 'button', class: 'btn ghost', text: 'Show', onclick: () => { f.pass.type = f.pass.type === 'password' ? 'text' : 'password'; showBtn.textContent = f.pass.type === 'password' ? 'Show' : 'Hide'; } });

      const save = async (httpOk) => {
        Object.values(errs).forEach((e) => { e.textContent = ''; });
        if (!f.name.value.trim()) { errs.name.textContent = 'Enter a portal name'; return; }
        const raw = f.url.value.trim();
        if (/^http:\/\//i.test(raw) && !httpOk && !(v.allowHttp && raw === v.url)) {
          const ok = await confirmBox({ title: "This address isn't secure", ok: 'Allow http for this portal', danger: true,
            body: 'http:// sites send everything — including your password — without encryption, so anyone on the same network may be able to read it. Use the https:// address if the site supports it. Only continue if you understand the risk.' });
          if (!ok) return;
          httpOk = true;
        }
        const hosts = f.hosts.value.split(/[,\s;]+/).map((s) => s.trim()).filter(Boolean);
        const draft = {
          id: edit ? v.id : '', name: f.name.value, url: raw, category: f.category.value, notes: f.notes.value, iconText: f.icon.value, colorHex: color,
          openMode, enabled: f.enabled.checked, allowHttp: /^http:\/\//i.test(raw) && (httpOk || v.allowHttp), autoFill: f.autoFill.checked,
          autoSubmit: f.autoFill.checked && f.autoSubmit.checked, rememberSession: f.remember.checked, extraTrustedHosts: hosts, logoutUrl: f.logout.value,
          favorite: !!v.favorite, pinned: !!v.pinned,
        };
        const r = await api.savePortal({ draft, username: f.user.value, password: f.pass.value });
        f.pass.value = '';
        if (r.error) { errs.form.textContent = r.error; return; }
        if (r.note) toast(r.note);
        close(true);
        await refresh();
      };

      dlg.append(h('h3', { text: edit ? 'Edit portal' : 'Add portal' }),
        field('Portal name *', f.name, errs.name),
        field('Website address *', f.url, errs.url, 'https:// is added automatically if you leave it out'),
        field('Category (optional)', f.category, null), sugg, h('datalist', { id: 'catlist' }, ...st.categories.map((c) => h('option', { value: c }))),
        field('Notes (optional)', f.notes, null),
        h('div', { class: 'field' }, h('label', { text: 'Icon text & colour' }), h('div', { class: 'sw' }, f.icon, dots)),
        h('div', { class: 'section', text: 'Login (stored encrypted on this PC)' }),
        v.hasLogin && h('p', { class: 'muted', text: '🔒 A login is saved. Saved details are never shown. Leave these blank to keep them, or type new values to replace them.' }),
        field('Username (optional)', f.user, null),
        h('div', { class: 'field' }, h('label', { text: 'Password (optional)' }), h('div', { class: 'sw' }, f.pass, showBtn)),
        v.hasLogin && h('div', {}, h('button', { type: 'button', class: 'btn', text: 'Forget saved login', onclick: async () => {
          if (await confirmBox({ title: 'Forget saved login?', body: 'The username and password for this portal are deleted from this PC. The portal stays.', ok: 'Forget' })) {
            await api.forgetLogin(v.id); toast('Saved login removed'); close(true); await refresh();
          }
        } })),
        h('div', { class: 'section', text: 'Opening & automatic login' }),
        h('div', { class: 'field' }, h('label', { text: 'Open mode' }), modes),
        sw(f.enabled, 'Enabled', 'Disabled portals are hidden from the dashboard'),
        sw(f.autoFill, 'Fill saved login automatically', "Fills username/password on the trusted site's login page. Never works through CAPTCHA/OTP; you complete those."),
        sw(f.autoSubmit, 'Also press the sign-in button', 'Submits once per visit. Off = you click the site\'s own button.'),
        sw(f.remember, 'Keep me signed in (remember session)', "Off = this portal's cookies are cleared when you leave it"),
        field('Extra trusted domains (optional)', f.hosts, errs.hosts, "Logins are only ever filled on this portal's own domain (and its subdomains) plus the domains listed here."),
        field('Sign-out address (optional)', f.logout, errs.logout, 'Used by the "Sign out" button in the browser bar'),
        edit && h('button', { type: 'button', class: 'btn ghost', text: 'Reset login settings', onclick: () => {
          f.autoFill.checked = false; f.autoSubmit.checked = false; f.hosts.value = ''; f.logout.value = ''; toast('Login settings reset. Click Save to apply.');
        } }),
        errs.form,
        h('div', { class: 'actions-row' },
          h('button', { class: 'btn', text: 'Cancel', onclick: () => close(false) }),
          h('button', { class: 'btn primary', text: edit ? 'Save changes' : 'Add portal', onclick: () => save(false) })));
      setTimeout(() => f.name.focus(), 0);
    });
  }

  // ---------- browser bar ----------
  function renderBrowser() {
    const b = S.browser;
    $('bbTitle').textContent = b.title || b.name;
    const secure = /^https:\/\//i.test(b.url || '');
    const d = $('bbDomain');
    d.textContent = (secure ? '🔒 ' : '⚠ not secure · ') + hostOf(b.url || '');
    d.classList.toggle('bad', !secure);
    $('bbBack').disabled = !b.canBack;
    $('bbFwd').disabled = !b.canForward;
    $('bbReload').textContent = b.loading ? '✕' : '⟳';
    $('bbReload').title = b.loading ? 'Stop loading' : 'Refresh';
    $('bbFill').disabled = !b.hasLogin;
    $('bbFillGo').disabled = !b.hasLogin;
    show($('bbProgress'), !!b.loading);
    const be = $('browserError');
    if (b.error) {
      be.replaceChildren(h('h2', { text: "Can't open this page" }), h('p', { class: 'muted', text: b.error }),
        !b.crashed && h('button', { class: 'btn primary', text: 'Retry', onclick: () => api.browser('reload') }),
        h('button', { class: 'btn', text: 'Open in external browser', onclick: () => api.browser('external') }),
        h('button', { class: 'btn ghost', text: 'Close', onclick: () => api.browser('close') }));
    }
  }

  // ---------- settings ----------
  const cap = (s) => s.charAt(0) + s.slice(1).toLowerCase();
  function choiceRow(title, current, options, onPick) {
    const sel = h('select', {}, ...options.map(([label, val]) => h('option', { value: String(val), text: label, selected: String(val) === String(current) })));
    sel.addEventListener('change', () => onPick(sel.value));
    return h('div', { class: 'srow static' }, h('div', { class: 't', text: title }), sel);
  }
  const row = (title, sub, fn) => h('div', { class: 'srow', onclick: fn }, h('div', { class: 't' }, title, sub && h('small', { text: sub })));

  function renderSettings() {
    const st = S.state;
    const s = st.settings;
    const upd = async (patch) => { await api.updateSettings(patch); await refresh(); };
    const root = $('settings');
    root.replaceChildren(
      h('div', { class: 'bar' }, h('button', { class: 'btn', text: '← Back', onclick: () => { S.screen = 'dashboard'; render(); } }), h('h2', { text: 'Settings' })),
      h('div', { class: 'section', text: '  Security' }),
      !st.pinSet
        ? row('Set up app lock', 'Protect TravelHub with a PIN. It locks automatically after the idle time you choose.', async () => {
          await pinDialog({ title: 'Set a PIN', labels: ['New PIN (4–12 digits)', 'Confirm PIN'], ok: 'Save', submit: async ([a, b]) => {
            if (!/^\d{4,12}$/.test(a)) return 'Use 4 to 12 digits';
            if (a !== b) return "PINs don't match";
            const r = await api.setPin(a); return r.error || null;
          } });
          await refresh();
        })
        : [
          row('Change PIN', null, async () => {
            await pinDialog({ title: 'Change PIN', labels: ['Current PIN', 'New PIN (4–12 digits)', 'Confirm new PIN'], ok: 'Change', submit: async ([c, a, b]) => {
              if (!/^\d{4,12}$/.test(a)) return 'Use 4 to 12 digits';
              if (a !== b) return "New PINs don't match";
              const r = await api.changePin(c, a); return r.status === 'success' ? null : lockMessage(r);
            } });
          }),
          row('Remove PIN', null, async () => {
            await pinDialog({ title: 'Remove PIN', labels: ['Current PIN'], ok: 'Remove', submit: async ([c]) => { const r = await api.removePin(c); return r.status === 'success' ? null : lockMessage(r); } });
            await refresh();
          }),
          choiceRow('Auto-lock when idle or minimised', s.autoLockSeconds, LOCK_CHOICES.map(([l, v]) => [l, v]), (v) => upd({ autoLockSeconds: Number(v) })),
        ],
      h('label', { class: 'srow' }, h('div', { class: 't' }, 'Hide app content from screenshots & screen sharing', h('small', { text: 'Uses Windows content protection; captures show a black window' })),
        h('input', { type: 'checkbox', checked: s.blockScreenshots, onchange: (e) => upd({ blockScreenshots: e.target.checked }) })),
      h('div', { class: 'srow static' }, h('div', { class: 't' }, 'Fingerprint / face unlock', h('small', { text: 'Not available in the Windows version (no Windows Hello integration). Your PIN is the lock.' }))),
      h('div', { class: 'section', text: '  Appearance & browsing' }),
      choiceRow('Theme', s.theme, ['SYSTEM', 'LIGHT', 'DARK'].map((x) => [cap(x), x]), (v) => upd({ theme: v })),
      choiceRow('Default portal view', s.view, ['LIST', 'GRID'].map((x) => [cap(x), x]), (v) => upd({ view: v })),
      choiceRow('Open new portals in', s.defaultOpenMode, [['TravelHub browser', 'IN_APP'], ['External browser', 'EXTERNAL']], (v) => upd({ defaultOpenMode: v })),
      row('Clear all browsing data', 'Signs you out of every portal and clears cookies, cache and site data', async () => {
        if (await confirmBox({ title: 'Clear all browsing data?', body: "You'll be signed out of every portal. Portals and saved logins are kept.", ok: 'Clear' })) { await api.clearBrowsingData(); toast('Browsing data cleared'); }
      }),
      h('div', { class: 'section', text: '  Backup & restore' }),
      row('Export portal list', 'Saves names, addresses and categories. NO usernames or passwords are included, so restoring will not restore logins. The file can also be imported on the Android app.', async () => {
        const r = await withAuth(() => api.exportPortals());
        if (r.ok) toast('Portal list exported (no passwords included)'); else if (r.error && r.error !== 'canceled') toast(r.error);
      }),
      row('Import portal list', 'Adds portals from a TravelHub export (Windows or Android). Duplicates and insecure entries are skipped; login automation is not restored.', async () => {
        const r = await api.importPortals();
        if (r.error) toast(r.error); else if (r.message) toast(r.message);
        await refresh();
      }),
      h('div', { class: 'section', text: '  Privacy & about' }),
      row('Privacy information', null, () => infoBox('Privacy', PRIVACY)),
      row('Diagnostics (no secrets)', null, async () => {
        const a = await api.about();
        infoBox('Diagnostics', [`App: ${a.version}`, `OS: ${a.os}`, `Electron ${a.electron} · Chromium ${a.chrome} · Node ${a.node}`, `Portals: ${a.portals}`, `Saved logins: ${a.logins}`,
          `App lock: ${a.lock ? 'on' : 'off'}`, `Secure storage (DPAPI) available: ${a.encryptionAvailable ? 'yes' : 'NO'}`, `Screenshot blocking: ${a.contentProtection ? 'on' : 'off'}`,
          'No usernames, passwords, URLs or cookies are included here.'].join('\n'));
      }),
      row('Open-source licenses', null, () => infoBox('Open-source licenses', LICENSES)),
      row('About TravelHub', null, async () => { const a = await api.about(); infoBox('About TravelHub', `TravelHub for Windows ${a.version}\n\nA personal hub for your own travel-agent portals. No account, no server, no ads, no analytics. Not synchronised with the Android app; you can copy the portal list between them with Export/Import.`); }),
      h('div', { class: 'section', text: '  Reset' }),
      row('Delete all saved logins', 'Keeps your portals, removes every stored username and password', async () => {
        if (await confirmBox({ title: 'Delete all saved logins?', body: 'Every stored username and password is permanently deleted. Your portal list is kept.', ok: 'Delete', danger: true })) {
          const r = await withAuth(() => api.deleteAllLogins()); if (r.ok) toast('All saved logins were deleted'); await refresh();
        }
      }),
      row('Delete everything', 'Removes all portals, saved logins and browsing data from this PC', async () => {
        if (await confirmBox({ title: 'Delete everything?', body: "All portals, saved logins and browsing data are permanently removed from this PC. This can't be undone.", ok: 'Delete everything', danger: true })) {
          const r = await withAuth(() => api.deleteEverything()); if (r.ok) toast('Everything was deleted'); await refresh();
        }
      }));
  }

  const PRIVACY = `What TravelHub stores (only on this PC):
• Your portal list (names, addresses, categories, notes) in a plain app-data file.
• Usernames and passwords, encrypted with Windows DPAPI (via Electron safeStorage) in a separate file. They are tied to your Windows user account.
• Your PIN as a salted scrypt hash (never the PIN itself).
• Website cookies/sessions, kept per portal in isolated storage, like a browser would.

What it never does:
• No account, server, analytics, ads or tracking. Nothing is uploaded.
• Passwords are never exported, logged or shown.
• Saved logins are only filled on the portal's own trusted domain.

Limits you should know:
• DPAPI protects against other Windows users and other PCs, but NOT against other programs running as you, or malware with your privileges.
• The app lock protects the app's screens; it is not full-disk encryption. Anyone with an unlocked PC can use sessions you left signed in.
• Windows and Android versions are independent. Nothing syncs automatically.`;
  const LICENSES = `TravelHub for Windows is built with:

• Electron (MIT License) — includes Chromium (BSD-style) and Node.js (MIT)
• electron-builder (MIT License) — used at build time to create the installer

Licenses: https://github.com/electron/electron/blob/main/LICENSE`;

  // ---------- wiring ----------
  $('search').addEventListener('input', (e) => { S.query = e.target.value; renderDashboard(); });
  $('sort').addEventListener('change', async (e) => { await api.updateSettings({ sort: e.target.value }); await refresh(); });
  $('viewToggle').addEventListener('click', async () => { await api.updateSettings({ view: S.state.settings.view === 'LIST' ? 'GRID' : 'LIST' }); await refresh(); });
  $('showDisabled').addEventListener('change', async (e) => { await api.updateSettings({ showDisabled: e.target.checked }); await refresh(); });
  $('addBtn').addEventListener('click', () => portalForm(null));
  $('settingsBtn').addEventListener('click', () => { S.screen = 'settings'; render(); });
  $('lockBtn').addEventListener('click', () => api.lockNow());

  $('bbClose').addEventListener('click', () => api.browser('close'));
  $('bbBack').addEventListener('click', () => api.browser('back'));
  $('bbFwd').addEventListener('click', () => api.browser('forward'));
  $('bbReload').addEventListener('click', () => api.browser(S.browser && S.browser.loading ? 'stop' : 'reload'));
  $('bbFill').addEventListener('click', () => api.browser('fill', false));
  $('bbFillGo').addEventListener('click', () => api.browser('fill', true));
  $('bbExternal').addEventListener('click', () => api.browser('external'));
  $('bbSignOut').addEventListener('click', () => api.browser('signout'));
  $('bbClear').addEventListener('click', async () => {
    const b = S.browser;
    if (b && await confirmBox({ title: "Clear this portal's session?", body: "You'll be signed out. Only this portal's cookies and site data are removed.", ok: 'Clear' })) await api.clearPortalSession(b.portalId);
  });
  $('bbEdit').addEventListener('click', async () => {
    const id = S.browser && S.browser.portalId;
    await api.browser('close');
    await refresh();
    const p = S.state.portals.find((x) => x.id === id);
    if (p) portalForm(p);
  });

  api.on('toast', toast);
  api.on('browser:state', async (b) => {
    const wasOpen = !!(S.browser && S.browser.open);
    S.browser = b;
    if (b.open !== wasOpen) await refresh(); else render();
  });
  api.on('lock:changed', async () => { S.browser = null; S.screen = 'dashboard'; $('lockScreen').dataset.built = ''; $('lockScreen').replaceChildren(); await refresh(); });

  refresh();
})();
