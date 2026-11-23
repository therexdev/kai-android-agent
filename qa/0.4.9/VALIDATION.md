# KAI Android 0.4.9 alpha

Character and Chat assistant replies now use native styled Markdown text. Bold,
emphasis, headings, lists, code, strikethrough and link labels display without
their delimiters. Tables become readable rows. Valid HTTP(S) and simple email
links open only when tapped. Raw HTML stays literal native text; image Markdown
uses alt text without loading a remote image. User input and approval arguments
remain literal, and original stored/exported assistant source is unchanged.

Kiki and Android TTS receive readable words, not Markdown syntax or hidden link
destinations. Incomplete streamed formatting waits for resolution. Complete
formatted sentences can start before the response ends. Spoken UTF-16 positions
map back to original source for conservative interruption/history accounting.
Echo references use readable played/current words, excluding prepared future
phrases. Kiki remains the default bundled voice.

Completed checks:
- All 262 JVM tests passed, with zero failures, errors or skips. New cases cover
  all token splits and character-at-a-time streaming, bold email, inline and
  reference links, formatting-only replies, code fences, lists, tables, escapes,
  entities, ordinary operators/underscores, early start and exact source offsets.
- Android lint passed with zero errors and 20 warnings. Debug, Android-test and
  ARM64 builds passed. CommonMark 0.32.0 includes the API 24+ compatibility fix;
  the parser and renderer were exercised on Android 9, below API 30.
- All 27 Android tests passed on an API 28 x86_64 software emulator:
  2 native Compose Markdown/link tests, 12 buffered local speech tests,
  5 platform TTS tests, 3 heard-history/lifecycle tests, 2 pinned-panel tests,
  2 recognition/interruption tests and 1 encrypted upgrade test.
- Actual Character and Chat screens rendered bold email and labeled clickable
  links. Taps invoked an injected URI handler with the expected URL. Unsupported
  schemes produced no URL action. Synthetic screenshots are in render-checks.
- Controlled local synthesis requests and platform TTS requests contained clean
  words while completion reported original source length. Interruption did not
  credit a prepared link or tail; late audio was closed. Playback, prefetch,
  long-paragraph continuation, failure ordering, echo and replacement checks pass.
- Existing heard-prefix, background-stop and interrupted correction tests pass.
  The animated companion remains pinned during transcript scrolling/streaming,
  including the existing compact-window/large-font check.
- The signed 0.4.8 (15) -> 0.4.9 (16) in-place install preserved the existing
  encrypted continuity fixture: credentials, exact ownership, conversation,
  deliberate device-voice choices, saved speech rate and Kiki defaults.
  An interrupted emulator session during a later history test required a restart.
  That test's profile.absent marker confirmed it had created a temporary profile;
  the temporary profile and marker were moved aside to restore the initial state.
  The separate encrypted continuity fixture was untouched and passed its check
  again before the history checks were rerun.
- The first pinned-panel run lost focus to an Android System UI ANR dialog,
  confirmed by the window dump and screenshot. After dismissing that system
  dialog, the same unmodified APK/test APK passed both pinned-panel tests.
  Initial failure evidence is retained alongside the passing run.
- The ARM64 installer matches all 827 relevant tested universal APK entries.
  Its seven native libraries, Kiki model, speaker embeddings and tokens match
  0.4.8. Retired voice assets remain absent. Speech ONNX is compressed and the
  memory ONNX remains stored for mmap. Compiled source hashes match this archive.
- Package com.kai.agent.preview; versionCode 16. Original signing certificate:
  a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89.

Limits: speech tests use controlled TTS/PCM services to verify exact engine input,
playback ledger and lifecycle behavior. They do not establish physical-phone
acoustics, Bluetooth performance or end-to-end neural latency. Kiki's model and
native synthesis engine are unchanged; neural quality was not retested here.
Link tests use a local URI handler and do not navigate, send email, access live
accounts or make paid inference calls. No realtime audio integration is added.
