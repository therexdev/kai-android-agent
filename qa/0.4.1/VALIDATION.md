# KAI 0.4.1-alpha validation

This build defaults KAI to a responsive installed offline Android voice with light character pitch, retains the original desktop voice, overlaps one following speech phrase with playback, shortens turn delays, and resolves readable account identities without blocking chat on profile reads.

The universal APK is **380,603,373 bytes**, version code **8**, application ID `com.kai.agent.preview`, for Android 9/API 28 and later. Install over KAI (Alpha); do not uninstall first.

- APK SHA-256: `294024e4d5c12ba49ebfde68fee9857003ab41a8184c9c18fc3dadbacef14212`
- Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`

## Build checks

| Check | Result |
| --- | --- |
| JVM tests | 230 passed; no failures, errors or skips |
| Android lint | No errors; 5 warnings |
| Main and instrumentation APK assembly | Passed with cached dependencies |
| Signing continuity | Original alpha certificate verified |
| Zip alignment | 16 KB zip alignment check passed; not a 16 KB-page device test |
| Native and desktop voice continuity | 40 files byte-identical to 0.4.0 |
| Native packaging | All four ABIs retained; native libraries compressed/extracted |
| Installation | In-place `pm install -r` succeeded on the API 28 emulator |
| Compiled inputs | Source, resources and build settings matched frozen hashes |

## Android checks

The final app APK passed **12 instrumentation tests** on an API 28 x86_64 software emulator:

- Three tests exercise the real Android TTS binder and playback queue with a deterministic test-only PCM engine: exact transcript completion and pitch/rate/voice selection, a failure in the queued next phrase, and stopping with a subsequent replacement reply. This is not real-voice quality or physical-phone latency evidence. The fixture engine is only in the separate instrumentation APK, not in the installable app.
- One SQLCipher/Keystore restart test verifies one-time migration from the previous desktop default, retention of credentials/settings/custom character voice/conversation, and persistence when Azelma is explicitly selected again.
- Seven real ViewModel/store/mock-inference lifecycle tests cover cancelled streams, stop and next turn, interrupted versus heard transcript context, foreground microphone state, persisted speech controls, and account profile lookups deliberately held while a reply starts. The last test then renders the authenticated Discord fixture name without the generated alias.
- One Compose UI test verifies visible Character/Start talking/Voice/Chat controls, Fast and original voice choices, and existing task/routine/project controls. UI screenshots use synthetic state.

The unit suite additionally covers offline voice selection, bounded lookahead/Unicode/transcript ordering, profile allowlists and successful response envelopes, exact account/owner binding, denied/ambiguous/masked identities, credential cancellation, cache expiry, failure cooldown and cancellation without cached failure. Existing approval, storage, memory, task, inference and connection regressions also passed.

Reports and evidence are under `qa/0.4.1`. Prior-release evidence is retained separately. Initial platform tests could not discover the synthetic TTS engine because its test-only service intent filter lacked DEFAULT; that registration was corrected and all three platform tests were rerun. The nine migration/lifecycle/UI checks used the same byte-identical production APK before this fixture-only correction. An initial emulator installation also paused when discarded temporary test disks filled /tmp; the old temporary overlays were cleared, the VM resumed, and installation then succeeded. No live owner API keys or connected accounts were used. No Gmail message, post or other external action was sent. The native Azelma model was not resynthesized in this release; its assets and runtime are unchanged.

## Remaining phone checks

Preview Fast on the actual phone, check an ordinary two-way conversation, then check an interruption and a Bluetooth/output change. Installed TTS voice quality, recognition endpointing, echo, provider response time and device performance vary. The new default is a different timbre from Azelma; original Azelma remains selectable. This is still a foreground recognizer plus streaming text/TTS design, not a dedicated full-duplex speech-to-speech backend.

Refresh Connections on the actual account setup. Profile names require the relevant provider scopes and Composio Proxy execute permission. If a profile is unavailable the account remains usable with a neutral label, rather than a generated alias or invented identity. See `docs/RESPONSIVE-VOICE-AND-ACCOUNT-NAMES.md` for supported profile reads and limitations.
