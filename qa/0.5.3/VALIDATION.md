# KAI Android 0.5.3 validation

This update advances the completion/artifact work in KAI-006 and KAI-039. It does not complete the research/source-assurance roadmap or the shared credit/deadline ledger.

## Measured automated results

- All 579 JVM tests passed in one full run, with zero failures, errors or skips. This includes 22 additional cases covering body comparison, receipt/account/run attribution, recent conflicting reads, unsupported/truncated data, persistence reconstruction and idempotent verification journaling. See `JVM_FULL_RUN.json`.
- The user-provided 0.5.2 report passed a private local replay: the latest successful plain-text read matches the approved Markdown document body. The final compiled verifier also passed this replay. No report body, account identifiers or document URL is checked into the repository. No new supplier or connected-service call was made. See `PRIVATE_REPLAY.json`.
- Android lint passed with zero errors and 16 warnings: six existing code/platform warnings and ten dependency-update notices. Dependencies were not changed. See `LINT.json`.
- The combined JVM/lint/ARM64 release/instrumentation-APK assembly command and signed artifact checks are recorded in `RELEASE_GATE.log`, `APK_METADATA.json` and `APK_SIGNING.txt`. `SOURCE_HASHES.json` freezes 716 source/build inputs for comparison with the packaged artifact.

The first full gate found one existing native-harness fixture that had no read-only flag, same-run identity, read dispatch/receipt or full response envelope. Its completion assertion was retained; the fixture now supplies an actual post-write receipt and the supported structured body shape. The next full suite passed. No assertion was disabled. `INITIAL_GATE.log` retains that failed attempt.

## Behavior and evidence limits

Supported Google Docs creation/update text is compared with a separate read from the same document, account, owner, connection generation and execution after write acknowledgement. Markdown formatting is projected into visible prose; list markers may differ only at actual list positions. Whole-document writes require complete normalized text equality. Supported insertion actions require the inserted passage to appear. Literal punctuation, case and numeric values are preserved.

Newest reads supersede older evidence. A wrong-scope, missing, failed, truncated or unsupported read stays unverified; a different body produces a partial result without another write. Arbitrary metadata and request echoes cannot supply body evidence. Chat and saved tasks share the evaluator and retain the write/read association in the run journal. The accepted receipt, exact approval and UNKNOWN protections are unchanged. Verification cannot authorize a mutation or prove source truth, formatting, hyperlink targets, access rights or delivery.

Evidence is recomputed from retained encrypted task records. JSON persistence reconstruction and loss of readback data are covered by JVM tests. SQLCipher schema 3, signing identity and package are unchanged. This does not reclassify a previously finished 0.5.2 run or silently rerun its actions.

## Live and physical boundaries

The user confirmed the 0.5.2 research-to-document test on a physical Android 13 phone after the gateway's GPT OSS route was qualified and activated. That is prior-release live evidence, not a new 0.5.3 phone test. The local report replay uses already-recorded results and spends no inference credits.

No Android device is connected to this workspace. Instrumentation APK compilation is distinct from running it. Device execution status is recorded in `DEVICE_STATUS.json`; physical 0.5.3 chat/saved-task, restart, lifecycle and voice checks remain unperformed. No production server update or new paid qualification probe is needed for this APK.
