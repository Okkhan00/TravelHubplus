# TravelHub

A personal Android app that is a single hub for **your own** travel-agent websites. You add each portal
yourself (name + address, optional login); the app ships with an **empty** list and no built-in portals.

* Package: `com.tabeer.travelhub` · Kotlin · Jetpack Compose · min SDK 26 · target SDK 35
* No account, no backend, no ads, no analytics. Nothing leaves the device.

## Get the APK (no Android Studio needed)

1. Open the repo's **Actions** tab → latest green **Android Build** run (or click **Run workflow**).
2. Under **Artifacts**, download **`travelhub-debug-apk`** (a zip containing `app-debug.apk`).
   Test/lint reports are in **`travelhub-build-and-test-report`**.
3. Unzip, copy `app-debug.apk` to your phone, open it, and allow "Install unknown apps" for your file manager/browser when asked.

This is a **debug-signed** build for personal use. A release/Play build would need your own signing key,
supplied via repository secrets — never commit a keystore.

## Build locally

```
./gradlew testDebugUnitTest lintDebug assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
Requires JDK 17 and the Android SDK (platform 35).

## Features & status

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

## Security design

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

## Layout

```
app/src/main/java/com/tabeer/travelhub/
  data/      Portal model, JSON repository, URL validation, filters, backup codec, settings
  security/  AES-GCM cipher, Keystore key provider, credential store, PIN manager, biometrics
  browser/   navigation policy, login script, website-data clearing
  ui/        Compose screens (dashboard, form, browser, settings, lock) + ViewModel
app/src/test/  JVM unit tests (URL, repository, vault, crypto, PIN, backup, navigation policy, filters)
```
