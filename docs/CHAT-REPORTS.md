# Chat reports (0.5.11)

Run your tests in regular chats, wait for work to finish, then open **Settings → Report → All saved chats → Save report**. All saved chats is the default; **This chat only** remains available. History also has Report buttons. Android's file picker saves one ZIP containing a JSON report that you can attach for batch review. Settings' Report button stays at the bottom and works even without an active chat, provided saved regular chats exist.

The batch includes all saved regular chats, not just failed attempts or the currently selected chat. Temporary/deleted chats are excluded. An export is a snapshot taken when the destination is opened: save a fresh report after further tests. No analytics, automatic upload or external reporting is enabled.

### Batch format

The ZIP contains exactly `kai-all-chats-report.json`, encoded as UTF-8. Compression is streamed along with serialization, without a full intermediate file/string. Single-chat export remains JSON.

`kai-chat-batch-report` version 1 contains `reports`, an array of individual `kai-chat-report` version 1 objects using the same exporter as single-chat reports. Chats are ordered by creation time and ID. Each retains its own messages, tasks, runs, memory jobs, trace sessions and coverage; events are never merged across conversations.

`index` links conversation IDs/titles/timestamps to observed provider/model pairs, recording app versions, recorded run-state counts, failure codes, retained/dropped event counts and last privacy reset. `summary` totals the coverage and recorded states across the batch. Run states are runtime labels, not an independent success score. A chat with no retained trace is still included and counted explicitly; missing evidence never establishes non-execution.

The top-level `app` describes the exporter. New trace events also record `recordedAppVersion` / `recordedAppVersionCode` so batches spanning app updates are distinguishable. Older events without these fields have unknown recording versions; the exporter does not backfill them with its current version.

## Evidence

The `kai-chat-report` version 1 envelope contains the app/Android version, transcript with stable message IDs and timestamps, associated tasks with durable intent/approval/receipt history, and an ordered trace. Sessions link to the user message; model/connection requests have request IDs; actions and transitions carry task/intent IDs. Entries distinguish a proposal, rejection, preparation, dispatch attempt, transport status and interpreted outcome. A successful HTTP response is not proof of action success or publication. Missing end events can mean interruption and never establish that a write did not happen. Check the durable task ledger before repeating a write.

Model records include the actual post-budget messages (image counts only), provider/model/routing, generation parameters, advertised/fallback and active context limits, conservative token estimates, omitted/compacted counts, response/partial output, finish reason, token usage, timings and bounded HTTP errors. Tool records include catalog metadata, schema shown to the model, runtime corrections, exact action arguments, approval state and service envelopes. Saved task steps also use the tracing context. No hidden model reasoning or provider-internal/server logs are available. Audio and attachment bytes are excluded; relevant memory/file text already sent as model context is included.

## Privacy and retention

Diagnostics use SQLCipher tables in the existing no-backup database, with an ordered v1→v2 migration. Temporary chats never create diagnostic sessions. Trace writes validate a persistent privacy epoch and current conversation under the same store mutex. Forgetting, correcting or deleting private sources clears all diagnostic traces atomically with that state change; credential changes also clear them. Later callbacks from the previous epoch are discarded. Logging failures are isolated from action execution and counted in the report for the current app process. They never cause network replay.

From 0.5.11, keep the newest 2,000 events / 4,000,000 characters in each of the 20 most recently traced chats (at most 80,000,000 characters of trace payload). This supports collecting a 10–20-chat test batch; very long individual chats can still exceed their limit. Prior versions retained only 1,000,000 characters per chat and 4,000,000 globally. The update cannot recover already-pruned events.

Oldest records are removed with per-chat omission counters. The global bound prunes whole oldest trace groups via the existing conversation index instead of scanning all other chats' large payloads on every append. SQLCipher schema stays at 3. Each event is still capped at 196,608 characters, and large/deep fields contain explicit omission markers. Limits apply to diagnostics, not transcript/task history: every saved regular chat remains exportable. Reports show retained counts, dropped counts, last privacy reset and limits.

Sanitization runs before persistence and again on export. It removes configured keys, common credential fields/token patterns, URL query/fragment/user-info and private relay routes. Audio/image bytes and long binary-looking fields are omitted. Arbitrary text can still contain personal information; this is not an anonymity guarantee. Review reports before sharing. The export contains no full app settings or credential headers. Generic data backups do not include diagnostics, and a chat report cannot be imported as a backup.

The all-chats export writes one redacted conversation at a time directly to the selected destination while holding the store snapshot/privacy lock. It does not stage a plaintext cache or build an aggregate batch string in memory. The pending scope survives activity recreation in the ViewModel; process death requires a new export. Cancellation or failed output never reports success and attempts to remove the partial destination. The older single-chat export retains its existing ViewModel staging behavior. Deleting data in KAI cannot delete copies the owner already exported.

## Validation

See `qa/0.5.11/VALIDATION.md` for current checks and limitations; `qa/0.4.20` records the original storage migration. Fixture tests do not establish that a real local model or Google account completes the user's request. This release collects evidence; it does not claim to fix the wrong tool selection shown in the screenshots.

As of 0.4.26, reports include memory capture decisions, source-bound background model requests/responses, validation counts, committed/pending counts and job status/errors. Empty model claims, rejected evidence, malformed output, interruption/requeue and timeout have distinct outcomes. Traces retain the existing credential filtering, privacy epochs and size limits.
