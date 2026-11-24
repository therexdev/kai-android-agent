# KAI Android 0.5.3 test update

Package `com.kai.agent.preview`, version code 43, ARM64. Install as an update over 0.5.2 using the unchanged alpha signing identity. SQLCipher schema 3 is unchanged.

SHA-256: `3245b0871e0cd220adf8446d075d4ede6607d07f8cc879648430038133d82d72`
Size: 125665227 bytes. Signing certificate and 16 KB ZIP alignment were verified; all 716 frozen source/build inputs match.

This release adds shared Google Docs body readback evidence and honest mismatch handling. It needs no additional gateway deployment, configuration change, or paid route probe. The existing qualified native route and JSON route both use the same phone verifier.

Start a new small document request, approve the exact write once, and check that a later read permits completion when the visible text matches. A failed or unsupported read must retain the real link and disclose the verification limit. Existing 0.5.2 partial runs are not silently reclassified or replayed. Verification does not authorize another write.

The full JVM/lint/build gate passed. Physical 0.5.3 chat/saved-task, lifecycle and voice checks remain separate. See VALIDATION.md and docs/DOCUMENT-VERIFICATION.md for scope.
