# Agent context management

Chat and saved tasks share `AgentContextManager`. This follows the separation used by [OpenClaw compaction](https://docs.openclaw.ai/concepts/compaction) and [LibreChat agent summarization](https://github.com/LibreChat-AI/agents/blob/main/docs/summarization-behavior.md): retained task records are separate from the smaller view sent to inference.

## Before each inference request

1. Estimate the input using the selected model's context window, output allowance, native function schemas and serialized UTF-8 payload size. Reported input usage can increase the estimate for later requests.
2. Near the working budget, shorten older consumed tool catalogs and schemas. Keep the latest inspected schema intact so the model can form valid arguments.
3. If more space is needed, summarize older original records and keep recent exchanges. The bounded summary request uses the same selected provider and credit policy, has no tools, and does not stream speech. It has a 15-second timeout and at most two attempts per active manager. An unsuccessful or incomplete summary falls back to deterministic local projection.
4. Validate native call/result pairing before sending the compacted projection. A confirmed provider context-overflow rejection permits one stronger-compaction retry of inference. Connection actions are never replayed by this retry.

The UI shows “Optimizing context…” while preparing a smaller request. These rules apply to both the JSON agent protocol and native calling; the context fix does not depend on deploying native gateway support.

## Retained data and retrieval

Live chat keeps canonical runtime exchanges for the active turn. Pausing for action approval saves the complete exchanges into an encrypted task archive, separately from its small compatibility checkpoint. Saved task steps keep their canonical messages and a separate summary cache. Older array checkpoints remain readable.

The model can use `read_context` to retrieve paged original results. `index` lists current and eligible saved archives; `t_N` reads an exchange and `h_N` reads a retained earlier chat message. `task:ID:t_N` and `step:ID:t_N` address saved records in the same conversation. Each result page is bounded to 3,000 characters. Full results remain phone-owned, subject to the storage and privacy limits below.

Summaries, earlier model proposals and retrieved content are untrusted data. Exact account/tool/version bindings, reviewed arguments, durable action state and provider receipts remain in the phone's action ledger. A summary cannot authorize a write or turn ACCEPTED/UNKNOWN into verified success. Duplicate-document and uncertain-outcome guards remain independent of model context.

## Privacy and limits

Action archive restoration checks conversation, privacy boundary, connection owner and connection generation. Forgetting, relevant context changes or disabling memory invalidate replayable archives and saved summary/message caches while retaining the intent and status needed to reconcile submitted operations. Diagnostics omit the private archive field; compaction traces record sizes and stages rather than summary text.

Compaction is lossy. The model must retrieve original records when a missing detail matters. Input estimates are conservative approximations, not exact tokenizer counts. An oversized current request, required instructions or required tool schema can still exceed the model's limit; the app then reports that specific limit and retains action receipts. Canonical exchange text is capped at one million characters and serialized task archives at two million characters. This is bounded continuation, not unlimited context or synchronization across devices.

Summary inference may incur normal usage under the user's selected account and paid/free settings. Failure to summarize does not discard the retained records. Automated tests use synthetic data and mock inference; live provider research quality and physical-phone behavior need separate validation.
