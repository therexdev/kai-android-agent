# Kiki in 0.4.6 alpha

Kiki is the default for all new characters and the only bundled voice. Open
Agent -> Voice to preview Kiki or select an installed Android voice. Existing
characters using automatic defaults or removed app voices move to Kiki. Explicit
Android voice IDs are preserved; selecting Device default afterward stays selected.
Legacy voice IDs in custom-character imports normalize to Kiki.

The unchanged KittenTTS Nano v0.8 int8 model uses Kiki speaker ID 7, with its original
speaker table and normal pitch. Other Kitten speakers are not selectable, and
Desktop/Azelma, Cute/Cartoon/Squeaky and Kokoro are retired. The Desktop and Kokoro
model assets are removed from the APK. Native libraries remain unchanged because
they are also required by Kitten. Old extracted model caches are cleared after
successful Kiki initialization, without touching conversations, memory or keys.

The paragraph bug in 0.4.5 allowed a newline-only phrase into native generation.
Sentence boundaries now require preceding spoken text. Early flushes cannot send
only leading layout; very long whitespace prefixes cannot exhaust the phrase bound.
The full original transcript stays intact, and prepared phrases do not count as heard.

One sentence may synthesize while another plays through a reused AudioTrack.
Generation remains serialized, with at most two reserved phrases. Silent warmup,
a five-minute foreground idle connection, explicit stop/background release, saved
speed and the 160-character native phrase bound are retained. A generation slower
than playback can still create a gap. Kiki speaks English; installed Android voices
remain available for other languages. Recognition and inference provider behavior
are unchanged. Local synthesis does not make recognition or connections offline.

Assets are pinned by test-voices/catalog.json. Recreate the bundled model using
tools/bundle_test_voices.py. The phone APK is ARM64; default builds remain universal.
Run tools/compact_voice_apk.py, zipalign and the existing owner signing identity for
the phone download. The memory ONNX model must remain stored for openFd/mmap.
See qa/0.4.6/VALIDATION.md for completed checks and limitations.
