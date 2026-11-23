# kai Android 0.4.15 validation

Release: version code 22, 18 November 2026. ARM64 APK, Android 9+, original update signing identity.

## Results

- 317 JVM tests passed, zero failures/errors/skips.
- The populated-context regression uses eight accounts and six earlier operations, including UNKNOWN. It reproduces the earlier overflow after a web detour and checks that the document-specific path keeps the entire generated ledger, exact user request, latest schema and at least 512 response tokens within a 4096-token window, including the 512-token template reserve.
- Optional memory fallback preserves status, mode, facts committed this turn and identity clarifications; source data is unchanged. Runtime history compacts before optional memory.
- Supplied-text document requests are redirected away from web research before any web query. Research and personal-reference requests remain available; repeated detours stop explicitly.
- Five Android controller/store tests passed: the full document flow with a forced web detour, eight accounts, six prior operations, approval gating, one simulated write and post-action link reply; populated-memory initial document request; Koinos and OpenAI memory conversations; legacy pending-card conversion/persistence.
- Android lint: zero errors, 10 warnings.
- Universal and ARM64 builds use matching shared payload bytes. Original signing certificate, version, launcher label and 16 KiB zip alignment were checked.
- Android App info visually confirms the kai label and robot-head icon; the main screen renders without the early-access badge.
- Launcher uses the transparent KAI robot head with mask insets and a matching monochrome robot icon. App label is kai; alpha version suffix and early-access header badge are removed.

## Limits

Provider and service responses are simulated. No live KAI Core inference, Google account or external document creation was tested. The fixture uses Composio's documented title/text create parameters; production still discovers the actual schema/version and validates it before approval and execution.

This does not enlarge the served model context. Very large required schemas, project material, current input or results can still exceed it. Saved memory and actual action outcomes are not inferred from omitted text; ambiguous writes are not replayed. No personal phone data was modified. Emulator checks do not validate physical-phone microphone or audio behavior.
