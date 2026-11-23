# KAI 0.3.0-alpha validation

The universal debug APK is **184,210,573 bytes**, version code **5**, application ID `com.kai.agent.preview` (Android 9/API 28 or later).

- APK SHA-256: `bb7acc16b001594ecce134b77ee8f223d9675debcc06e03a00dcd9a14b5f7df5`
- Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`
- The original alpha signing identity is preserved. Install over the existing KAI (Alpha); do not uninstall first.
- Final source hashes, build metadata, signature, alignment, JVM and lint evidence are in `qa/0.3.0/`.

## Final binary checks

| Check | Result | Evidence |
|---|---|---|
| JVM tests | 194 passed in 28 suites; zero failures, errors or skips | `jvm-summary.json`, `jvm-results/` |
| Android lint | 0 errors; 8 advisory warnings | `lint-results.txt` |
| App and instrumentation APK | Assembled successfully | `final-visible-build.log` |
| Update signing | Original certificate preserved | `apk-signature.txt` |
| Native packaging | 16 shipped native libraries byte-identical to 0.2.1; 16KB APK zip alignment passed | `native-libraries.json`, `apk-alignment.txt` |
| Visible characters | 18 captures passed SVG geometry and actual screen-pixel assertions; all four characters manually reviewed | `final-candidate-bb7acc/` |
| Studio selection and reduced motion | KAI/Nova/Sage checkpoints passed; focused Spark/tap/return continuation passed in 55.445s; one live renderer | `final-candidate-bb7acc/focused-studio/`, `final-validation-summary.json` |
| Cold launch | Actual MainActivity and KAI artwork rendered; Android launch timing limited by an 11,220ms timeout | `device-20261116T181855Z/final-native-mainactivity-start.txt`, final screen capture |
| Idle animation | Actual KAI screen poses differ between two captures; 27,751 changed pixels inside the avatar crop | `final-candidate-bb7acc/idle-motion-comparison.json` |

The lint advisories concern newer dependency versions, two Kotlin URI conveniences, and the WebView touch handler. The surrounding native Compose element owns the accessible character click action; the web content itself is hidden from accessibility. No dependency or native-library upgrade was made as an incidental lint cleanup.

## Voice, upgrade and connection regression evidence

Device checks used an isolated API 28 x86_64 emulator with Chromium 66 WebView and software CPU emulation. The full voice/store/upgrade suites ran on candidate `c29591e506bfdd59138b0c5622adf7d16919b9cdb6b8b5bca195b827a6b38cb9`. The selected-persona prompt and Gmail suites then ran on candidate `2b6724ae0c0fc0851e528007f243beac9b28d46b028b4b5ebd112c80b1f5e65d`. Their source manifests and build metadata are retained with each run. Subsequent production changes are confined to `CharacterRig.kt`; the voice, storage, memory, inference and connection code from the second candidate is unchanged in the final binary.

| Check | Result | Run evidence |
|---|---|---|
| Actual 0.2.1→0.3.0 update | Seed and verification passed; UID 10067 preserved | `device-20261116T173751Z/seed-021.txt`, `verify-030.txt`, package reports |
| Voice draft persistence | 1 device test passed | `device-20261116T173751Z/VoiceDraftStoreDeviceTest.txt` |
| Voice turn lifecycle and preferences | 6 device tests passed | `device-20261116T173751Z/VoiceTurnLifecycleDeviceTest.txt` |
| Nova identity and early streamed speech | Focused real-view-model test passed after persona changes | `device-20261116T175530Z/VoiceTurnLifecycleDeviceTest#streamedSpeechStartsEarlyAndInterruptionPersistsOnlyTheHeardPrefix.txt` |
| Gmail identities, discovery and reads | 4 device fixtures passed | `device-20261116T175530Z/GmailAgentFlowDeviceTest.txt` |

The in-place update preserved both synthetic provider keys, the synthetic Composio key and exact user ID, all four per-character voice choices, selected Nova, existing speech speed/reduced motion, a pinned note, and linked Aurora/daughter/October facts with source evidence. The new preferences receive defaults; no memory database schema change is introduced by this release.

The voice tests verify cancellation/new-conversation ownership, stopping before a subsequent turn, speech beginning before model completion, and context containing only the conservatively heard prefix after interruption. They exercise an eight-character heard prefix on interruption and a five-character prefix on backgrounding. Additional cases verify a local nonstreamed reply remains unheard until playback finishes, speech settings persist through encrypted reopen, initially unheard drafts survive process restart, cursors remain bounded, and late writes cannot recreate a deleted conversation. Voice callbacks are controlled test doubles; these checks establish orchestration and persistence, not microphone acoustics.

The Gmail fixtures verify labels for both exact connected email identities, paginated tool discovery with fallback, reads from both accounts, preserving credentials/accounts after a catalog 403, retaining the second account when the first account's read returns 403, and ordinary chat when account listing is unavailable. They use local synthetic Composio and inference responses. They do not access a real inbox, send mail, post to social services, or prove an owner's live OAuth scopes or API permissions.

## Rendering defect and verification

An initial character fixture checked DOM state and accessibility but did not verify drawing. Screenshot review found blank character regions, so that result was rejected and no affected APK was delivered. Diagnostic measurements showed a valid 480×480 native frame and a zero-height SVG. CSS sizing alone did not fix it: AndroidView had supplied native WRAP_CONTENT dimensions, putting the older WebView into intrinsic-height mode.

A diagnostic changing only native dimensions to MATCH_PARENT produced a 240×240 CSS viewport and 24,876 painted samples; the actual screen then showed the complete desktop KAI robot. The production view now uses those explicit native dimensions, a viewport-filling SVG, compatibility expansion for older Chromium selectors, and visual readiness plus nonzero SVG geometry before removing the native poster. Failed renderers retain a visible poster. Asset isolation and network/file/content restrictions remain intact.

The strengthened character fixture requires nonzero SVG height and colored pixels inside the actual avatar's screen bounds. The first final-binary run completed all 16 character/state combinations plus the KAI playback-gap and reduced-motion captures, with 1,372–5,402 colored screen samples per capture. A manual review confirmed visible KAI, Nova, Sage and Spark artwork. That full fixture then reached its 660-second limit during Studio navigation; it is not recorded as a full-suite pass. A focused Studio sweep completed the KAI, Nova and Sage selection checkpoints, then reached its 480-second bound after selecting Spark. The separate Spark/reduced-motion/tap/return continuation passed in 55.445 seconds, with actual screen pixels and one live renderer. Together these checkpoints cover all four Studio selections and returning to Talk; the timed-out aggregate runs are not presented as whole-suite passes. The capture helper was optimized to use bulk pixel reads and avoid repeated synchronous native WebView draws; the production APK was unchanged. Native drawing measurements remain available in the diagnostic evidence. These controls use synthetic UI state and do not claim live audio.

Earlier emulator runs encountered a System UI ANR or Android cold-start timeout. These were recorded rather than counted as clean startup passes. On the final binary, Android's launch command still timed out at 11,220ms, but MainActivity was resumed and a subsequent actual screen capture shows the complete KAI home screen and robot. The captured log contains no KAI FATAL or ANR. Two real screen frames show different idle character poses. This is a render/animation confirmation, not a clean startup-timing pass or a physical-phone performance measurement.

## Scope and reproduction

No physical-phone speakerphone echo, Bluetooth routing, recognizer quality, battery use, frame rate or end-to-end speech latency has been validated here. Android speech services vary by device. Automatic spoken interruption is best effort, with an explicit Your turn control. This build does not include a full-duplex realtime audio transport, always-listening wake word, background microphone or background routine scheduler.

Build toolchain: Gradle 8.13, AGP 8.11.1, Kotlin 2.1.20, JDK 21 targeting JVM 17, compile/target SDK 36 and build-tools 36.0.0.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Run device fixtures only in an isolated preview emulator, never against personal phone data. Upgrade fixtures install the actual archived 0.2.1 binary before the new binary; synthetic profiles are backed up/restored. Device source is under `app/src/androidTest/java/com/kai/agent/upgrade/`. `CharacterPaintDiagnosticDeviceTest` is an explicit diagnostic, not the release visual assertion. `CharacterRenderDeviceTest` contains the strengthened on-screen checks.

The old release validation remains in `qa/0.2.1/VALIDATION.md`. Release packaging verifies every compiled-input hash and the APK hash against the recorded final build. It also checks the source ZIP against that same manifest and excludes signing keys, local SDK configuration and build output. The machine-readable validation scope and earlier-candidate provenance are in `qa/0.3.0/final-validation-summary.json`.
