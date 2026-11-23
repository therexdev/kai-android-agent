# KAI Android 0.4.8 alpha

This release improves natural pause timing, pending-turn deadlines, long-dictation
watchdog recovery, interruption capture and echo references. Long speech chunks
inspect only a bounded window; UI display updates are coalesced while speech and
format validation still receive every delta. Saved completed replies release
their temporary playback ledgers. Kiki and the pinned companion panel remain.

Android 12+ has a foreground communication-route picker with route confirmation,
loss handling and cleanup. Voice includes opt-in, bounded numeric diagnostics
exported through Android's document picker. No audio, transcripts, device names,
addresses, credentials or account IDs enter that report. It does not auto-upload.

Completed checks:
- 248 JVM tests passed, with zero failures, errors or skips. New cases cover
  adaptive pause lengths, unchanged pending deadlines during idle endpoints,
  unplayed lookahead versus echo, a 1,500-paragraph reply with exact source offsets,
  and opt-in diagnostic privacy/bounds. Existing strict reply grammar tests pass.
- Android lint passed with no errors; final debug, Android-test and ARM64 builds passed.
- 31 Android regressions passed on an Android 9 x86_64 software emulator:
  seven new conversation tests, ten buffered voice regressions, four platform-TTS
  regressions, seven VM/lifecycle/persistence tests, one new voice-controls UI test,
  native Kiki warm/reuse and an encrypted-data continuity check. The final routing
  build reran 23 controller/output/UI/update checks. The other eight VM/store and
  silent native-warm checks used identical compiled source inputs, recorded in
  routing-component-changes.json.
- Conversation tests use actual SpeechRecognizer IPC with a scripted test-only
  RecognitionService, real controller state and fixture TTS/PCM. They cover empty
  endpoints, missing finals, preserved multi-part interruption, unplayed text,
  fatal errors and unsent input. A 70-second active-dictation script survives the
  old startup watchdog. Twenty successive user turns and explicit stops recover
  in one foreground session. These are not acoustic recognition measurements.
- VM tests use the real encrypted store and mock inference. They verify early
  streaming, cancellation, background stop, heard-prefix-only context, preferences
  and ten completed persisted turns releasing their transient playback ledgers.
- The new UI test reaches route selection and Start/Save/Clear test controls,
  confirms that starting diagnostics does not start voice, and prevents route
  changes while voice is active. Prior pinned-panel checks remain in qa/0.4.7.
- A synthetic encrypted fixture was seeded under signed 0.4.5, verified under
  0.4.7, then retained across the 0.4.8 update/rebuilds. Credentials, exact owner,
  conversations, speech rate, saved base metadata and deliberate device voices
  remain; Kiki defaults remain selected. The two old-version seed/verify checks
  are recorded separately from the 31 release regression checks.
- Actual Kiki native model loading and silent warm/reuse passed. Kiki model,
  tokens and embeddings and all seven native libraries match 0.4.7. The ARM64
  installer matches the final tested universal APK's relevant payload; the
  memory ONNX remains stored for mmap and retired voice assets remain absent.
- Package com.kai.agent.preview, versionCode 15; original certificate SHA-256:
  a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89.

Limits: No physical phone, live accounts, paid inference, acoustic microphone,
speaker echo, actual Bluetooth headset, phone call, heat or battery run was
performed here. The API 31+ communication-routing branch was compiled/linted but
not exercised on this API 28 emulator. Recognition-service microphone routing
still depends on the phone/provider. The echo improvement is a transcript filter,
not hardware/software AEC. Realtime cloud audio is not implemented or enabled.
No phone latency or sustained acoustic reliability claim is made. Follow the
15–20 minute phone procedure in docs/NATURAL-CONVERSATION.md and save its private
timing/error report to guide the next iteration.
