# KAI Android 0.4.7 alpha

Character mode keeps its live animated companion in a rounded panel below the
Character/Chat/Voice header. The full latest reply scrolls independently below it;
the previous 1,800-character display limit is removed. Incoming text preserves
the reading position. Start/End conversation and the optional Your turn button
occupy one bottom row so speaking/listening changes do not resize the panel.

Removed the general tagline, Open chat & transcript footer, and microphone helper
footer requested in the annotated screenshot. The header Chat tab remains.
Project context, approval cards and temporary-chat information remain reachable
in the scrolling area. Existing character activity, speech and tap-reaction
inputs still drive the same live renderer.

Completed checks:
- 243 JVM tests passed with zero failures, errors or skips.
- Android lint passed with zero errors; debug, Android-test and ARM64 builds passed.
- Four new Android UI tests passed on an Android 9 x86_64 software emulator.
  They exercised the actual Compose screen and animated WebView renderer using
  synthetic UI state and callback fixtures, without starting a model or microphone.
- A long reply retained its opening paragraph. Swiping changed only the transcript
  offset; appending more text preserved that offset. The character panel bounds
  and WebView instance stayed the same, with exactly one renderer. A tap and a
  speaking-to-listening state change updated the existing renderer's DOM.
- Start/End, Your turn and the header Chat tab invoked their expected callbacks.
  Removed copy was absent in both idle and active states. Approval cards and the
  temporary-conversation notice could be scrolled into view without moving Spark.
- In a constrained 320 x 440 dp app window with 1.6 font scale, the panel,
  transcript and talk controls remained separate and usable. The control label
  can ellipsize at that extreme size. Screenshots capture normal and compact states.
- One existing-data device check passed after the signed 0.4.6 (13) -> 0.4.7 (14)
  in-place install. The encrypted fixture originally seeded under 0.4.5 and verified
  under 0.4.6 retained credentials, exact ownership, conversations, deliberate
  device-voice choices, speech rate and saved base metadata; Kiki defaults remained.
- The ARM64 installer matches all 825 relevant entries of the tested universal
  APK. Its seven native libraries, Kiki model, tokens and speaker embeddings match
  0.4.6. All production voice source hashes also match 0.4.6. Retired voice model
  assets remain absent, and the memory ONNX remains uncompressed for mmap.
- Package com.kai.agent.preview, versionCode 14. Original certificate SHA-256:
  a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89.

Limits: Current device checks cover UI/control fixtures and encrypted-state
continuity, not acoustic microphone behavior, live accounts, paid inference or
physical-phone performance. Neural Kiki paragraph playback was validated in
0.4.6; it was not rerun for this UI-only update. No new voice latency claim is made.
