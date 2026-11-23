# Android 0.4.31 validation

## Changes and scope

The phone now has a shared native function registry for chat and saved tasks. Functions are bound to an exact connected account, tool version and JSON schema. Native responses accumulate bounded fragments and require complete call IDs and arguments. The runtime handles one action at a time, preserves formal tool-result IDs, detects repeated proposals, and never treats transport failure as permission to repeat a write. Existing account, approval, schema, target and durable submission checks remain in effect.

Native support defaults off. The authenticated account catalog must advertise `nativeTools: 1` for the selected model. The companion credits PR is https://github.com/therexdev/kai/pull/107. Its allowlist requires an explicit synthetic provider probe before activation. Installing the APK alone does not enable native transport on the production gateway. Legacy JSON recovery remains available for models without advertised support; failures do not silently change protocols mid-run.

Research/discovery exchanges survive the approval pause in encrypted task context. A changed privacy boundary prevents restoration and clears replayable result context while retaining exact intents and receipts for reconciliation. Saved native checkpoints preserve call IDs across serialization and allow the pending approval call without admitting orphan results.

Document receipts retain `display_url` and `documentUrl` despite large Google metadata. Existing-document link requests use scoped receipts and cannot create a new document. Duplicate titles and UNKNOWN/RUNNING creations require reuse or reconciliation. Research document actions with a body-capable schema require actual content before review. A researched body write must be read back from the same document ID/account or the reply reports unverified acceptance. Literal supplied text retains the existing exact-text acceptance check.

Web extraction preserves bounded public HTTPS anchors, including ecosystem navigation. Unread outgoing links are navigation evidence, not fetched-page sources. Koinos project queries retain blockchain scope; unrelated excerpts cannot count as relevant project evidence. Fresh Koinos research document proposals cannot introduce URLs outside user input, search results or extracted links; equivalent URLs are normalized before comparison. This relevance check is heuristic; it does not prove every project claim correct.

Voice changes from 0.4.30 remain: tolerant recent-playback echo rejection, stable partial interruption confirmation, immediate explicit corrections, and interruption diagnostics bound to the original privacy epoch. Native replies begin speech only after the completed response is validated.

## Verification

- 277 focused JVM tests passed with zero failures, errors or skips. Includes fragmented native transport through mock HTTP, exact account/schema binding, deferred registry activation and UTF-8 budgets, call-ID checkpoint round trips, privacy invalidation, document/readback guards, duplicate/link follow-ups, public navigation/relevance, legacy recovery and voice turn handling.
- 48 gateway mock-service tests passed across native function transport, signed usage, idempotency, billing, catalogs, diagnostics and metered streaming.
- Final reviewed source snapshot remained unchanged through the JVM, lint and ARM64 release checks. Android lint reported zero errors and 23 warnings (dependency update suggestions, existing view/KTX suggestions and ABI guidance).
- ARM64 release and x86_64 app/instrumentation builds passed. The emulator reported Android boot complete, but its package-manager service was unavailable during installation (`cmd: Can't find service: package`). No app-runtime test completed; this failure occurred before APK installation. Physical-phone microphone/speaker behavior remains unverified.
- Signed phone APK: `com.kai.agent.preview`, versionName `0.4.31`, versionCode `38`, ARM64 Android 9+. Original alpha certificate SHA-256 `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89` and 16 KB ZIP alignment verified.
- APK SHA-256: `02a697fa9c5c0706feecba48e792478a87aec5fa25d44a59ab1c45d06e275558` (125,198,283 bytes).

## Limits

All provider, Composio and billing assertions in automated tests use synthetic services and identities. No real Google document was created or edited, no live provider comparison was run, and no production service was deployed. A native protocol probe verifies function-call support; it does not establish research quality or end-to-end completion with a particular model. Readback remains conservative: large, truncated or differently formatted service responses can produce an unverified result even when a write was accepted. Physical Android microphone/speaker behavior requires a phone test.
