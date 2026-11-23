# KAI Android 0.4.4 alpha — voice comparison build

Added KittenTTS Nano v0.8 int8 (Kiki, Rosie, Bella, Luna) and Kokoro v1.0 int8
(Heart, Bella, Sky). All seven use their own speaker embeddings and bundled offline
models. Existing selected voices are preserved.

Validation completed:
- 235 JVM tests passed; no failures, errors or skipped tests.
- Android lint and debug/test APK assembly passed.
- 13 Android tests passed: upgrade/persistence, prior Android
  voice regressions, cancellation during native preparation, production AudioTrack
  playback of real Kitten/Kokoro clips and cancellation of real native PCM playback.
- Actual 0.4.3 -> 0.4.4 signed in-place upgrade passed, preserving synthetic settings,
  credentials, conversation and character voice selections. New selections persisted.
- All seven voices generated complete non-silent audio with different PCM hashes in
  a Linux CPU model test using the same runtime version. Five completed native Android
  clips were recovered and checked: all four Kitten voices and Kokoro Heart. Selected
  speaker embeddings were also verified distinct.
- All existing native libraries are byte-for-byte identical to 0.4.3. Every new model
  asset was checked against its SHA-256 manifest in the signed APK.
- Package com.kai.agent.preview, versionCode 11, existing signing certificate retained.
- The phone download is ARM64. Its classes, resources, assets and included native
  libraries match the universal APK used for the x86_64 emulator checks.

Limits: The long all-seven Android generation run was interrupted without a completed
test result because software emulation was too slow. Native synthesis and production
AudioTrack playback were checked separately; full controller/conversation audio was
not verified end to end. Device checks used disposable Android 9 x86_64 emulators and
synthetic data. No live account calls or paid inference were used. These checks do not
establish physical-phone latency, perceived voice quality, echo handling or Bluetooth
behavior. The first preview prepares the selected model and may be slower.
Use Agent -> Voice, preview the named options, then select a row to keep that voice.

Detailed results: qa/0.4.4/. Model/speaker information: docs/LOCAL-TEST-VOICES.md.

Packaging: stream-read speech weights are compressed in the phone APK. The memory model remains uncompressed for mmap. All payload bytes match the validated universal build.
