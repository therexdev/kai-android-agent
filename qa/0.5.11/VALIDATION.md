# KAI Android 0.5.11 validation

This release adds batch diagnostics for repeated attempts. It does not modify research, model selection, inference budgets, tool proposals, approvals, service execution or recovery behavior.

## Automated checks

- Lint passes with zero errors, 29 warnings and one hint. Release and instrumentation APKs assemble. The ARM64 release APK is verified as version 0.5.11/code 51, non-debuggable, aligned for 16 KB pages and signed with the original certificate.
- 687 JVM tests pass, zero failures/errors/skips. This includes 7 new batch writer tests and 4 new Compose/Robolectric report-dialog tests.
- A mixed 20-chat fixture retains distinct conversation/message/run/session IDs, groups recorded completed/failed states correctly, and matches each independently exported single-chat report. JSON comparison normalizes numeric representation, not content.
- Temporary conversations and their runs are excluded. Configured and echoed credentials are removed across all chats and index entries while useful personal content remains. Missing traces, dropped records and privacy-reset timestamps remain explicit; missing evidence does not invent success or recording versions.
- A 20-chat Unicode fixture round-trips through a streamed ZIP containing exactly one JSON entry. Writer failure propagates without reading the rest of the batch. Empty state is valid. UI tests cover default all-chats selection, single-chat selection, cancellation, no current chat and no saved chats.
- The existing redactor bound test also checks that recording-version metadata survives oversized-event truncation. Individual reports retain format/version 1 and the same serializer as batch entries.
- A supplemental standard SQLite check exercised the exact global retention query with 20 large groups exceeding the old 4,000,000-character global bound. All 20 remain; introducing group 21 selects only the oldest group and records its 14 dropped events. This is a query check, not an encrypted Android storage test.

## Device boundary

No phone or emulator is connected. Instrumentation cases cover 20 large chats across encrypted-store restart, temporary exclusion, credential filtering, oldest-chat eviction, deletion/privacy clearing and pending-export activity recreation. Their execution remains unverified on Android hardware. Existing retention instrumentation is updated for the new bound. No live inference, document write or email was performed.

## Retention and limitations

All saved regular chats are exported, including those with no retained traces. Detailed diagnostics keep at most 2,000 events / 4,000,000 characters in each of the 20 most recently traced chats. Prior versions' discarded records cannot be recovered. Forget/deletion/credential changes retain their existing diagnostic reset and late-callback invalidation behavior. Per-event and field redaction/omission bounds remain.

The all-chat ZIP is written directly to the chosen destination one redacted chat at a time under the store snapshot lock. No aggregate batch string or plaintext staging file is created. In-progress exports should be avoided during active work; exporting after the test batch produces a stable comparison snapshot. Failed/cancelled output is not reported as saved and partial-destination deletion is attempted.

Build/packaging results are recorded in RELEASE_GATE.log and APK_METADATA.json. SOURCE_SHA256.txt freezes the 738 build inputs. Original package/signing identity and SQLCipher schema 3 are retained.
