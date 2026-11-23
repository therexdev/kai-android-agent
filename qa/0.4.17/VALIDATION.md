# kai Android 0.4.17 validation

Version code 24. ARM64, Android 9+, original signing identity retained.

- 338 JVM tests passed, zero failures/errors/skips. New checks cover malformed/truncated/multiple action responses, incomplete wrappers, missing fields, bounded recovery, retained schemas/UNKNOWN receipts, output limits, content filtering and actual-versus-advertised context metadata.
- Nine Android checks passed: large-context discovery; document flow; malformed responses before discovery/after schema inspection/after approved submission; bounded failure without approval or execution; populated-memory document request; memory under both providers; legacy pending-card persistence; real WorkManager task recovery. Two voice checks (cancelled-stream ownership and no replacement after speech began) were blocked by emulator connection loss.
- The document controller fixture preserves six earlier operations (including UNKNOWN), eight connected accounts and eight memories. Recovery produces one reviewed external write, never a duplicate. Rejected fragments are absent from subsequent prompts.
- Lint: zero errors, 10 warnings. Universal and ARM64 APK payloads match outside omitted ABI libraries. Original certificate and 16 KiB alignment checked.
- The first device run lost its emulator connection after nine completed checks. An isolated retry lost its emulator during installation. The two voice checks are blocked, not passed; physical-phone retesting remains required.
- A transient initial incremental APK packaging failure was resolved by rerunning packaging. The final build and completed checks passed; the two blocked voice checks are listed separately. No production validation was disabled.

## Behavior and limits

Only completed model responses rejected by the protocol, or output-length stops, can trigger at most two corrective inference attempts per turn/worker invocation. No network/service operation is retried by this mechanism, and partial action fields are never completed locally or executed. Normal schema, ownership, exact approval, durable submission and UNKNOWN guards remain in effect. Speech already started, cancellation, network failures and filtering do not enter format recovery.

Test API shows the catalog context limit; completion status separately shows an active allocation only if reported by the API. Core 0.7.32 and Remote Access 0.3.4 are already released. Optional desktop limits are under Model library → your model → Automatic context. No further desktop release is part of this patch, and no manual phone override is needed.

Provider/model and service replies are simulated. The screenshot does not expose the actual malformed model output or prove the active context allocation. This patch validates recovery behavior; the owner's exact live model and Google account still require a phone retest. No personal phone data or live Google document was changed. Android voice checks use controlled speech callbacks and do not establish physical audio quality.
