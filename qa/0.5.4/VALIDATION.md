# KAI Android 0.5.4 validation

This phase advances KAI-016/018 while retaining their partial status. It also fixes two field regressions from the supplied 0.5.3 Android 13 report: Gmail accepted a schema-valid but empty message for review, and the document verifier treated a rendered thematic rule as different body text.

## Automated evidence

- All 619 JVM tests passed with zero failures, errors or skips. The 40 additional cases cover durable admission, summary/retry accounting, credit estimates, missing/invalid usage, cancellation/restart, bounded continuation/finalization, malformed persistence, privacy invalidation, effect settlement before exhausted model admission, email correction and horizontal-rule matching. `JVM_FULL_RUN.json` retains class counts.
- The compiled code passed a private replay of the supplied report: the newest document read matches the approved body, the empty Gmail send is rejected, and the literal placeholder draft read is rejected. The report contents, account identifiers, document URL and private runner are excluded from source. No live supplier or connected-service calls were made. See `PRIVATE_REPLAY.json`.
- Android lint passed with zero errors, 20 warnings and one hint. Six warnings concern existing code/platform support; fourteen are dependency-version notices. The hint is an existing dependency KTX suggestion. Dependencies were not changed. See `LINT.json`.
- The combined full JVM/lint/ARM64 release/instrumentation-APK assembly command is retained in `RELEASE_GATE.log`. `INITIAL_GATE.log` records the earlier successful full gate before restricting placeholder validation to the observed draft-read operation. No failing assertion was removed. `SOURCE_HASHES.json` freezes 723 source/build inputs.
- `APK_METADATA.json` and `APK_SIGNING.txt` record package/version, original signer, ARM64-only native libraries, 16 KB ZIP alignment and the final checksum. Source hashes are checked again before signing.

## Accounting boundaries

Admission precedes transport and shares request/input/output/active-model-time/estimated-credit limits across chat and saved-task planning, summaries, retries, fallback and finalization. Invalid or missing usage retains token/cost reservations. Process-death recovery retains the full outstanding model-time reservation. A normal interruption records elapsed model time. Only explicit budget continuation extends the allowance; automatic workers and approval resumes cannot refill it. Already approved saved effects settle without a new model admission, and UNKNOWN still pauses for reconciliation.

Credit values are catalog estimates, not settled charges or a guaranteed monetary cap. Price timestamps are not refreshed merely by selecting a cached model. Unknown prices remain explicit; an enabled credit allowance requires a current matching price. Existing wallet and free/purchased-credit rules remain. Background memory extraction, full tool/network wall-clock accounting, authoritative gateway settlement and wider roadmap work remain unfinished.

## Field regression boundaries

Gmail cross-field checks reject proposals before review and again before direct dispatch. They do not fill a subject/body, change recipients, reuse an approval for changed input, or send a replacement automatically. A corrected message needs fresh exact approval. The placeholder check covers the reported GMAIL_GET_DRAFT operation, not guessed contracts for other operations.

The observed 50-underscore rule is accepted only at the corresponding Markdown thematic-break node. Other punctuation and missing/extra body text remain significant. Same-document/account/run attribution, later successful reads, newest-read precedence and truncated/unsupported evidence rules remain. Text matching proves neither source truth nor email delivery.

## Device and production gates

The supplied report is actual 0.5.3 phone evidence: document creation/read succeeded and email sending failed. Private replay is an offline regression check, not live 0.5.4 execution. Instrumentation APK assembly passed; connected device execution is recorded separately in `DEVICE_STATUS.json`. No phone is connected here, so physical 0.5.4 encrypted restart, lifecycle, saved approval settlement, voice and live corrected-email checks remain open.

SQLCipher schema 3, package and signing identity are retained. No production service/configuration update or paid native-route qualification probe is needed. Install over the previous preview and follow `docs/RUN-ALLOWANCES.md` for bounded phone checks.
