# Travel Hub — Android Starter v0.1.0

A native Kotlin Android starter for opening the user's two specified travel portals inside an in-app WebView.

## Included
- Home screen with two portal cards
- Travel Advisor and Travel Point Aviation login URLs
- In-app WebView
- HTTPS-only navigation allowlist for the two known portal domains
- JavaScript and DOM storage for modern portal compatibility
- GitHub Actions workflow to build a debug APK and upload a build/test report artifact

## Not included yet
- No usernames or passwords are saved or embedded
- No automatic login yet
- No biometric/PIN lock yet
- No password vault yet
- No verified live-portal compatibility test has been run on a physical Android device

This is deliberate: credentials should not be added until the encrypted vault and portal-specific login flow are implemented and tested.

## Build with GitHub Actions
1. Create a new GitHub repository (private recommended).
2. Upload this folder's contents to the repository root.
3. Open **Actions** and run **Android Build** (or push to `main`).
4. Download the artifact named `travel-hub-debug-apk-and-report`.
5. Install the debug APK on your Android phone and test both portals.

## Manual test plan
1. App opens and displays both cards.
2. Each card opens the correct login URL inside the app.
3. Login page renders and form fields are usable.
4. Back navigation returns to previous page/home as expected.
5. HTTPS errors are never bypassed; non-allowlisted hosts are blocked.
6. Try normal login manually on a test account, then close/reopen the portal and observe session behavior.
7. Test redirects, OTP/CAPTCHA, downloads, file uploads, and any portal-specific errors.
8. Do not use real credentials in screenshots, logs, commits, or bug reports.

## Security references
- Android security tips: https://developer.android.com/privacy-and-security/security-tips
- GitHub Actions security: https://docs.github.com/en/actions/how-tos/secure-your-work
