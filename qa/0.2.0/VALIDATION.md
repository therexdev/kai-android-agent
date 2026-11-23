# KAI 0.2.0-alpha validation

The final universal debug APK is **178,332,029 bytes**, version code **3**, application ID `com.kai.agent.preview` (Android 9/API28 or later).

- APK SHA-256: `1c6bd77f559b669205471b7060e3378e33278209bd346ebdea3999e45b38ba99`
- Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`
- The certificate matches the archived 0.1.1 APK, allowing an in-place update. Signing material is kept separately from this source project.
- Final source hashes are in `qa/0.2.0/compiled-source-sha256.json`; the build outputs and recorded source hashes were checked again before packaging.

## Results

| Check | Result | Evidence |
|---|---|---|
| JVM tests | 131 passed; 19 suites; no failures/errors/skips | `qa/0.2.0/jvm-summary.json`, `jvm-results/` |
| Android lint | 0 errors, 19 advisory warnings and 1 hint | `qa/0.2.0/lint-results.txt` |
| Debug app and instrumentation APK | Assembled successfully | `qa/0.2.0/build-final.log` |
| Final-APK encrypted storage | 15 passed in 109.143s | `qa/0.2.0/storage-device-tests.txt` |
| Final-APK direct Composio UI | 1 passed in 97.034s | `qa/0.2.0/direct-composio-ui.txt` |
| Final-APK cold launch | Fully rendered; MainActivity foreground; no fatal crash/ANR in captured logs | `qa/0.2.0/cold-launch*`, `screenshots/00-final-fresh-launch.png` |
| Real archived-APK upgrade | Old 0.1.1 codec seed and 0.2 migration/reopen passed; UID stayed 10067 | `qa/0.2.0/upgrade-*-apk.txt`, `package-before-upgrade.txt`, `package-after-upgrade.txt` |
| Memory UI scenarios | 2 passed in 200.288s on candidate `e94db2ae…` | `qa/0.2.0/memory-ui-tests.txt`, `screenshots/memory-final/` |
| Actual Android offline model | 1 passed, all 6 paraphrases retrieved the correct fact first; 384 dimensions | `qa/0.2.0/embedding-device-test.txt`, `embedding-device-benchmark.json` |
| Native packaging | 16KB ELF LOAD alignment for every shipped SQLCipher/ONNX ABI; final APK zip alignment passed | `native-dependencies.json`, `apk-alignment.txt` |

The final 15 storage cases cover encrypted round trips, tamper/key-loss/missing-database preservation, genuine AES-JSON migration, corrected legacy-note migration, stable connection identity, approval invalidation, FTS, atomic jobs/evidence/vector deletion, import resurrection prevention, temporary/OFF boundaries, recovery-file retirement failure, atomic multi-fact Undo, and edited-note retrieval across restart/import.

The Composio UI fixture uses the actual UI, ViewModel, encrypted store and HTTP decoder with a test-only localhost transport. It verifies quoted-key normalization, exact user-ID reuse, a 614,608-byte catalog page, account discovery, pagination, mixed successful/401/403 permission diagnostics without discarding the key, a blank masked editor, and local key removal with no revoke request. All 8 fixture requests were read-only. No KAI website login is involved.

The memory UI cases verify Aurora/daughter/October links across separate conversations, preservation of unknown day/year, visible sources, explicit identity choice between two Auroras, correct related-person details, Ask-first accept/dismiss/Undo, the use-memory toggle, automatic-memory OFF, and temporary-chat boundaries. Inference replies in these UI tests come from a localhost fixture; no paid model or real account was called.

## Validation provenance

The full memory UI and offline model checks ran on APK `e94db2ae7ca326b47dd2af04c2efe7c88c539be98dc796e88cf8b3584cf462fa`. Their UI/VM and embedding source hashes match the final source; `build-verification.json` lists the exact matching paths and changes. After those runs, the final build repaired manual-note correction/provenance/suppression behavior and added targeted regressions. All 15 storage cases and the Composio UI case were run on the final APK.

The actual 0.1.1-to-0.2 APK upgrade was verified on candidate `83fe3423…`, including preservation of inference settings/keys, direct Composio credentials/user ID, pinned memory, provenance, tombstones and task state. The final binary additionally passes isolated clean-legacy and already-corrected-legacy migration cases. An early SQLCipher PRAGMA execution error was found and fixed before these passes; its failed migration preserved the old input and was successfully retried.

The offline embedding test used the bundled quantized MiniLM model and ONNX Runtime Android 1.27.0 with Wi-Fi and mobile data disabled. On the **software-emulated API28 x86_64 device**, query times were 721–1042ms; cold load plus six fact embeddings took 10985ms. These are emulator measurements, not Samsung or other physical-phone performance estimates. A separately labeled Linux host benchmark is also included.

The first final cold-launch attempt showed a starting window after `am start -W` timed out. The captured native SIGSEGV belonged to Android’s `cmd` utility (PID3072), while KAI remained alive as PID3074. A single clean emulator restart preserved userdata; the final launch check and screenshot are recorded after that restart. The original utility-failure evidence is retained in `qa/0.2.0/cold-launch-before-reboot/`.

## Scope and reproduction

No production credentials, live OAuth flow, social post, email, external write, physical microphone/TTS behavior or physical-phone performance was tested. The older general provider-settings/cancellation UI suite was not rerun for this release; the new memory UI suite does exercise real streaming inference and persisted phone state with a local mock provider. API35 software emulation was too costly for this host, so completed device checks use API28. Sixteen-kilobyte native compatibility was checked statically, not on a physical 16KB-page device.

The build used Gradle8.13, AGP8.11.1, Kotlin2.1.20, compile/target SDK36, SQLCipher4.20.0, AndroidX SQLite2.7.0 and ONNX Runtime Android1.27.0. Standard build commands are:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Run instrumentation only in an isolated preview emulator. The UI/upgrade fixtures replace and restore their own synthetic app profiles; they are not tests to run against personal phone data. Install the app and test APK, then select the storage, memory, embedding or connections classes with `adb shell am instrument -w -e class … com.kai.agent.preview.test/androidx.test.runner.AndroidJUnitRunner`. The two upgrade classes deliberately run on different installed APK versions with the same package and signing key.
