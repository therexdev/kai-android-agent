# KAI Android 0.5.4 test update

Package `com.kai.agent.preview`, version code 44, ARM64. Install over 0.5.3 using the unchanged alpha signing identity. SQLCipher schema 3 is retained.

SHA-256: `9b8a3520fe2a9906a46ba17ba3220aaa41b2af485d4ea8cc6187b05de392115b`
Size: 125718475 bytes. Original signing certificate and 16 KB ZIP alignment verified. All 723 frozen source/build inputs match.

This release shares persisted model allowances across chat/saved-task planning, summaries, retries, fallback and finalization. It adds optional catalog-estimated credits; these are not settled charges or a guaranteed billing cap. Unknown usage is retained. Only explicit budget continuation grants more allowance, and already approved effects can settle after model exhaustion without replaying UNKNOWN writes.

It also addresses the supplied 0.5.3 field report: empty Gmail sends and literal placeholder draft reads are rejected before review/dispatch, and the Docs thematic-rule rendering is recognized only at the approved Markdown position. Corrected email content requires a fresh exact approval. The old failed email is not automatically retried.

The final full 619-test JVM/lint/build gate and offline compiled report replay passed. Physical 0.5.4 lifecycle, encrypted restart, voice and corrected document/email execution remain separate gates. Start a small new test and inspect recipient, subject/body and the real document URL before approval. See `VALIDATION.md`, `DEVICE_STATUS.json` and `docs/RUN-ALLOWANCES.md`.

No gateway deployment, account configuration change or new paid route probe is required. Background memory accounting, authoritative credit settlement, complete wall-clock/tool accounting and source assurance remain unfinished.
