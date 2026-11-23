> Historical documentation. These voices were retired in 0.4.6; see LOCAL-TEST-VOICES.md.

# Desktop KAI voice in Android 0.3.1

> Update 0.4.3: Cute is the default and uses the Android speech engine. Azelma remains an optional original model and may be slow or unstable on phones. See `PLAYFUL-DEVICE-VOICES.md`.

The optional original voice uses the same pinned Azelma Pocket model, reference recording and Cute KAI effects used by the desktop Assistant. It runs on the phone with either inference provider. No desktop connection, paid speech API, project key, separate download or shared account is needed for synthesis. Model weights and notices are bundled in the APK.

The `kai:azelma:cute` voice uses the desktop default +9 semitone effect with tempo 1.08. The existing speaking-speed slider applies an additional speed adjustment without changing pitch. Voice identity is English. Installed Android voices remain selectable for other languages and preferences; there is no silent switch to cloud synthesis.

## Defaults and upgrade

Historically, 0.3.1–0.4.0 and 0.4.2 selected the desktop voice for KAI. In 0.4.3, new installs select Cute. On the first 0.3.1 load, KAI's old blank/device-default choice moves to the desktop voice. An explicitly selected named voice is preserved. Other characters keep their choices. A persisted migration marker means choosing Device default after this update stays selected across restart. The migration is committed alongside the existing encrypted state, without a database schema change or credential reset.

## Synthesis and lifecycle

The existing streaming reply queue still supplies one bounded phrase at a time. A non-exported `:desktop_voice` service verifies and extracts the bundled model into app-private storage, then performs CPU synthesis. Its reference, generation parameters and continuous tone processing match desktop: 24 kHz, five steps, seed 42, chunk size three, ten-second reference bound. Complete phrases are buffered before playback to prevent native generation gaps from breaking up speech on slower phones. Text/model streaming is retained, but this is not a full-duplex realtime audio engine.

Completed replies retain the loaded engine for the next reply, with an idle release after one minute. Stopping, interruption, audio-focus loss and backgrounding invalidate pending native/playback callbacks. Cancellation releases the bound synthesis process. Playback uses Android AudioTrack; final completion waits for its playback head rather than the end of synthesis or a buffer write. Native output has no word timestamps, so interrupted context retains only completely played phrases. It never claims a partially heard phrase was fully heard. Generated PCM is transient app-private cache data and is removed after descriptor handoff; startup clears any synthesis fragments left by process death.

The bundled native ONNX Runtime has a distinct SONAME, `libkai_pocket0.so`, and the sherpa JNI dependency references that exact name. Memory's ONNX Runtime and its JNI library remain byte-for-byte unchanged. This avoids packaging two incompatible runtimes under one filename. All native segment sizes and alignment are preserved by an equal-length string-table rename.

## Sources

- Desktop Assistant commit `575940a2d6405ff6a933cc5dec8c824875b11824`: `pocket.json`, `pocket-voice-worker.js`, `mascot-pocket.js`.
- Prior Android voice port commit `316efa235b8d9b9cd6b2b3ee69bb6b01ab9855df` in `therexdev/kaiapp`: adapted synthesis, process client/service and tone code. No changes were published to that legacy repository.
- Model and voice URLs, byte sizes and SHA-256 values: `desktop-voice-catalog.json` and the copy bundled with the assets.
- Runtime AAR 1.13.7: SHA-256 `c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8`.
- Licenses and attribution: `app/src/main/assets/desktop-voice/`.

See the release validation for executed checks. Emulator-generated audio is not proof of physical-phone speech latency, echo cancellation, Bluetooth behavior or battery use.
