# kai Android 0.4.20 validation

Source change: 5869f59. Version code 27; original signing identity retained.

- 360 JVM tests passed, zero failures/errors/skips. Includes nine new diagnostic/transport tests covering sanitization, private/signed URLs, filtering twice, binary omission, oversized event bounds and correlation, actual budgeted request payloads, response usage/context, partial output, HTTP 400/422 detail and no extra requests.
- Latest full Android lint run: zero errors; dependency/style/ABI advisories remain. The final URL-marker spacing change and its unit assertion were subsequently compiled and tested in the final build; lint was not repeated for that string-only change.
- Final JVM, universal APK and Android instrumentation APK builds passed. Final ARM64 phone and x86_64 emulator variants built and signed. Shared non-native payloads are byte-identical. Signing certificate and 16 KB alignment verified.
- Android runtime validation is incomplete: the first software-emulator attempt timed out installing the old app, and the retry did not complete startup. No instrumentation tests ran; storage, upgrade and lifecycle cases compiled but are not claimed as passed.
- Device cases cover v1→v2 encrypted database upgrade, restart/readback, temporary-chat exclusion, forget/delete with late writers, retention counters, export rejection as import, Activity recreation, and existing document/action controller regression paths. These use synthetic model and service responses.
- No physical-phone, live Llama, live Google Docs or audio validation was performed for this release.

The first build attempt lost its Gradle daemon; obsolete software-emulator temporary overlays were removed and subsequent builds passed. One redaction test found a private-route URL marker issue and was fixed before the final passing run. Tests now cover mixed configured/unknown URL credentials and repeated redaction preserving document IDs.

Diagnostics do not authorize, select or replay actions. Existing approvals, submission-before-dispatch and UNKNOWN protections remain. A successful fixture test would not establish that the user's real model selects the correct Google action; that is what the new report is intended to investigate.
