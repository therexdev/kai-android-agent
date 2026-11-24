# Android 0.4.32 validation

## Changes and reported failure

The reported three-message conversation failed after approval because the resume path inserted a serialized tool checkpoint into the pinned prompt and then hit the Android agent's fixed 56,000-character guard. The selected model's advertised window was larger; this was a phone context-assembly limit before the next inference request.

Chat and saved tasks now share proactive context budgeting and compaction. Canonical phone-owned records are separate from the provider projection. Older consumed catalogs/schemas are shortened first; bounded summarization of original older records follows when necessary. Recent exchanges remain available and `read_context` provides paged access to retained details. Native call/result IDs remain paired. The same path supports the existing JSON protocol independently of gateway native-tool activation.

Action review checkpoints save a compact compatibility view plus a full encrypted archive. Saved steps keep their original messages and a separate summary cache. Old array checkpoints remain readable. Exact intents, account/tool bindings, arguments, statuses and receipts remain authoritative outside summaries. Context recovery retries inference once only for a recognized context-overflow rejection; it cannot execute or replay a connection action.

Summary inference uses the same provider/account/free-or-paid policy, no tools and no speech. It is bounded to 512 output tokens, 15 seconds and two attempts per active manager. Failure, truncation or timeout uses deterministic local projection; cancellation propagates. Forget/context changes invalidate replayable archives and summaries, including stale in-flight summary writes. Diagnostic exports omit full private task archives.

Google document readback omits large default formatting metadata while retaining body content and resource references. Oversized result excerpts remain valid JSON rather than sliced JSON fragments. Native argument estimates count serialized UTF-8 bytes.

Design, retrieval semantics and resource bounds: [Agent context management](../../docs/AGENT-CONTEXT.md).

## Verification

- 301 focused JVM tests passed with zero failures, errors or skips. Includes a synthetic reproduction of the captured checkpoint/outcome lengths; mock-HTTP approval resume; bounded provider overflow recovery; UTF-8 schema/argument budgets, the client input cap with a larger model window, and the managed 4K document flow; native multi-call pairing; original-record retrieval; legacy and large saved-step serialization; summary failure/truncation/cancellation; privacy invalidation; and existing document/research/voice guards.
- Android lint passed with zero errors and 24 warnings: dependency update notices, existing view/KTX suggestions and ARM64-only ChromeOS guidance.
- ARM64 release build passed. The frozen implementation snapshot remained unchanged through JVM, lint and release verification.
- x86_64 app and instrumentation builds passed. A fresh API 30 software emulator reached Android/package readiness, but APK installation failed before app-runtime checks. No device tests completed. The device build contains the same storage/archive changes; the final input-budget and compact-prompt review was verified by JVM tests and the ARM64 release build. Physical-phone behavior remains unverified.
- Signed phone APK: `com.kai.agent.preview`, versionName `0.4.32`, versionCode `39`, ARM64 Android 9+. Original alpha certificate SHA-256 `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89` and 16 KB ZIP alignment verified.
- APK SHA-256: `443edcd010c16dd5c20c542a1c9d121e91f23c2587fa33411d25748da82628c4` (125,259,723 bytes).

## Limits

All inference, connected-service and billing assertions in JVM tests use synthetic data and mock services. No real Google document was created or edited, no live model comparison was run, and no production service was deployed. Summaries are lossy; details must be retrieved when needed. A single oversized current request, required prompt or required tool schema can still exceed a model's limit after compaction; action receipts remain retained. Canonical exchange text and serialized archives have explicit storage caps.

The gateway and native registry work from 0.4.31 remains capability gated, with companion gateway PR https://github.com/therexdev/kai/pull/107. Installing this update does not activate native calling on production. Prior voice interruption improvements are retained; physical-phone microphone/speaker behavior remains separately unverified.
