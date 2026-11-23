# Chat reports (0.4.20)

Use a regular chat, reproduce the problem, wait for the turn to finish, then tap **Report → Save report**. Android's file picker lets you save the JSON in Downloads or a chosen folder. Attach that file when requesting help. History has a Report button for each saved chat. An export is a snapshot: save a new report after any additional test. No analytics, automatic upload or external reporting is enabled.

## Evidence

The `kai-chat-report` version 1 envelope contains the app/Android version, transcript with stable message IDs and timestamps, associated tasks with durable intent/approval/receipt history, and an ordered trace. Sessions link to the user message; model/connection requests have request IDs; actions and transitions carry task/intent IDs. Entries distinguish a proposal, rejection, preparation, dispatch attempt, transport status and interpreted outcome. A successful HTTP response is not proof of action success or publication. Missing end events can mean interruption and never establish that a write did not happen. Check the durable task ledger before repeating a write.

Model records include the actual post-budget messages (image counts only), provider/model/routing, generation parameters, advertised/fallback and active context limits, conservative token estimates, omitted/compacted counts, response/partial output, finish reason, token usage, timings and bounded HTTP errors. Tool records include catalog metadata, schema shown to the model, runtime corrections, exact action arguments, approval state and service envelopes. Saved task steps also use the tracing context. No hidden model reasoning or provider-internal/server logs are available. Background memory extraction, audio and attachment bytes are excluded; relevant memory/file text already sent as model context is included.

## Privacy and retention

Diagnostics use SQLCipher tables in the existing no-backup database, with an ordered v1→v2 migration. Temporary chats never create diagnostic sessions. Trace writes validate a persistent privacy epoch and current conversation under the same store mutex. Forgetting, correcting or deleting private sources clears all diagnostic traces atomically with that state change; credential changes also clear them. Later callbacks from the previous epoch are discarded. Logging failures are isolated from action execution and counted in the report for the current app process. They never cause network replay.

Keep the newest 500 events / 1,000,000 characters per chat, and 4,000,000 characters / 4,000 events globally. Oldest records are removed with per-chat omission counters. Each event is capped at 196,608 characters, and large/deep fields contain explicit omission markers. Limits apply to diagnostics, not the existing transcript/task ledger. Reports show retained event count, trimmed count, last privacy reset and limitations; pre-update steps cannot be reconstructed.

Sanitization runs before persistence and again on export. It removes configured keys, common credential fields/token patterns, URL query/fragment/user-info and private relay routes. Audio/image bytes and long binary-looking fields are omitted. Arbitrary text can still contain personal information; this is not an anonymity guarantee. Review reports before sharing. The export contains no full app settings or credential headers. Generic data backups do not include diagnostics, and a chat report cannot be imported as a backup.

Report bytes stay in ViewModel memory while the file picker is open, surviving rotation without putting personal text into saved-instance state or an unencrypted cache. If the process is killed, the app asks for a new export instead of claiming the file was saved. Deleting data in kai cannot delete copies the owner already exported.

## Validation

See `qa/0.4.20` for observed build, unit, storage migration/runtime and packaging results. Fixture tests do not establish that a real local model or Google account completes the user's request. This release collects evidence; it does not claim to fix the wrong tool selection shown in the screenshots.

As of 0.4.26, reports include memory capture decisions, source-bound background model requests/responses, validation counts, committed/pending counts and job status/errors. Empty model claims, rejected evidence, malformed output, interruption/requeue and timeout have distinct outcomes. Traces retain the existing credential filtering, privacy epochs and size limits.
