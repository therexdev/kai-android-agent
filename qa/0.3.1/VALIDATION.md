# KAI 0.3.1-alpha validation

This alpha changes KAI's default speech output to the desktop Azelma Pocket voice with Cute KAI effects. It preserves named Android voice choices and changes the old KAI device-default selection once. Other characters keep their existing voices.

The universal APK is **489,817,955 bytes**, version code **6**, application ID `com.kai.agent.preview` (Android 9/API 28 or later).

- APK SHA-256: `bb0f10b2921c5be877e23c2fd5baf1761fb8bc765af9cae03a10af8e36350d03`
- Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`
- Install over the existing KAI (Alpha); do not uninstall first. The package and signing identity are unchanged.

## Build and package checks

| Check | Result |
| --- | --- |
| JVM tests | 202 passed; zero failures, errors or skips |
| Android lint | 0 errors; 10 advisory warnings and 1 hint |
| App and instrumentation APKs | Assembled successfully |
| Signing | Original alpha certificate verified |
| APK native alignment | 16 KB zip alignment passed |
| Existing native dependencies | All 16 libraries from 0.3.0 are byte-identical |
| New native dependencies | Isolated Pocket runtime and sherpa JNI for four ABIs |
| Pinned voice assets | Model/reference SHA-256 and byte sizes verified against the desktop catalog |
| Desktop effects | JVM golden-vector tests match the desktop continuous tone algorithm across chunks |
| Final APK installation | Emulator accepted an in-place update with `adb install -r` |

The lint advisories concern dependency versions, Kotlin convenience APIs and the existing character touch handler. No unrelated dependency upgrades were made. Build logs, results, compiled-input hashes, signature and package checks are under `qa/0.3.1/`. Prior release evidence remains under `qa/0.3.0/` and is not presented as a new 0.3.1 run.

## Android device checks

The final APK was exercised in a four-test native/store run and a separate one-test playback run. Four checks passed; the full production controller test failed on the synthesis time budget. This is an alpha for phone verification, not a claim that end-to-end voice timing passed.

| Check | Observed result |
| --- | --- |
| Real native Azelma synthesis in private process | Passed; generated 23,112 non-silent PCM frames at 24 kHz (0.963 seconds of audio). Cold fixture took approximately 473 seconds under software CPU emulation. |
| Full production VoiceController synthesis/playback | Did not pass: the next native request exceeded the normal 120-second timeout, before playback. |
| Real encrypted default migration | Passed; synthetic provider/Composio settings, exact user ID, conversation and other character voice retained; choosing Device default afterward persisted across reopen. |
| Interrupted speech draft persistence | Passed; unread/partially heard boundaries and deletion retained across storage reopen. |
| Actual AudioTrack playback of generated Azelma PCM | Passed separately in 1.569 seconds; start and completion callbacks observed, with completion no earlier than the audio duration. This calls the production player with the already-generated synthetic greeting, without another synthesis request. |

Detailed instrumentation results, logcat and the synthetic greeting PCM are under `qa/0.3.1/device/`. The greeting is test content, not a recording of user speech. The whole four-test run took 614.451 seconds and reported one failure; adb itself returned zero, which is not treated as a passing test result.

## Limits

This environment uses an API 28 x86_64 emulator with software CPU emulation and no KVM. The cold-native test has a test-only 600-second budget; production speech requests retain their 120-second timeout. The following playback test uses the production controller and a warmed native engine. These are not physical-phone latency benchmarks.

The earlier candidate's device run found Android 9's incompatible ICU script aliases in a Java regex. The final binary replaces those aliases with UnicodeScript checks, with two regression tests. The earlier native cold-start attempt timed out at the production budget. A separate earlier device run passed the real SQLCipher/Keystore voice migration and interrupted-draft persistence tests, then stalled in the pre-existing Compose voice-controls suite. That incomplete suite is not counted as a pass.

Physical-phone speech latency, speaker/echo behavior, interruption, Bluetooth routing, memory pressure and battery use have not been validated here. No live provider credentials or connected-account writes were used. The current voice remains an English voice; installed Android voices remain selectable for other languages/preferences. The package contains the voice model, reference, runtime and notices, so it is larger than 0.3.0 and needs no separate voice download.
