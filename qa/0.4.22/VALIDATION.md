# kai Android 0.4.22 validation

Based on released 0.4.21 source, SHA-256 7a0351df9dcb35d941c91ed126e2a6ca278d0543db69a0e802bfe669f7daea95.

- 380 JVM tests passed, no failures/errors/skips. Fifteen new regression tests cover catalog ranking, capability-based context selection, all ten catalog tools in the actual serialized mock API request, complete inspected descriptions, target evidence and account/privacy boundaries, the captured placeholder failure, bounded correction, service error display, credential redaction, document receipt IDs/links and malformed diagnostic output. The existing five approval UI tests also passed.
- Android lint: 0 errors, 24 warnings.
- ARM64 phone APK and Android instrumentation APK compiled. Original installed certificate and 16 KB alignment verified. Package com.kai.agent.preview, version code 29, version 0.4.22.
- No physical-phone, emulator instrumentation, live inference or live Google account action was executed. Mock completion responses verify software behavior and prompt contents; they do not measure Gemma quality.
- The first two full-test attempts found that added compact instructions reduced available output below the established 512-token minimum for the populated 4K fixture. Compact wording was shortened without dropping the user request, schema or ledger; the final full run passed.

See docs/DOCUMENT-ACTION-FIXES.md for changes and the phone comparison procedure. Approval bindings, durable-before-submit and UNKNOWN/no-replay handling remain in place. Database schema and signing identity are unchanged.
