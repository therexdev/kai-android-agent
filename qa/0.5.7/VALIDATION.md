# KAI Android 0.5.7 validation

This update removes the failed research prerequisites and heuristic stop policies identified by the complete 0.5.6 report. It preserves source storage, approvals and resource ceilings. It does not claim live completion of the original request.

## Measured checks

- **655 JVM tests passed**, zero failures, errors or skips. The count is lower than 0.5.6 because tests asserting the retired policy were removed/replaced. It is not a quality or completion score. Optional live-fixture tests did not make live requests.
- Two new scripted component scenarios cover document-only and document-plus-email. Each crosses three run origins, two protocols and fresh/resumed states: 24 combinations. They exercise real parsing, context projection, source serialization/paging, exact approval, the direct client's simulated transport, receipt transitions, document read-back and duplicate-write rejection. Sources survive catalog compaction with zero saved findings. Exactly one document write and one read-back occur; the email variant adds one reviewed send containing the returned link.
- The component scenarios compose shared APIs directly. An origin enum does not instantiate `KaiViewModel` or `TaskAgentEngine`; scripted model outputs cannot prove that a live model chooses the intended next step. Read-only fixture actions also cross explicit approval for test consistency, while the real trusted-read adapter can run them without a write review.
- Additional cases cover a 9,000-byte source projection with Latin/CJK text, every indexed source across offset pages, network-exhausted cached reads after restart, and old closed/stalled progress records resuming without new allowance. Existing privacy, exact account/schema approval, uncertain effects, document/body guards and durable budget suites pass.
- A real HTTP-interceptor transport test serializes managed native history with an empty tool list and asserts protocol 2 plus the bound provider replay token. This addresses the report's answer-only 400 without calling a provider.
- Android lint passed with **zero errors, 25 warnings and 1 hint**. Nineteen warnings are dependency-version notices and six concern existing code/platform support; the hint is an existing KTX suggestion. Two additional Espresso-version notices appeared versus the prior lint result; dependency declarations are unchanged.
- JVM tests, lint, release assembly and instrumentation APK assembly passed together in `RELEASE_GATE.log`. `SOURCE_HASHES.json` freezes 730 source/build inputs. No source drift occurred before signing.
- Package, version 0.5.7/code 47, ARM64 libraries, 16 KB ZIP alignment and original signing certificate were checked. The final APK metadata/checksum are in `APK_METADATA.json`; signing verification is in `APK_SIGNING.txt`.

## Private report replay

The supplied 0.5.6 trace has 207 retained events and zero dropped events. Its rejected legacy finding batch leaves zero notes. Against compiled 0.5.7 classes, serialization and canonical projection now retain the complete 5,644-character directory excerpt, and all sources are recovered by exact ID and offset pages. `PRIVATE_REPLAY.json` records these assertions and tested source hashes. Raw report text, account/recipient identifiers and private trace data are excluded.

The diagnostic export's escaped receipt URLs are reconstructed for an offline fixture. This is not a trusted-state import, migration or new model run. The synthetic scenario supplies new proposals; the replay does not infer that the old model would automatically complete the document. No paid provider, real email or live Google Docs action was executed. The earlier upstream 502 remains a separate unresolved availability observation.

## Compatibility and remaining gates

Old notes and 0.5.6 progress data retain strict decoding but no longer control drafting or admission. The legacy JSON finding handler remains for compatibility; new prompts and the native tool registry no longer advertise it. Historical records are not rewritten. SQLCipher schema 3, installed identity, exact approval bindings and UNKNOWN non-replay remain unchanged. Existing budgets still stop unlimited work; semantic convergence and complete claim/source assurance remain unfinished.

`connectedDebugAndroidTest` was attempted and failed at **No connected devices**; **zero device tests ran**. The instrumentation APK compiled, including the encrypted restart/privacy fixture retaining old progress data. Physical encrypted restart, chat and saved-task adapters, lifecycle/voice, live native decisions and document/email completion remain open. Install the signed test APK and follow the paired original-request checks in `RELEASE.md` before declaring this regression resolved in use. No server deployment or paid route qualification is included.
