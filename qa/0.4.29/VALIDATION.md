# 0.4.29 validation

## Changes

- Account includes Automatic · connected accounts and explicit credit-source choices. Automatic balance display loads the primary or first verified connected account.
- Use purchased credits enables paid funding without requiring manual wallet selection. Free-only stays the default, source changes reset paid permission, and stale/unlinked account choices cannot enable paid usage.
- New sign-ins and previously blank model settings default to GPT OSS 120B (`akash-gpt-oss-120b`). Existing saved selections are preserved. Account refresh retains the server-supplied limits for the selected model.
- The release publishes the standalone app source, app details, signed APK, and checksums to `therexdev/kai-android-agent`.

## Verification

- Focused account/default-selection and Compose UI regression suites: 18 tests passed; no failures, errors, or skips. Coverage includes primary connected-account balances, explicit-source pinning, missing/unlinked accounts, paid/free request flags, both dropdowns, the paid toggle, sign-in controls, account credit transport, and credential exclusion.
- Existing server connected-credit regression suite: 6 tests passed, using isolated mocked accounts and ledger balances. Covers Core credit links, purchased funding, primary/fallback routing, consent, revocation, and retry binding. No production account was accessed.
- Android lint: zero errors; 19 warnings. Existing Android deprecation/resource warnings remain.
- ARM64 release build completed. Package `com.kai.agent.preview`, versionName `0.4.29`, versionCode `36`, minSdk 28, targetSdk 36.
- APK signature verification and 16 KB ZIP alignment passed. Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`. The signed APK retains the installed alpha identity.
- Public source excludes private signing files, local build settings, generated build outputs, and historical raw QA captures/logs. Bundled assets and their notices remain included.

## Limits

UI tests use Robolectric fixtures. No physical-device or emulator test was run for this release. Live phone sign-in, voice behavior, and paid-credit use remain owner acceptance checks. No live inference request or payment was made. The account backend and desktop Core are not changed by this release.

Install over the existing kai app. Open Account, refresh, select Automatic · connected accounts or a specific connected credit account, enable Use purchased credits, and check that the choice persists after reopening. A real paid request still needs physical-phone acceptance.
