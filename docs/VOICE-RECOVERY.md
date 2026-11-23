# Voice recovery — 0.4.2

> Historical recovery. Version 0.4.3 keeps this playback behavior and replaces the default with Android pitch styles. See `PLAYFUL-DEVICE-VOICES.md`.

The user reported that 0.4.1 made all voices slower and broke the new KAI voice. That release changed several speech mechanisms together; synthetic tests established control flow, not naturalness or latency on the user's phone. This release restores 0.4.0 voice behavior while retaining the account-identity improvements.

## Restored behavior

- KAI uses its original bundled Azelma model and Cute KAI treatment. The model, runtime, effects and native assets are unchanged.
- The experimental `kai:conversation:local` preset is removed from the picker. No automatic installed-voice scoring, 1.12 Android pitch, or 1.02 rate multiplier remains.
- One phrase is submitted at a time. The prior chunk boundaries, 420 ms partial flush, and Natural/Quick/Patient continuation waits (1100/550/1900 ms) are restored. The 72-character early trigger and 120-character first-clip cap are removed.
- Existing interruption, cancellation, output-change/background cleanup and conservative heard-transcript rules remain.
- A saved Fast selection maps to Azelma for KAI, or Device default for another character. Migration runs even when the old 0.4.1 marker was already set. Explicit Android/device/desktop selections and saved speed, pacing and language stay selected. Imported custom characters cannot reintroduce the retired preset.
- Connected-account identity lookup, display names, exact account bindings and nonblocking profile enrichment remain as in 0.4.1.

Install 0.4.2 over the current KAI (Alpha) app; do not uninstall. Package identity, signing identity, encrypted credentials, conversations, memory and tasks are preserved.

## Evidence and limits

The restored controller, chunk session and turn logic are compared with 0.4.0; the controller differs only by an optional test-engine injection parameter (production uses the platform default). Connections code is compared with 0.4.1. The final validation records JVM, lint, signing, packaging and Android instrumentation results.

The test-only Android TTS engine emits deterministic silent PCM. It checks pitch/rate/voice requests, one-phrase submission, completion, failure, interruption and stale-callback handling through the Android binder/playback path. It does not measure real speech sound or physical-phone latency. Encrypted-state restart tests seed the retired preset with the 0.4.1 marker and preserve credentials/settings/history.

The exact device-specific cause is not confirmed. The added queue and very small first phrases were plausible regression sources, and the new preset depended on a matching installed offline voice. Reverting them removes those new dependencies without claiming the user's phone was reproduced. The original Azelma model still buffers a phrase after CPU inference; its original delay may remain. No live provider or connected account action is needed for these checks. Actual phone preview, conversation, interruption and audio-output testing remain necessary before claiming a smooth or faster voice experience.
