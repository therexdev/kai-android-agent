# kai Android 0.4.16 validation

Version code 23. ARM64, Android 9+, original signing identity retained.

- 328 JVM tests passed, zero failures/errors/skips. New coverage includes discovery before budgeting, fresh/background clients, TTL refresh and reduced caps, endpoint/credential/model isolation, missing/invalid metadata, older APIs, authorization failures and cancellation.
- Six Android checks passed: fresh clients discover a 16K window and preserve a request above 4K plus UNKNOWN action evidence; document approval/action/receipt flow; populated-memory document request; Koinos and OpenAI memory conversations; legacy memory-card persistence.
- Lint: zero errors, 10 warnings. Universal and ARM64 APK payloads match outside the omitted ABI libraries. Original certificate and 16 KiB alignment checked.
- Core PR #13: 699 unit tests, 44 service tests passed (13 optional backend-fixture tests skipped locally), native Foundation and real Electron context controls passed. GitHub checks passed on Linux, Windows and macOS.
- Remote Access PR #3: 33 tests and source verification passed. No publisher release package was signed in this task.
- Cross-repository integration passed through the real Remote Access HTTP server, public SDK, Core gateway and runtime adapter with a synthetic engine child; verified 4K-to-16K growth, unchanged saved preferences and lower-cap enforcement.

## Release dependencies and limits

Core https://github.com/therexdev/kai-core/pull/13 and Remote Access https://github.com/therexdev/kai-plugin-remote-access/pull/3 are prepared changes, not released installers or a published plugin. Larger automatic windows require their release/installation. Older APIs remain usable with conservative budgeting; the fallback error identifies absent metadata instead of claiming measured model capacity.

Android refreshes scoped capabilities before inference and after one minute. It sends a request-scoped context minimum only for an API explicitly advertising automatic allocation. Saved context preferences are not changed. This does not extend prepaid-network tariffs/scheduling or the 128 KiB request bound.

Provider/model and service replies are simulated. No physical GPU capacity, live model quality, hosted relay deployment or Google account action was tested. No personal phone data was modified. The Android tests do not prove physical microphone/audio behavior.
