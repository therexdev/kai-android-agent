# KAI Android 0.5.5 validation

This phase advances KAI-028/032 while retaining their partial status: saved source receipts and exact-quote findings, with a minimal citation floor for researched Google Docs bodies and final plain research answers.

## Automated evidence

- All **645 JVM tests passed**, with zero failures, errors or skips. The 26 new cases cover source/snippet/navigation separation, exact quotation and atomic batches, source identity, redirect/newer-read precedence, citation provenance, malformed persistence, paged retrieval, bounds, native/JSON tool parity, trusted cache recovery, failed persistence before model exposure, provider failure/resume, persisted correction limits, stale callbacks, forget/deletion, temporary serialization, imports and legacy archives. `JVM_FULL_RUN.json` records class counts.
- The fixtures explicitly demonstrate the limit: a located quotation is not claim entailment, and a minimal citation floor does not verify every statement. Web, provider and repository fixtures are fake responses; no paid provider/search or live connected-service call was made by this validation.
- Android lint passed with **zero errors, 23 warnings and one hint**. Six warnings concern existing code/platform support and seventeen are dependency-version notices. The hint is an existing dependency KTX suggestion. The three additional warnings compared with 0.5.4 are newly reported lifecycle dependency-version notices, not new code warnings; dependencies were not changed. See `LINT.json`.
- Full JVM tests, lint, ARM64 release assembly and instrumentation APK assembly passed together in `RELEASE_GATE.log`. `SOURCE_HASHES.json` freezes 726 source/build inputs; all hashes still matched before signing.
- `APK_METADATA.json` and `APK_SIGNING.txt` record version 0.5.5/code 45, preview package, original signer, ARM64 native libraries, 16 KB ZIP alignment and final checksum.

## Persistence and authority boundaries

Phone retrieval receipts commit before their result is exposed to the model. Findings commit atomically against source IDs and exact excerpts. Chat and saved-task adapters use the same run-owned evidence and correction controller. New sessions restore only the separate trusted receipt ledger; older archives remain historical text without manufactured attribution. Temporary runs are excluded from persistence, privacy invalidation clears research, deletion removes it, and imports cannot introduce new trusted runs. Source text remains untrusted data and conveys no action permission.

The new citation check has two persisted correction opportunities, then retains the draft/findings with a partial outcome. It does not alter approved arguments, grant action permission or replay older/UNKNOWN effects. Exact approval, document read-back and shared inference allowance regression suites still pass. Older approval resumes retain their original exact intent; the new attribution check applies to new proposals.

## Scope limits

At least one current quoted finding and its cited URL are required; this is not full claim coverage, entailment, source independence, corroboration or factual verification. Source receipts can be snippets or bounded page excerpts and are not complete-page review. The new proposal check covers explicit public research for Google Docs and final plain answers; generic service bodies and other roadmap work remain unfinished. Explicit Answer from findings does not require another tool/correction turn. See `docs/RESEARCH-EVIDENCE.md` for these limits and legacy behavior.

## Device and production gates

`connectedDebugAndroidTest` was attempted and stopped with **No connected devices**; zero instrumentation tests executed. Its APK compiled successfully. `ExecutionRunDeviceTest` now includes source/quote encryption, restart recovery and privacy clearing, but physical execution remains unverified. See `DEVICE_ATTEMPT.log` and `DEVICE_STATUS.json`.

Physical 0.5.5 encrypted restart, lifecycle, native-tool research, saved-task approval and voice checks remain open. Previous user-supplied reports are not proof of 0.5.5 behavior. SQLCipher schema 3 and the installed signing identity are retained. No production service update or paid native-route qualification is required.
