# KAI Android 0.5.6 validation

This phase corrects shared progress, capability admission, source working-set and service-receipt boundaries exposed by the supplied 0.5.5 test and adjacent-caller review. It advances the bounded implementations in KAI-029/031/034; research coverage, semantic assurance and other roadmap work remain unfinished.

## Automated evidence

- All **665 JVM tests passed**, with zero failures, errors or skips. Twenty new cases cover durable normalized progress, alternating cached reads, changing budget text, independent capability admission, invalid native registrations, archive/file/discovery loops, pagination, duplicate findings, completion reserves, explicit continuation, canonical context retention, generic-topic retrieval and HTML diagnostic filtering. Shared controller fixtures exercise both wire protocols and every run origin; success/failure/UNKNOWN service receipt tests use committed-status fixtures. These are not live executions of the Android adapters.
- The full existing approval, document read-back, inference allowance, source provenance and privacy regression suites still pass. Shared settlement runs before new-model/no-progress admission. Failed service receipts cannot reset the streak, duplicate callbacks do not count again, and UNKNOWN/unsubmitted outcomes cannot authorize or advance an effect.
- Android lint passed with **zero errors, 23 warnings and 1 hint**. Six warnings concern existing code/platform support and seventeen are dependency-version notices; the hint is an existing KTX suggestion. Dependencies were not changed. See `LINT.json`.
- JVM tests, lint, ARM64 release assembly and instrumentation APK assembly passed together in `RELEASE_GATE.log`. `SOURCE_HASHES.json` freezes 729 source/build inputs; no drift remained before signing.
- `APK_METADATA.json` and `APK_SIGNING.txt` record version 0.5.6/code 46, preview package, original signing certificate, ARM64 libraries, 16 KB ZIP alignment and final checksum.

## Actual-report replay

A private offline replay uses the retained 0.5.5 trace and compiled 0.5.6 classes. Its 17 retained runtime outcomes close collection under the shared progress rule. Re-recording 24 captured attributions into a fresh in-memory fixture yields 13 distinct source/claim pairs, all retained by canonical projection without the duplicate legacy index. The captured HTTP 502 remains classified while its HTML body is omitted. `PRIVATE_REPLAY.json` records assertions and tested source hashes; raw report contents, account identifiers and network details remain excluded.

This is a retained-sequence structural replay, not a new complete model execution. Earlier events were dropped from the report. The fixture remaps source IDs and does not import or rewrite trusted production state. Existing stored findings remain intact on upgrade. No live web, paid provider or connected-service calls were made by validation. The upstream 502 cause is not established or repaired here.

## Shared contract and limits

Both chat and saved-task/routine adapters now persist outcome tracking through the execution record. Restart/model changes cannot reset collection closure. Explicit continuation resets only the stalled streak and retains closure and evidence; the existing budget extension rules still apply. Actual accepted/successful service receipts advance progress once, failures consume it, and exact intent bindings remain authoritative. A stalled run saves a partial outcome instead of fabricating completion.

The progress controller recognizes repeated/failed outcomes, not semantic novelty. Connected-service observations remain untrusted; unique successful reads are not a semantic polling/coverage verifier. Closing collection and reserving allowance does not guarantee a draft, successful review or delivery. Per-step saved-task limits remain separate. Projection explicitly reports omitted findings and preserves originals for paging; exact quotes establish attribution only. The update does not claim full source coverage, factual correctness or a universal service adapter.

## Device and production gates

`connectedDebugAndroidTest` was attempted after the final build and stopped with **No connected devices**; zero instrumentation tests executed. Its APK compiled. The encrypted-run device fixture now checks persisted progress/collection closure after reopen and privacy invalidation, alongside existing source-quote assertions. JVM serialization/restart fixtures cannot substitute for that device test.

Physical 0.5.6 encrypted restart, lifecycle, native research, saved-task approval, document/email completion and voice checks remain open. The old report is not live proof of the new release. SQLCipher schema 3 and the installed signing identity are retained. No production service change or paid native-route qualification was made. Follow `RELEASE.md` for the bounded phone check.
