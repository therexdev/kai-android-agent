# 0.4.27 account access validation

## Delivered behavior

- Account replaces manual inference API configuration. The fixed credit-service origin performs email/Google sign-in and account creation in the system browser, then links the phone with a short-lived approval code.
- The website and app use the same authenticated credit ledger and model catalog. Free allowance, purchased credits and model token prices are shown separately. Selecting a wallet and disabling free-only authorizes paid chats, tasks and automatic memory extraction.
- Credentials and pending sign-in secrets stay in encrypted device storage; exports and reports redact them. Sign-out preserves local conversations and memories. Billing accounts do not synchronize the local workspace.
- Vault pairing and payment approval use the account website and KOIN Vault. The Android Vault button is gated on the server capability.

## Automated checks

- Android JVM regression suite: 443 tests passed, zero failures/errors/skips. A final focused account suite passed 7 tests and also verifies account-specific HTTP/credit errors after replacing legacy API-key troubleshooting messages. Account coverage includes browser approval code vs secret handling, persistent serialization vs exports, redirect refusal, expired sessions, authenticated streaming, output-budget clamping, fixed service origin, credit units, and absence of manual inference settings.
- Android lint: zero errors; 30 warnings and one hint. Existing deprecation/resource warnings remain.
- Backend regression suite: 41 tests passed; final transport-redaction/DOM regression subset: 7 tests passed. Evidence is simulated. Tests cover shared account balances/chat/logout, server-verified wallet ownership and origins, unlink handling, reviewed operation binding and non-replay after ambiguous payment requests.

## Android device and package checks

API 30 x86_64 emulator, 480×854 pixels at density 213 (about 360 dp wide): encrypted SQLCipher/Keystore account persistence, credential exclusion from reports/exports, sign-out history/memory preservation, toolbar Account entry, and visible sign-in controls passed. The first UI capture failed because the test selected both activity and dialog roots; the capture selector was corrected and the UI test passed on rerun. No production layout change was required. The reviewed image is `account-sign-in.png`.

The signed ARM64 APK uses package `com.kai.agent.preview`, versionName `0.4.27`, versionCode `34`, minSdk 28 and targetSdk 36. Certificate continuity is checked against `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`; 16 KB zip alignment is verified. The production ARM64 package is built and signed; emulator checks use the matching debug implementation on x86_64. Physical-phone authentication and wallet approval still need owner testing.

## Deployment and live limits

The APK uses existing device-account and credit-gateway routes. The new backend capability and KOIN Vault workflow are prepared locally for `therexdev/kai`, branch `codex/android-account-vault`, commits `a6261a2` and `e2675a9`, on premium-model base `ed35690b0a4d681aa4dd1f2972c95d7a5de724eb`. Publication was blocked by automatic approval review because public publication of this payload/destination was not explicitly authorized. No remote branch/PR was created and no production deployment occurred. Deployment access from this workspace was unavailable.

The guarded updater requires that exact premium-model base already installed and preserves existing payment journals and account data. Vault and catalog context metadata require this update; the Android UI displays Vault as pending until the service advertises support. Live email/Google sign-in, premium-model availability and a real user-approved payment remain acceptance checks. No live payment was made.

## Phone acceptance

Install the signed APK over the existing kai app without uninstalling. Open Account, sign in with the same AI credit account used on the website, approve the phone code, and return. Choose a model and compare the selected-wallet balance with the website. Use a small chat; confirm credits refresh. After deployment, connect KOIN Vault and make a deliberately reviewed small credit purchase; confirm the credited balance appears on both surfaces. Sign out and verify local history remains.
