# KAI Android 0.5.7 test release

This build removes the mandatory research-note/citation gates and 0.5.6 automatic collection/stalled-run policies. Fetched text remains usable with zero saved findings, cached reads return content, exact source IDs page original excerpts, and managed native answer-only requests retain protocol 2. Chat, saved tasks and routines share the corrected components. Exact action approval, unknown-write protection, real run allowances and document read-back remain.

Install over the existing preview: version code 47, package `com.kai.agent.preview`, SQLCipher schema 3 and the original signing certificate. Existing data and historical notes are retained. No server update or new provider qualification is needed. See `VALIDATION.md` for what was actually tested.

## Phone acceptance check

1. Confirm existing chats/tasks still open after upgrading.
2. Repeat the previously successful research-to-document request without email. Review the exact proposed document, approve once, inspect its real content and read-back result. This must reach document review with no manual quote-recording step.
3. Repeat with only the email step added. Confirm one document is created, its actual returned link is reused, and the exact email recipient, subject and body are reviewed before approval. Verify the real service result separately from delivery.
4. Pause/reopen before approval and repeat as a saved task. Confirm source retrieval, original goal and exact approval survive; a previously accepted or uncertain write must not be submitted again.
5. Exercise Answer from findings on a native run with retained tool history; it must offer no tools and avoid the prior native-protocol 400.

Export a fresh report at the first failing boundary. These physical/live checks remain open; compiled instrumentation and scripted component scenarios do not substitute for them.
