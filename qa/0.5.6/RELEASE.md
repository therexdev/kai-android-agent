# KAI Android 0.5.6 test update

This update corrects shared progress and context boundaries across chat, tasks and routines. Repeated or failed reads retain their no-progress state across restarts; search and page reading are admitted independently; stalled collection closes so the run can use saved evidence for its requested output. Committed connected-service results share the same success/failure rule across adapters. Exact approvals and UNKNOWN-outcome protections remain separate.

Canonical claims and source references survive bounded projection, while cached pages return pointers instead of full duplicate copies. Automatic summaries are skipped when canonical run progress exists. New findings deduplicate the same exact source and claim. Retrieval no longer guesses topic relevance with a keyword list, and HTML gateway error pages are omitted from diagnostics.

These changes do not establish factual correctness, complete research coverage or a live successful document/email workflow. The reported upstream HTTP 502 has not been fixed or reproduced by a live supplier call. See `VALIDATION.md` and `docs/SHARED-PROGRESS-CONTROL.md` for measured checks and limits.

Install over the existing preview; version code is 46, SQLCipher schema remains 3 and the original signing identity is retained. No server update or new paid qualification is required. Old records are not rewritten; start new work for a clean exercise of the new collection rules. An explicit continuation keeps old evidence and any already-closed collection state.

## Small phone check

1. Install over the existing preview and confirm existing conversations/tasks remain readable.
2. Start a bounded research-to-document request with a few entries and a clear source requirement. Review the exact document action before approving it; inspect the resulting document and citations. A partial result must identify unfinished work rather than claim completion.
3. Pause and reopen before approval. Confirm saved findings, collection status and the exact action survive; approve once, then confirm subsequent steps use the receipt and do not recreate the write.
4. Exercise the same small goal as a saved task. A service failure or unavailable provider must retain partial work without repeatedly dispatching an uncertain write.
5. For an email step, review the exact recipient, subject and body before approving. Verify the actual connected-service result; a service acceptance is not proof of delivery.

Export a fresh report if any boundary fails. Physical device, live service and voice/lifecycle behavior remain separate validation gates.
