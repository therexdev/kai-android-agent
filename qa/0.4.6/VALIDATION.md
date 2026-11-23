# KAI Android 0.4.6 alpha

Kiki is the only bundled voice and the default for new characters. Removed/default
voice selections migrate to Kiki; explicit Android device voice IDs remain.
Desktop/Azelma, Cute/Cartoon/Squeaky, other Kitten choices and Kokoro are removed
from the selector and service. Desktop and Kokoro model assets are absent from the
APK. Old model caches are removed after successful Kiki initialization. The Kiki
model, full speaker table, tokens and speaker ID 7 are unchanged, preserving its sound.

The reported failure matches a deterministic 0.4.5 chunking bug: the next phrase
could consist only of paragraph-newline characters, so the native service rejected
it as empty. Sentence/flush boundaries now require spoken text. Oversized leading
layout cannot block the 160-character bound. Exact transcript offsets, conservative
heard accounting, bounded lookahead and shared AudioTrack playback are preserved.

Completed checks:
- 243 JVM tests passed, with no failures, errors or skips. Paragraphs, CRLF,
  streamed partial words, long layout, defaults and all retired voice IDs are covered.
- Android lint and debug, test and ARM64 builds passed.
- 19 Android checks passed on an Android 9 x86_64 software emulator: 10 buffered
  Kiki/controller tests, four standard Android TTS regressions, model-cache cleanup
  including symlink safety, old-version seed and updated-version verify, real Kiki
  paragraph playback and the actual warm/reuse protocol.
- The real native test used the actual Kitten model, private Messenger service,
  sentence lookahead and AudioTrack to read three short paragraphs without Android
  TTS. It completed all transcript offsets with one AudioTrack and no blank requests.
  Only its per-operation timeout was extended for slow software CPU emulation.
  Production operation timeout remains 120 seconds. Controller fixture tests also
  read a longer six-sentence story with LF/CRLF and blank paragraphs.
- A real signed 0.4.5 (versionCode 12) -> 0.4.6 (13) in-place update preserved
  encrypted synthetic credentials, exact account owner, conversations, speech rate,
  deliberate device voices and saved base metadata. Default Spark/Sage now use Kiki.
- Cleanup removed only app-managed retired model folders, preserving active Kiki,
  private-state files and unrecognized folders; it did not follow a symlink to state.
- The ARM64 APK matches 825 relevant payload entries in the universal tested APK.
  Its seven native libraries and all Kiki model inputs match 0.4.5. The memory ONNX
  asset remains stored for mmap; Kitten ONNX is stream-compressed.
- Package com.kai.agent.preview; original signing certificate SHA-256
  a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89.

Limits: No physical phone, Bluetooth, acoustic microphone/echo, live account or paid
inference verification was performed. Software-emulator timings are not phone
benchmarks. Synthesis slower than playback can still cause pauses. Native Kiki
speaks English; installed Android voices remain available for other languages.
