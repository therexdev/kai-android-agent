# 0.4.28 validation

## Changes

- Account model selection uses a read-only exposed dropdown. Unavailable entries are disabled, and selected-model prices and free-credit eligibility stay visible.
- New sign-ins and previously blank model settings default to GPT OSS 120B (`akash-gpt-oss-120b`). Existing saved selections are preserved. Account refresh retains the server-supplied limits for the selected model.
- The release publishes the standalone app source, app details, signed APK, and checksums to `therexdev/kai-android-agent`.

## Verification

- Focused account/default-selection and Compose UI regression suites: 11 tests passed; no failures, errors, or skips. Coverage includes dropdown interaction, saved-choice round trips, sign-in controls, account credit transport, and credential exclusion.
- Android lint: zero errors; 12 warnings. Existing Android deprecation/resource warnings remain.
- ARM64 release build completed. Package `com.kai.agent.preview`, versionName `0.4.28`, versionCode `35`, minSdk 28, targetSdk 36.
- APK signature verification and 16 KB ZIP alignment passed. Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`. The signed APK retains the installed alpha identity.
- Public source excludes private signing files, local build settings, generated build outputs, and historical raw QA captures/logs. Bundled assets and their notices remain included.

## Limits

UI tests use Robolectric fixtures. No physical-device or emulator test was run for this release. Live phone sign-in, voice behavior, and paid-credit use remain owner acceptance checks. No live inference request or payment was made. The account backend and desktop Core are not changed by this release.

Install the APK over the existing kai app without uninstalling. Open Account, verify the saved choice or GPT OSS default, open the dropdown, choose another available model, and confirm the choice persists after reopening.
