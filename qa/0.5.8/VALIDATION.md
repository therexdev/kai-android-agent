# KAI Android 0.5.8 validation

This candidate corrects the shared action-preparation and reply contract exposed by the latest report. It does not claim that the live model has completed the original request on 0.5.8.

## Measured checks

- **661 JVM tests passed**, zero failures, errors or skips. Six new contract tests account for the increase from 655. A passing test count is not a completion or model-quality score. Optional live fixtures made no live calls.
- The new tests reject ordinary readiness/confirmation and unsupported completion prose for outstanding actions in both protocols. A subsequent complete proposal reaches exact review and cannot enter RUNNING without approval. Specific missing-input questions and explicit partial/blocked outcomes remain valid. Ordinary/accepted/answer-only responses bypass this check when the caller has no outstanding-action requirement.
- The existing two-consecutive/six-total correction limits are unchanged. New cases verify repeated readiness does not reset consecutive failures; native output-limit corrections retain chunk limits, resource reuse and UNKNOWN instructions. Intermediate saved research steps may finish with text, while the final step owes the overall goal.
- Two scripted document-only/document-plus-email component scenarios now inject premature confirmation before proposal. They cover 24 protocol/origin/resume combinations, zero saved findings, retained text after compaction, exact approval, one document write/read-back, actual returned-link reuse and duplicate-write rejection. Origin labels exercise shared components; they do not instantiate the Android adapters. Model replies and service responses are fixtures.
- The first full gate caught excessive prompt growth in the populated 4,096-token document scenario. Instructions were shortened without changing its assertion, ledger or budget. Final measured output: `Populated document flow: prompt=3063, output=521`. The existing requirement to preserve at least 512 output tokens passes.
- Android lint passed with zero errors, 25 warnings and 1 hint. Existing dependency/platform/code warnings remain; dependency declarations are unchanged. Details are in `LINT.json`.
- JVM tests, lint, release assembly and instrumentation APK assembly passed in `RELEASE_GATE.log`. `SOURCE_HASHES.json` freezes 731 source/build inputs; no source drift occurred before signing. Package, version 0.5.8/code 48, ARM64 libraries, 16 KB ZIP alignment and original signer were verified in `APK_METADATA.json`/`APK_SIGNING.txt`.

## Private report replay

Compiled 0.5.8 classes reject the exact latest 0.5.7 final response in both native and JSON modes when the original goal remains unprepared. The existing correction retains preparation, missing-input and UNKNOWN guidance. The reconstructed registry still contains the document create function. The earlier successful 0.5.2 document arguments pass the current schema and parse unchanged in both protocols. No old proposal is submitted.

`PRIVATE_REPLAY.json` records the assertions and tested source hashes. Raw reports, private identifiers, recipient details and signing material are excluded. This is offline replay of exported diagnostic data, not a production-state import or a new provider/model execution. The precise reason the model chose different behavior remains unproved.

## Device and live gates

The instrumentation APK compiles new chat and saved-task cases: premature confirmation must be corrected into a concrete approval card; no write may precede approval; saved UNKNOWN effects cannot replay. The document fixtures now seed current managed-account settings. Chat inference/account clients are injected into the existing ViewModel constructor and routed to the local test server; production defaults are unchanged. These checks are **compiled, not executed**.

`connectedDebugAndroidTest` failed at **No connected devices**: **zero device tests ran**. Physical-phone lifecycle/restart, actual adapter execution, live GPT OSS choices, real Docs content/read-back and email results remain open. This workspace has no authenticated live inference session. No paid model call, real email/Docs write or production deployment was made.

## Limits and compatibility

The existing goal inference and explicit outcome labels still have semantic limits. A model can mislabel unnecessary confirmation as `needs_input` or incorrectly assert a blocker; runtime validation cannot prove that classification true. Ordinary plain-text questions/partial results during unfinished external work must now use the explicit question/partial/blocked form through bounded correction. Explicit answer-only continuation and accepted work preserve their existing behavior. Correction exhaustion stops with progress retained.

Source retention from 0.5.7, exact approval bindings, document body checks, durable submission receipts, UNKNOWN non-replay, SQLCipher schema 3 and all run/inference allowances remain unchanged. Follow `RELEASE.md`, starting with the previously successful document-only request on the same model, before declaring this resolved in use.
