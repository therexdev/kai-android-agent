# KAI 0.2.1-alpha validation

The current universal debug APK is **178,048,803 bytes**, version code **4**, application ID `com.kai.agent.preview` (Android 9/API28 or later).

- APK SHA-256: `11b395cdfbf2b85a065d195450ce06fa0c5e4b5ff3f2286f988b6928c09e327a`
- Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`
- The certificate matches the archived 0.2.0 APK and separately saved original signing key.
- Source hashes are in `qa/0.2.1/compiled-source-sha256.json`; build, signature, alignment, JVM and lint evidence are stored beside it.

## Current results

| Check | Result | Evidence |
|---|---|---|
| JVM tests | 158 passed, 23 suites, zero failures/errors/skips | `qa/0.2.1/jvm-summary.json`, `jvm-results/` |
| Android lint | 0 errors; 5 advisory warnings | `qa/0.2.1/lint-results.txt` |
| App and instrumentation APK | Assembled successfully | `qa/0.2.1/build-final.log` |
| Update signing | Original certificate preserved | `apk-signature.txt`, `previous-apk-signature.txt` |
| Native packaging | 16 shipped native libraries byte-identical to 0.2.0; 16KB APK zip alignment passed | `native-libraries.json`, `apk-alignment.txt` |
| Actual 0.2.0→0.2.1 upgrade | Seed and verify passed; UID 10067 preserved | `qa/0.2.1/seed-020.txt`, `verify-021.txt`, package reports |
| Gmail UI and agent flow | 4 passed in 161.183s | `qa/0.2.1/gmail-agent-ui.txt` |
| Cold launch | MainActivity rendered and foreground; clean check limited by emulator System UI ANR | `qa/0.2.1/cold-*`, `screenshots/00-cold-launch.png` |

## Provenance and scope

A workspace reset removed an earlier unuploaded 0.2.1 candidate. This release was reconstructed from the saved 0.2.0 source and the recovery notes. The results above were rerun against the reconstructed source; they do not rely on the lost candidate's test results. A first packaging attempt used a newly generated debug key at Android Gradle's XDG key location; this was caught by signature verification before delivery. The original key was restored, both APKs were repackaged, and the certificate was checked against the archived 0.2.0 APK.

The JVM tests cover safe account identity, Gmail profile parsing and caching, pagination/fallback/ranking, credential/session invalidation, bounded schemas and conversation context, action binding, error handling, existing memory behavior and existing voice/inference behavior. Four device fixtures passed for two-account email identity/discovery/read, catalog permission denial, one-account read failure followed by success on the other account, and ordinary chat when the initial account listing is unavailable. They use localhost synthetic Composio and inference responses; no real inbox, OAuth scope, project key, email send, social post or external account action has been verified.

The upgrade test ran on the actual archived 0.2.0 APK (seed 19.297s) and the final 0.2.1 APK (verify 7.261s). It verified preserved synthetic AI/Composio credentials, exact Composio user ID, pinned note, linked Aurora/daughter/October facts and evidence, memory settings, encrypted reopen, secret-free export and unchanged UID. The four Gmail fixtures verify displayed email identities and both exact account reads after paginated fallback; catalog 403 retains credentials and account views without prescribing reconnect; a first-account read 403 does not discard the second account’s discovered tool or replay the failed action; initial account-list failure does not block ordinary chat.

After these passes, evidence copying failed because a local screenshot destination folder did not exist. This was a host-side reporting error, not an app or test failure. The folder was created. After reboot, external storage remained unavailable until the emulator user was unlocked. A final finish-only pass dismissed keyguard, confirmed `RUNNING_UNLOCKED`, recovered all 10 evidence files, and captured the actual MainActivity; no completed tests were rerun.

The final cold-launch command reported an Android timeout. KAI remained alive as PID 2765, MainActivity was foreground, and the final screenshot shows the KAI home screen rendered behind a **System UI is not responding** dialog. This is not recorded as a clean cold-launch pass. The captured log contains no `FATAL EXCEPTION` or `ANR in com.kai.agent.preview`; that limited log observation is not a physical-phone startup guarantee. No further restart was attempted.

All recorded source hashes were rechecked against the final source before packaging; all matched. The output APK hash matches the binary used for the upgrade and Gmail tests.

The prior 0.2.0 release validation is preserved in `qa/0.2.0/VALIDATION.md`. Its physical-device/voice limitations remain. This patch does not change the memory schema or voice pipeline.

## Reproduction

The build uses Gradle 8.13, AGP 8.11.1, Kotlin 2.1.20, JDK 21 targeting JVM 17, compile/target SDK 36 and build-tools 36.0.0. Dependencies are unchanged from 0.2.0.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Run device fixtures only in an isolated preview emulator, never against personal phone data. The synthetic profile is backed up/restored. `SeedGmailUpdate020DeviceTest` runs with the actual archived 0.2.0 APK installed; `VerifyGmailUpdate021DeviceTest` runs after an in-place install of 0.2.1. `GmailAgentFlowDeviceTest` contains the four local-fixture UI cases.
