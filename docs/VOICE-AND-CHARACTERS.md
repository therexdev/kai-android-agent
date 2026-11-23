# Voice and characters in 0.3.0

For the bundled desktop voice added in 0.3.1, see [Desktop voice](DESKTOP-VOICE.md). Its default and playback behavior supersede the Android-TTS-only descriptions below.

The phone remains the agent runtime. Both configured text inference providers use the same speech pipeline, stored preferences, memories and connections. The character artwork is packaged in the APK; it does not require a running desktop app, website account, remote asset server or desktop plugin.

## Character cast

| Character | Appearance | Personality |
|---|---|---|
| KAI | Original white and blue desktop robot, cape and K emblem | Warm, capable, playful everyday companion |
| Nova | Lavender alien in an indigo suit | Curious, imaginative, encouraging |
| Spark | Orange fuzzy horned monster | Quick, witty, practical |
| Sage | Green woodland owl | Calm, patient, clear |

Existing character IDs and saved voice choices are preserved. The selected name, appearance and personality are included in model context, so each companion can introduce itself consistently while retaining the same phone-owned permission and memory rules. Each has a personality-specific voice preview; voice quality and available choices come from installed Android speech engines. This version does not bundle premium synthetic voices or provide custom character authoring.

KAI reuses the actual MIT-licensed desktop SVG rig, CSS animation and texture from `therexdev/kai-plugin-assistant`, pinned in its provenance file. The additional characters use generated transparent textures and authored SVG articulation, with prompts and generation provenance in their asset folders. Native Compose controls render the surrounding app. One visible local WebView renders the active character; small previews use static posters.

The animation document receives only fixed state enums and booleans. It has no JavaScript bridge, local file/content access, cookies or data storage, remote navigation, model text, account data or network assets. A strict request allowlist serves only the bundled document and its texture. Character changes replace the entire view; hidden screens and background lifecycle suspend or dispose animation. Reduced motion disables loops and transitions. Native accessibility descriptions describe the character and current state, and a native tap action triggers a greeting with optional haptics.

The native WebView explicitly fills its measured parent, and the document fills that viewport. This avoids the intrinsic-height behavior of older Android WebViews, where a wrap-content view can collapse percentage and viewport-unit SVG heights to zero. A native poster remains visible until the attached view has nonzero SVG geometry and reports visual readiness. The desktop CSS selector groups are expanded for older Chromium versions without changing the bundled artwork. Rendering checks inspect both SVG geometry and actual pixels inside the character's screen bounds; a loaded document alone is not treated as visible artwork.

Idle, listening, thinking, speaking, working, review, success and recoverable error have distinct reactions. Success requires an observed transition of a real task to completed; a model's prose is not proof of success. Mouth movement uses short TTS-start/range cues and closes during playback gaps. These cues are playback timing, not measured audio amplitude or phoneme-accurate lip synchronization. The speaking body pose remains stable through phrase gaps.

## Speech pipeline

1. An explicitly enabled foreground microphone session produces partial/final recognition text.
2. Phone-side turn accumulation accepts fragments and pauses using Natural, Patient or Quick pacing. Provider-specific endpoint timing still varies.
3. The selected inference API streams text. Only a strict canonical reply object can feed speech before the response completes. Tool/action JSON, nested lookalikes, incomplete escapes, fences and malformed payloads never enter the speech queue.
4. A phrase queue retains exact UTF-16 offsets, prefers sentence boundaries, bounds chunk size and sends at most one chunk to Android TTS. Complete-response validation still gates every tool proposal. A stream that changes from reply format to action format is cancelled.
5. TTS range-start/completion events advance a conservative heard cursor. A range start never claims its end was heard. Each playback attempt has unique generation IDs, so delayed callbacks cannot affect the next attempt.

The app persists an initially unheard assistant draft before starting streaming audio. Completed or interrupted reply text is saved to the same message identity. A reply stays marked interrupted until playback actually finishes; context uses only the confirmed heard prefix for interrupted speech. Cancellation flushes queued TTS and finishes old draft persistence before the next user message is appended. Temporary-chat drafts remain temporary, and deleted conversations are never recreated by late callbacks.

Automatic barge-in is best effort: accepted user partials stop speech without destroying those words, and recognition errors retain a usable partial rather than discarding the correction. A transcript echo heuristic removes clear playback echoes, including some mixed echo/user phrases. It is not acoustic echo cancellation. **Your turn** explicitly cancels the current response while preserving the microphone session. The microphone button ends voice. Audio focus loss, output-route changes, fatal recognition errors and backgrounding stop the affected session and update the microphone UI.

## Preferences and privacy

- Pacing: Natural, Patient or Quick; stored on the phone.
- Speaking speed: 0.5–2.0×.
- Language: device default or a validated BCP 47 tag such as `en-US`, `es` or `fr-FR`.
- Local speech only: require on-device recognition and a downloaded TTS voice; unavailable capability fails closed with text fallback. This setting does not disable network inference or connected services.
- Reduced motion and optional touch feedback: independent of inference provider and character.

## Validation boundary

See `VALIDATION.md` and `qa/0.3.0/` for executed checks. Local mock services and controlled speech callbacks exercise streaming, cancellation, account regressions and persistence without paid model calls or live inbox access. Emulator checks do not establish speakerphone echo suppression, Bluetooth behavior, recognizer quality, battery consumption or end-to-end voice latency on a physical phone. No always-listening wake word, background microphone, premium cloud voice engine or full-duplex realtime transport is included in this release.

Platform contracts reviewed: [TTS progress callbacks](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener), [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent), and [audio focus](https://developer.android.com/media/optimize/audio-focus).
