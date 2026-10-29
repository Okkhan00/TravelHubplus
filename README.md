# TravelHub

A personal hub for **your own** travel-agent websites, for **Android** and **Windows**. You add each portal yourself
(name + address, optional login); both apps start with an **empty** list and contain no built-in portals.
No account, no backend, no ads, no analytics. Nothing leaves your device.

| Platform | Folder | Stack | Workflow | Download artifact |
|---|---|---|---|---|
| Android | repo root (`app/`) | Kotlin, Jetpack Compose | `android-build.yml` | `travelhub-debug-apk` |
| Windows | `windows/` | Electron, plain JS | `windows-build.yml` | `travelhub-windows-installer` |

The two builds are independent: each has its own workflow, and a change under `windows/` does not trigger the Android build.
Test/build reports: `travelhub-build-and-test-report` (Android) and `travelhub-windows-build-and-test-report` (Windows).

> **The two apps do NOT synchronize.** Portals, settings and logins are stored separately on each device. There is no cloud sync.
> You can copy the *portal list* (never passwords) between devices with **Settings → Export / Import portal list**; both apps use the same file format.
> Logins must be re-entered on each device.

## Android — get the APK (no Android Studio needed)

1. Open the repo's **Actions** tab → latest green **Android Build** run (or click **Run workflow**).
2. Under **Artifacts**, download **`travelhub-debug-apk`** (a zip containing `app-debug.apk`).
   Test/lint reports are in **`travelhub-build-and-test-report`**.
3. Unzip, copy `app-debug.apk` to your phone, open it, and allow "Install unknown apps" for your file manager/browser when asked.

This is a **debug-signed** build for personal use. A release/Play build would need your own signing key,
supplied via repository secrets — never commit a keystore.

## Android — build locally

```
./gradlew testDebugUnitTest lintDebug assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 and the Android SDK (platform 35).

## Android — features & status

| Area | Status |
|---|---|
| Add / edit / delete portals (name, URL, category, notes, icon text, colour) | ✅ |
| URL validation (https default, explicit http warning + per-portal opt-in, duplicate detection) | ✅ |
| Dashboard: cards, search, category chips, favourites, pin, sort (manual / A–Z / recent), list/grid, empty state, delete confirmation | ✅ |
| Reorder | ⚠️ Move up/down in menu (no drag-and-drop) |
| Encrypted credential vault (AES-256-GCM, Android Keystore key, separate file) | ✅ |
| App lock: PBKDF2 PIN, lockout throttling, biometric (strong) unlock, auto-lock, forgot-PIN erase flow, screenshot/recents blocking | ✅ |
| In-app browser: back/forward/refresh/stop, progress, title+domain, retry page, uploads, downloads, SSL errors blocked, external-scheme handling, permission denial | ✅ |
| Login filling: origin-checked, manual fill button, optional auto-fill, optional single auto-submit | ✅ (works only on standard HTML login forms) |
| Per-portal session clearing / sign-out URL / forget login / reset login settings | ✅ best-effort (see limits) |
| Export / import portal list (no secrets) | ✅ |
| Encrypted backup that includes credentials | ❌ not implemented |
| Website favicons / custom image logos | ❌ not implemented (avoids extra network requests) |
| Instrumented (on-device) UI tests | ❌ not implemented; only JVM unit tests run in CI |

## Android — security design

* **Passwords** are encrypted with AES-256-GCM (random 12-byte IV per encryption, record id bound as AAD) using a non-exportable key
  generated in the **Android Keystore**; ciphertext lives in `vault.json`, separate from `portals.json`. Nothing sensitive is logged, exported or committed.
* **The vault key is not tied to your PIN or biometrics**, so the app can fill logins without prompting every time. The PIN/biometric gates the *app UI*
  (and re-prompts before export/reset). Malware running as this app, or a rooted device, could use the key.
* **PIN**: 4–12 digits, PBKDF2-HMAC-SHA256 (150k iterations, unique salt), constant-time compare, escalating lockouts. A short PIN can still be brute-forced offline by someone with full file access.
* **Forgot PIN** can't reveal or bypass it; it erases saved logins and browser sessions, then removes the lock.
* **WebView**: no JavaScript bridges; SSL errors always cancel; mixed content blocked; file/content access off; camera/mic/location denied;
  `intent:`, `file:`, `javascript:`, `data:` and unknown schemes blocked; `tel:`/`mailto:` only on a user tap; http only for explicitly opted-in portals.
* **Credential filling** happens only if the *current* page is https on the portal's host (or a subdomain, or a domain you listed), re-checked right before injection;
  the script receives JSON-quoted values; one automatic attempt per visit (no retry loops). It never touches CAPTCHA/OTP — you complete those. A changed portal website removes its saved login.
* **Cookies**: Android WebView has a single app-wide cookie store, so portals are **not isolated** from each other. Per-portal clearing expires cookies for that portal's hosts (best effort).
* Backups/exports contain **no** usernames or passwords, and login automation settings (auto-fill, trusted domains, http opt-in) are intentionally not restored.

## Android — layout

```
app/src/main/java/com/tabeer/travelhub/
  data/      Portal model, JSON repository, URL validation, filters, backup codec, settings
  security/  AES-GCM cipher, Keystore key provider, credential store, PIN manager, biometrics
  browser/   navigation policy, login script, website-data clearing
  ui/        Compose screens (dashboard, form, browser, settings, lock) + ViewModel
app/src/test/  JVM unit tests (URL, repository, vault, crypto, PIN, backup, navigation policy, filters)
```

---

## Windows

### Install
1. On GitHub open **Actions → Windows Build →** latest green run (or **Run workflow**).
2. Download the artifact **`travelhub-windows-installer`** (a zip) and unzip it to get `TravelHub-Setup-<version>.exe`.
3. Double-click the installer. It is **not code-signed**, so Windows SmartScreen may say "Windows protected your PC": choose **More info → Run anyway**
   (only do this for a build you made from your own repository).
4. Choose the install folder, finish, and start **TravelHub** from the Start menu or desktop shortcut. It installs per user; no admin rights are needed.

Uninstall from Windows **Settings → Apps**. Your data lives in `%APPDATA%\TravelHub` (`portals.json`, encrypted `vault.json`, `settings.json`, `lock.json`); uninstalling does not remove it.

### Build locally
```
cd windows
npm install
npm test          # core logic unit tests (Node's built-in runner)
npm start         # run from source
npm run dist      # builds dist/TravelHub-Setup-<version>.exe (run on Windows)
```

### Windows features & status
| Area | Status |
|---|---|
| Add / edit / delete portals, categories (custom), notes, icon text, colour, favourites, pin, enable/disable, move up/down | ✅ |
| URL validation (https default, explicit http warning + per-portal opt-in, duplicate detection) | ✅ |
| Dashboard: search, category chips, favourites filter, sort (manual / A–Z / recent), list/grid, empty state, delete confirmation | ✅ |
| Credential vault: Electron `safeStorage` = Windows DPAPI, separate file, never plaintext, no plaintext fallback | ✅ (self-tested on a Windows runner) |
| App lock: scrypt-hashed PIN, lockout throttling, auto-lock when idle/minimised and on Windows lock-screen, "forgot PIN" erase flow, screen-capture blocking | ✅ |
| Windows Hello / fingerprint unlock | ❌ not available (PIN only) |
| In-app browser (one isolated session per portal): back/forward/refresh/stop, title + domain, retry page, downloads (https only), uploads (native file dialog), certificate errors blocked, permission prompts denied, same navigation policy as Android | ✅ implemented — see "Not verified" |
| Login assistance: manual fill, optional auto-fill, optional single auto-submit, origin-checked; never touches CAPTCHA/OTP | ✅ implemented — see "Not verified" |
| Per-portal sign-out URL, clear session, forget login, reset login settings | ✅ |
| Export / import portal list (same format as Android) | ✅ |
| Encrypted backup including passwords | ❌ not implemented |
| Automatic sync with Android | ❌ not implemented (see top) |

### Windows security design
* **Passwords** are encrypted with `safeStorage` (DPAPI, key bound to your Windows user account) and stored in `vault.json`, separate from the portal list.
  Each blob carries its portal id inside the encrypted payload, so blobs can't be swapped between portals. Nothing is logged, exported or committed.
  If DPAPI is unavailable the app refuses to save a login rather than falling back to plaintext.
* **DPAPI does not protect against other programs running as you** (or malware with your privileges). It protects against other Windows users and other PCs.
* **PIN**: 4–12 digits, scrypt + unique salt, constant-time compare, escalating lockouts. **Forgot PIN** can't reveal or bypass it; it erases saved logins and sessions.
* **Browser**: each portal is a sandboxed `WebContentsView` with its own session partition (cookies are isolated per portal, unlike Android WebView), no preload/IPC bridge,
  `file:`/`javascript:`/`data:`/`blob:`/unknown schemes blocked, http only for opted-in portals, cert errors never bypassed, camera/mic/location/notifications denied.
  The app UI itself runs sandboxed with a strict CSP, context isolation and a minimal, whitelisted preload API; credentials only travel UI → main (write-only), never back.
* **Credential filling** only when the *current* page is https on the portal's host, a subdomain, or a domain you listed, re-checked just before injecting; one automatic attempt per visit.

### Windows CI (what is actually verified)
`windows-build.yml` runs on `windows-latest`: install → **54 unit tests** → build the NSIS installer → **silently install it and launch the installed app with `--selftest`**,
which checks DPAPI availability, a vault save/decrypt round trip, that no plaintext hits disk, an empty portal list on first start, that the UI loads with its bridge, shows the empty state and has no Node access, and that a portal view can be created.

### Not verified
* The in-app browser and login filling are **not exercised against real websites or by automated UI tests**; only the underlying policy/validation logic is unit-tested.
* The installer is unsigned (SmartScreen warning). Dependencies are resolved with `npm install` at build time (no committed lockfile).
* Windows-specific behaviour on older Windows versions, high-DPI layouts and screen readers has not been tested.
