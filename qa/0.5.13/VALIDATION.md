# KAI Android 0.5.13 (53) validation

## Scope and source continuity

Baseline source: `0ee431df04c9d7403efbc9db4a4fdc909b25f4c0` (0.5.11). The 0.5.12 APK was retained, but its unpublished local source commit/patch was unavailable. Its changed DEX methods were compared with 0.5.11 and the relevant preparation-slot persistence, draft feedback, recent read-page projection, document-resource handling, model-unavailable classification and budget-boundary evidence finalization were reconstructed. This is behavior reconstruction, not a claim of byte-identical source recovery. No signing material or user report contents are included in this repository.

## Report findings and changes

- The final chat's Sheets follow-up never reached inference. ResourceReuseGuard's broad link classifier matched “reference link” and the letters “it” in “with”, returning an old Docs URL. The shortcut and its intent-based rejection are removed; new requests reach the agent with ordinary history and receipts. Exact duplicate/UNKNOWN effect checks remain.
- A successful table write/read was rejected solely because the verifier did not support Markdown tables. GFM table cells and inline `<br>` now compare visible text; structured tables and a complete single tab are supported. The BY_ID action's `id` alias is accepted. Full-body equality, run/account/owner/generation binding, later observation, failure/truncation and warnings remain authoritative.
- Decorated plaintext tab banners are not guessed away. Typed read issues point to complete structured readback or undecorated plaintext, preventing a request to repeat the same unsupported shape. Multiple/partial tabs, images, code and arbitrary HTML remain unverified.
- Native Gmail definitions now expose the same nonempty subject-or-body constraint already enforced at preparation. They do not fill arguments, change the catalog approval schema, or submit mail.
- Recent unique context pages and a bounded record of page offsets/read counts survive projection. The archive index omits retrieval-result copies, avoiding recursive indexes. No new stop heuristic, retry allowance or research quota was added.

## Measured checks

Final JVM/UI suite: 699 tests passed, zero failures/errors/skips. The private replay additionally passed before its temporary test harness was removed from the release source. Final `testDebugUnitTest`, `lintDebug` and `assembleRelease` pass completed successfully. Lint: zero errors, 12 warnings.

Private report replay (not committed): seven retained task sets. The table case now returns TEXT_MATCHED; the three previously successful document/email cases still match. Two decorated-tab plaintext cases remain explicitly UNSUPPORTED_FORMAT. The permission-only read case remains NO_SUPPORTED_READ. No service requests were made by replay.

The existing scripted research/document component suite covers chat, saved-task and routine origins, native and JSON protocols, interruption/resume, zero saved findings, crowded catalogs, document-only and document-plus-email flows. These are mocked model/service tests, not evidence of actual model task completion.

Additional regressions cover preparation feedback/drafts across compaction and serialization, provider-wire pairing, bounded read history, table truncation/mismatch, tab scope, budget finalization, stale ownership, model-unavailable fallback and native email schemas.

## Remaining acceptance checks

No Android device was connected and this workspace has no KVM/emulator. Physical-device storage/lifecycle and microphone checks were not run. No live Google Docs/Gmail/Sheets writes or new model qualification were performed. On the phone, install over the current app, refresh the model catalog after server activation, and test research → document → email → sheet → edit in one chat, with one interruption/resume. Review every exact write and check the returned objects. Transport qualification cannot establish research quality, citation accuracy or delivery.

Companion gateway helpers have 27 passing operator/native transport tests. Their rollout is still an operator-host action using its protected credentials; see the gateway's NATIVE_MULTI_MODEL_ROLLOUT.md. Roadmap status remains partial where full live conformance is outstanding.

## Signed artifact

`KAI-Android-0.5.13.apk`, versionCode 53, arm64-v8a, minSdk 28 / targetSdk 36. Original alpha signing certificate verified: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`.

APK SHA-256: `e30b01f1c96ab524b49010b1f58c1b131e2e9a6fec498a8f1b0aabeeb82c7e48`.
