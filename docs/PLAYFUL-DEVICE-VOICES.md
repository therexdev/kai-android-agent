> Historical documentation. These voices were retired in 0.4.6; see LOCAL-TEST-VOICES.md.

# Playful device voices — 0.4.3

The original Azelma model runs CPU inference for each phrase before playback. It remained slow/unreliable on the user's phone after the 0.4.2 rollback. This update adds three alternatives through the existing Android TTS path, bypassing the Azelma synthesis process when selected.

| Style | Android pitch request | Intended direction |
| --- | --- | --- |
| KAI · Cute (default) | 1.35 | Gentle, cute high pitch |
| KAI · Cartoon | 1.55 | Brighter cartoon pitch |
| KAI · Squeaky | 1.80 | Extra high, squeaky pitch |

These are pitch styles of a device voice, not three new models or voice clones. Select **Agent → Voice**, tap **Preview** to compare, and tap a style to use it. **Base voice** selects an installed voice underneath the style; choose one that already works well on the phone. The same base is retained when comparing styles. A normal device voice always returns to pitch 1.0. Speech rate remains the saved user value with no multiplier. The ordinary device-voice selection policy is reused, without the retired 0.4.1 Fast policy or its forced offline requirement.

Preview uses the same short greeting for the device choices and restores the selected character voice afterward. Preview does not save a new choice. Selection and base voice are saved per character and survive process restart. Exports include the base voice; importing new character IDs retains it. Import keeps the preferences of existing characters. Missing or unavailable base choices use the existing compatible-device fallback. Local speech only still excludes network voices and refuses playback without an eligible offline voice; it never silently calls the desktop model as a fallback.

The one-time migration changes KAI's old Azelma/retired default to Cute. Explicit normal/named device choices and other characters remain as selected. The original desktop model stays available at the end of the list, labeled as potentially slow, and an explicit later selection survives restart. Existing credentials, conversation history, projects, memory, tasks and connection identity behavior are preserved.

## Deliberately unchanged

The streaming session, chunk boundaries, partial-flush delay, one-phrase submission, turn-pacing waits, native model/runtime, recognizer lifecycle, exact account/action approvals and connected-account identity implementation are retained. No additional local model, paid speech API or custom publisher credential is introduced for these styles.

## Validation limits

Android exposes pitch and rate independently, but individual engines can produce different sound or handle high pitch differently. This environment does not have the phone's Samsung/Google voices or microphone acoustics. The instrumentation fixture emits silent PCM and verifies the real Android TTS call/playback path: selected base, requested pitch and rate, reset to normal pitch, unavailable-base/local-only behavior, completion, cancellation and stale-callback handling. It cannot judge cuteness, naturalness or real-phone response time. The release validation lists executed build, UI, encrypted-state and ViewModel checks. Actual Preview comparisons on the user's phone are the next listening check; no latency benchmark is claimed.

## Android API references

- https://developer.android.com/reference/android/speech/tts/TextToSpeech#setPitch(float)
- https://developer.android.com/reference/android/speech/tts/TextToSpeech#setSpeechRate(float)
- https://developer.android.com/reference/android/speech/tts/TextToSpeech#setVoice(android.speech.tts.Voice)

The Android references were checked for this implementation; pitch 1.0 is normal and larger values raise it.
