# KAI Android 0.4.5 alpha — local speech responsiveness

KittenTTS/Kokoro now prepare one phrase ahead while the current phrase plays.
A single AudioTrack is reused per reply. Model loading warms silently while
listening or beginning a reply; the foreground binding idles for up to five
minutes and releases on full stop, voice change, close or background. Native
synthesis stays serialized; at most two phrases are reserved. Prepared audio
never advances the conservative heard-text ledger. Models, speakers, natural
sentence boundaries, normal pitch, saved speed and the 160-character bound remain.

Validation completed:
- 238 JVM tests passed, no failures, errors or skips.
- Android lint passed: no errors/fatal issues; 18 warnings.
- Debug, Android-test and ARM64 phone APK assembly passed.
- 19 Android checks passed on Android 9 x86_64 software emulation: eight buffered
  controller tests with real AudioTrack and recorded native Kiki PCM, eight
  existing Android TTS regressions, real Kitten model warmup/reuse over the private
  Messenger service, and upgrade seed/verify with encrypted synthetic data.
- Controller checks covered streamed sentence lookahead during playback, one
  output per reply, interrupted preparation, stale descriptor disposal, replacement
  replies, voice changes, deferred generation/language errors, silent warmup,
  release on full stop, blank streams and trailing whitespace.
- The 0.4.4 baseline was rebuilt as a universal APK for x86_64; all 842 relevant
  payload entries match the released ARM64 0.4.4 APK byte for byte. Its signed
  in-place update to 0.4.5 preserved the saved Kiki voice, other character settings,
  synthetic credentials, speech rate and conversation. All seven voice IDs persisted.
- The phone APK is ARM64 only. All 842 relevant payload entries match the universal
  APK used for Android testing. All seven included native libraries and 379 voice
  assets are unchanged from 0.4.4. Speech ONNX assets are compressed for streamed
  extraction; the memory ONNX asset remains stored for mmap.
- Package com.kai.agent.preview, versionCode 12, original owner signing certificate
  SHA-256 a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89.

Limits: Controller playback tests use controlled generation and actual native Kiki
PCM, rather than fresh neural synthesis of every sentence. Real Kitten model
loading and the warm protocol passed separately. Model/audio identity is unchanged
from 0.4.4. No physical phone, Bluetooth, live account, paid inference or acoustic
barge-in verification was performed. These checks establish queue behavior and
lifecycle safety, not phone timing or subjective smoothness. Synthesis slower than
playback can still cause gaps; recognition/model-token latency also remains.

The initial Android run had one fixture error: a test called speak with blank
preview text, which the existing preview API deliberately ignores. The fixture was
corrected to use beginSpeech/appendSpeech/endSpeech, then all sixteen controller
and platform tests passed. No production code or phone APK changed for that fix.
The test emulator restarted during the verification session; the final rerun used
the same persisted app data. Initial and final results are retained in this folder.
