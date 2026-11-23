# kai Android 0.4.23 validation

Based on released 0.4.22 source, SHA-256 c09c5bd7bc9afae3dcb47370e43883ffc2dcc2476c69c7e08911350bb656d0b5.

- 390 JVM tests passed, no failures/errors/skips. Ten new tests cover timeout policy, delayed response headers, partial/stalled responses, the absolute deadline, cancellation, unrelated-tool recovery using catalog fields captured in the reported failure, and preservation of relevant schemas and account boundaries. The existing five approval UI tests and 4K context fixture also passed.
- Android lint: 0 errors, 24 warnings.
- ARM64 phone APK and Android instrumentation APK compiled. Original installed certificate and 16 KB alignment verified. Package com.kai.agent.preview, version code 30, version 0.4.23.
- No physical-phone, emulator instrumentation, live inference or live Google action was executed. Network tests use a local mock server and scaled millisecond timeouts. They verify transport/recovery behavior, not desktop model performance.
- Initial throttled-stream test fixtures accidentally throttled request uploads as well as response bodies in MockWebServer. The fixtures were corrected to let request uploads complete; they now distinguish pre-header, body and overall-deadline waits. This did not require a production stream-parser change.

See docs/LOCAL-INFERENCE-TIMEOUT.md for report findings, changes, limits and the next phone comparison. Saved-task model deadlines are capped by the remaining worker budget. Approval bindings, durable-before-submit and UNKNOWN/no-replay handling remain in place. Database schema and signing identity are unchanged.
