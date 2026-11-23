# KAI 0.4.2-alpha recovery validation

This recovery reverses the 0.4.1 voice preset, queue, phrase-splitting, pitch/rate and turn-pacing changes after the user reported worse speech and a broken KAI voice. It restores 0.4.0 voice behavior while retaining 0.4.1 account-identity fixes. It is not a measured latency or naturalness improvement on a phone.

The universal APK is **380,590,361 bytes**, version code **9**, application ID `com.kai.agent.preview`, for Android 9/API 28 and later. Install over KAI (Alpha); do not uninstall first.

- APK SHA-256: `d6418dee93fdd0a353134c7534175bda8d78a4f18a04e12487a529174dd2e511`
- Signing certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`

## Build checks

| Check | Result |
| --- | --- |
| JVM tests | 229 passed; no failures, errors or skips |
| Android lint | No errors; 5 existing warnings |
| Main and instrumentation APK assembly | Passed with cached dependencies |
| Signing continuity | Original alpha certificate verified |
| Zip alignment | 16 KB zip alignment check passed; not a 16 KB-page device test |
| Native and desktop voice continuity | 40 files byte-identical to 0.4.1 |
| Native packaging | All four ABIs retained; native libraries compressed/extracted |
| Installation | 0.4.1 installed, then 0.4.2 installed in place with `pm install -r` on API 28 |
| Compiled inputs | Source, resources and build settings matched frozen hashes |
| Source comparison | Voice session and turn logic equal 0.4.0; controller differs only in optional test-engine injection |
| Connections | All nine connection implementation files byte-identical to 0.4.1 |
| Production DEX | Experimental policy and test-only TTS engine absent |

## Android checks

The final app APK passed **13 instrumentation tests** on an API 28 x86_64 software emulator:

- Four tests exercise the real Android TTS binder/playback using a deterministic test-only silent-PCM engine: ordinary pitch and saved rate, transcript completion, synthesis failure, interruption/replacement, an intact long first sentence, and no extra speed multiplier. This is not real-voice sound or physical-phone latency evidence. The synthetic engine is in the separate instrumentation APK only.
- One SQLCipher/Keystore restart test seeds the retired Fast preset with the 0.4.1 migration marker already true. It verifies restoration to Azelma, preserved encrypted credentials/settings/conversations/other-character selection, and a subsequent explicit Android voice selection surviving restart. It simulates 0.4.1 persisted state; it is separate from the APK update installation check.
- Seven real ViewModel/store/mock-inference tests cover cancelled streams, stop and next turn, interrupted versus heard transcript context, foreground microphone state, persisted speech controls, and optional account profile lookup held while an answer starts. The profile test then renders the authenticated Discord fixture identity.
- One Compose UI test checks Character/Start talking/Voice/Chat entry points, original Azelma visible and selected, the retired Fast option absent, device voice selection, and existing task/routine/project controls. Screenshots use synthetic state.

Unit tests additionally cover retiring the preset regardless of the prior marker, preserving explicit choices, import normalization, single-phrase submission, longer natural phrase boundaries, Unicode/transcript handling, and the unchanged connection/approval/storage/memory/task/inference regression suite.

Reports and evidence are under `qa/0.4.2`; historical release evidence remains separately labeled. The first run of a new preview test incorrectly expected a terminal sentence to remain one clip before the controller marks input complete. The test was corrected to verify an intact long sentence with a following sentence, then all four platform checks were rerun. Production APK bytes were unchanged; the initial failed test log is retained. The execution session was later interrupted before the UI test returned a result. UI and final playback checks ran in a fresh API 28 emulator using that same APK. Migration/lifecycle result logs are retained, but their optional JSON/screenshots could not be retrieved from the interrupted session. No live owner API credentials or connected accounts were used. No email, post or other external write was sent. Azelma native synthesis was not rerun; its native code, model and processing are unchanged. This environment has no access to the user's physical phone.

## Remaining phone checks and limits

Preview the selected voice on the actual phone, check a two-way conversation, an interruption and an audio-output change. Native Azelma still buffers a phrase after CPU inference, so its original delay may remain. Recognition endpointing, selected inference provider, networking and installed Android engines also affect response time. The recovery deliberately removes the 0.4.1 experiments rather than making another unmeasured speed claim. This remains foreground recognition plus streaming text/TTS, not a dedicated full-duplex speech-to-speech backend.

Connections retain the 0.4.1 profile-name behavior and neutral fallback for unavailable identities. Live account scopes remain unverified. See `docs/VOICE-RECOVERY.md` and the connected-identity section of `docs/RESPONSIVE-VOICE-AND-ACCOUNT-NAMES.md`.
