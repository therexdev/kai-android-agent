# KAI 0.4.0-alpha validation

This build makes character conversation the default Agent view, exposes Voice and Chat together, and adds resumable tasks, routines, and project files/camera/memory. The desktop Azelma/Cute KAI synthesizer and assets are unchanged.

The universal APK is **381,740,044 bytes**, version code **7**, application ID `com.kai.agent.preview`, for Android 9/API 28 and later. Install over KAI (Alpha); do not uninstall first.

- APK SHA-256: `47535b07ccf0491dd764ed97af038c0382fe2d2dfa469875a1be6d24f3176925`
- Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`

## Final build checks

| Check | Result |
| --- | --- |
| JVM tests | 214 passed; no failures, errors or skips |
| Android lint | No errors; 9 warnings |
| Main and instrumentation APK assembly | Passed |
| Signing continuity | Original alpha certificate verified |
| Zip alignment | `zipalign -c -P 16 -v 4` passed; this is not a 16 KB-page device test |
| Native and voice continuity | 36 existing native/desktop-voice files byte-identical to 0.3.1 |
| Native packaging | Libraries compressed and extracted by Android; all four ABIs retained |
| Final installation | In-place `pm install -r` from a staged APK succeeded |
| Compiled inputs | Source/resource/build-setting hashes matched before packaging |

Lint warnings are advisory; reports and JVM XML are in `qa/0.4.0`. The build runs offline against cached dependencies. No shared publisher API credentials or private signing keys are in this source archive.

## Android verification

The final packaged APK passed **17 instrumentation tests** on an API 28 x86_64 software emulator, without hardware acceleration. This includes:

- Native Pocket, sherpa and ONNX Runtime libraries load together after compressed installation.
- SQLCipher/Keystore persistence, shared UI/worker store access, transaction serialization and reopen.
- Task checkpoints, pause/resume, completed-step preservation, stale-worker rejection, needs-input directions, and refreshed context after attaching a file to a paused task.
- Ambiguous submitted actions recover as UNKNOWN and are not replayed.
- Routine occurrence idempotency, no overlapping unfinished run and no missed-occurrence backlog.
- Project/file/memory deletion stays deleted after old-backup import. Pending chat actions disappear; submitted receipts survive without old instructions or result context, including late results.
- Real bundled Latin OCR of generated photo and PDF fixtures.
- Real WorkManager executes the production task worker and persists a plan and outputs using a device-local MockWebServer inference endpoint.
- Compose controls for character Start talking, the Voice selector, shared Chat, attachments, task directions, routine editing and projects. Screenshots are fixture-only.
- Six real ViewModel/store/mock-HTTP voice lifecycle checks: settings persistence, interrupted heard-prefix ownership, background stop, local reply completion, cancelled-stream isolation, and stop followed by a new turn. Speech playback/recognition is controlled in the audible-path tests; they do not validate microphone acoustics.

A separate earlier 0.3.1 → 0.4.0 candidate upgrade check seeded synthetic memory and settings in 0.3.1, installed 0.4.0 in place, and verified their preservation (two one-test runs passed). The final build retains the same schema, package and signing identity. Prior-version QA remains labelled by version.

## Resolved test and environment issues

The final emulator streamed-install client timed out before updating the old package. The same APK was then copied to the device and installed locally with `pm install -r`; its successful result and the subsequent instrumentation run are retained.

An earlier WorkManager fixture waited for validated Internet, which this emulator does not have. The fixture now enqueues the production worker without that constraint because its mock endpoint is on the device itself. Production scheduled work retains its CONNECTED network constraint. The earlier 10-test run had one timeout and is not counted as a passing suite.

A preceding 17-test candidate run passed 16 tests and timed out on the second Reduce motion toggle. The fixture now waits for the rendered off state and invokes the switch's semantic click action before verifying persistence. The final 17-test run passed. Earlier build attempts that exceeded available RAM were superseded by the successful final build; compilation and emulator verification ran separately.

## Remaining phone checks and limits

Physical-phone microphone recognition, acoustic interruption/echo, Bluetooth routing, native voice latency, battery behavior, Android 13+ notification permission, actual camera-app handoff, and real background schedules through Doze/reboot have not been validated here. The app uses Android's standard picker/camera and WorkManager APIs; the camera/OCR test supplies fixture files rather than taking a real photograph.

The native voice runtime was not changed. The previous 0.3.1 emulator generated real Azelma audio, but its full production speech-controller test exceeded the 120-second synthesis budget under software CPU emulation; separate playback of the generated audio passed. That limitation remains relevant. This release's controlled speech tests do not replace a physical-phone end-to-end voice check. See `qa/0.3.1/VALIDATION.md` for the original result.

No live paid model inference, configured customer credentials, connected-account writes, or physical-phone installation was performed. Task-worker and voice-inference tests use local mock services. Routines can be delayed by Android power/network conditions. Image analysis needs a configured vision-capable OpenAI-compatible model; photos otherwise contribute locally extracted text. Project retrieval uses bounded keyword excerpts, not full-document semantic indexing.
