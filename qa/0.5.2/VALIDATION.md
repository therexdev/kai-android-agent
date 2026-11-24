# KAI Android 0.5.2 validation

This phase adds managed native-route qualification and private provider continuation. It does not complete the broader architecture roadmap.

## Automated checks

- All 557 JVM tests passed in one full run, with zero failures, errors or skips. Six new cases cover archive privacy, exact pending approval/result pairing, invalid continuation, qualification expiry, route changes, and the managed streaming protocol. See `JVM_FULL_RUN.json`.
- Android lint passed with zero errors and 12 warnings: six existing code/platform warnings and six dependency-update notices. Dependencies were not changed for this phase. See `LINT.json`.
- All 709 frozen source/build input hashes match `SOURCE_HASHES.json`.
- The combined JVM/lint/ARM64 release/instrumentation-APK assembly gate passed. The APK's original signing certificate, package, version code 42, ARM64-only libraries and 16 KB ZIP alignment were verified. See `RELEASE_GATE.log`, `APK_METADATA.json` and `APK_SIGNING.txt`.
- The companion gateway's full deployment regression suite passed all 140 tests on Node 22.23.5. See [gateway PR #108](https://github.com/therexdev/kai/pull/108).

The first JVM attempt could not initialize three UI classes because the environment lacked a Robolectric runtime dependency. After configuring the test runtime's repository/proxy, the complete unchanged-source suite passed. No test was disabled or assertion weakened.

## Behavior and compatibility

Managed native tools require protocol 2 and unexpired gateway qualification evidence. An older boolean capability alone no longer enables them. Provider continuation is opaque ciphertext bound to the local route and, on the gateway, to the deployment, credit owner, upstream model and exact assistant call. Exact approval and actual outcome receipts remain separate from continuation data.

Continuation survives encrypted saved-task storage and pending approval checkpoints. It is omitted from public exports, diagnostics, summaries and archive retrieval. A route/protocol switch clears reusable native wire while retaining effect observations. Expired or mismatched gateway continuation produces a non-retryable error with saved progress retained; it never authorizes replay of a write.

Install over 0.5.1 using the existing package/signing identity. Encrypted schema 3 is unchanged. Installing this APK alone does not enable native routes; the companion server deployment and explicit model qualification must complete first, followed by an Account refresh. Existing text/JSON operation remains available on unqualified routes.

## Limits

`connectedDebugAndroidTest` was attempted after successful APK assembly and stopped with `DeviceException: No connected devices!`; no instrumentation case executed. See `DEVICE_GATE.log`. The new SQLCipher restart/export/Forget instrumentation case is included, but physical-device persistence/lifecycle/audio remains unverified. Mock gateway and UI tests are not evidence of real supplier behavior or a live email/document/spreadsheet write.

Production gateway installation and paid model qualification were not performed in this session. General OpenAI Responses/Anthropic signature replay, budgets, source assurance, voice improvements and the remaining product roadmap are separate work.
