# Automatic context discovery (0.4.16)

Koinos requests now refresh the authenticated model catalog before budgeting input, including fresh clients used by saved tasks and after app restart. Capabilities are scoped to endpoint, credential and model, expire after one minute, and are rediscovered rather than persisted as an assumed hardware capacity. Cancellation stops discovery before inference. Authorization, redirects and rate limits stop the request; unavailable or missing discovery uses the conservative fallback. Refreshing without metadata invalidates a previously larger limit.

The matching Core update automatically grows context per request, bounded by the model/owner ceiling, and verifies the actual runtime allocation. Remote Access forwards that ceiling as `context_window` with `context_mode: "auto"`. Android supplies the estimated prompt plus response allowance and template reserve as `context_tokens` only when automatic allocation is explicitly advertised. Fixed/older APIs receive no unsupported extension. Larger advertised windows permit a larger requested response allowance; the 4K behavior retains its prior bounds.

The fallback error now identifies 4,096 as a conservative client assumption when no metadata was received. It does not claim that Core's loaded model was measured at that size. Full current requests and action receipts remain pinned; no external write is retried by this change.

Core 0.7.32: https://github.com/therexdev/kai-core/releases/tag/v0.7.32
Remote Access 0.3.4: https://github.com/therexdev/kai-plugin-remote-access/releases/tag/v0.3.4

The released Core and Remote Access updates must be installed to obtain automatic larger windows through that API. The APK remains compatible with older installations. The 128 KiB request bound and local-only Remote Access inference remain; larger prepaid-network windows still require separate scheduler/protocol work. Tests use simulated services/engines and do not prove physical GPU capacity or live Google actions.

# Context follow-up (0.4.15)

The 0.4.14 synthetic document fixture used only three abbreviated accounts and an empty task ledger. It did not cover the reported web detour with populated account and operation context. The 0.4.15 regression fills the ledger with six prior operations (including UNKNOWN), adds eight connected accounts and memory context, and reproduces an overflow in the earlier path.

For a narrowly recognized request that supplies a document title and literal text, the phone sends a connected-action protocol rather than unrelated memory and research schemas. Open-ended, research-based and personal-reference requests retain the normal protocol. A mistaken model web request is redirected once before any web network call; repeated detours stop explicitly. This policy is enforced by the web session, not just a prompt. Ordinary research remains available.

General conversation also has an explicit optional-memory fallback: memory status, saving mode, facts saved this turn and identity clarifications are preserved, while older retrieval/review views are deferred with a lookup instruction. Nothing is removed from storage. Account metadata retains every exact account ID and provider identity but removes redundant labels. Small-context catalogs show at most three tools with honest coverage markers; inspection still loads the full validation schema with bounded annotations.

On post-approval continuation, old conversational display replies can be omitted, while the complete runtime continuation and phone operation ledger remain pinned. No result is recast as proof of delivery, and no write is retried automatically.

Google Docs fixture parameters are the documented `GOOGLEDOCS_CREATE_DOCUMENT` `title` and `text` fields, checked against the provider's primary documentation on 18 November 2026:
https://docs.composio.dev/toolkits/googledocs
https://landing.composio.dev/toolkits/googledocs/framework/cli
The fixture is not a downloaded live account schema. Production still discovers and validates the actual schema and pinned version before execution.

# Small served contexts (0.4.14)

The 0.4.13 budget guard could reject a short document request because the prompt combined full agent instructions, a broad memory review view, accounts, and multiple tool schemas. Trimming old chat alone could not solve this. Separately, inference compaction could turn the newest inspected schema into a request to inspect that same schema again.

## Changes

- Koinos conversation and saved-task runners use concise protocol instructions with the same phone-owned action, account, research and memory boundaries. OpenAI retains the full instructions.
- The conversation's optional memory review view is limited to 1,000 characters by removing whole oldest records, with `moreAvailable` when records are omitted. The retrieved initial fact view is limited to 900 characters. Committed-this-turn facts, memory mode/status and identity clarifications remain present. The phone reconciles proposed corrections against the full current graph. `search_memory` remains available for additional facts.
- Small-context discovery exposes up to six tools within 2,600 characters, with exact IDs, read-only status and honest `hasMore`. It does not preload six unrelated schemas. The next step inspects one selected tool.
- Small-context schema inspection removes duplicate root documentation and bounds field descriptions to 96 characters. Property names (including names such as `description`), required fields, enums, bounds, references and nested validation constraints are preserved. Execution still validates against the original schema.
- Earlier discovery detail can compact to a marker stating no service action was executed. The newest tool result has no compact fallback. Actual execution results remain pinned, along with the current user request; no uncertain action is replayed by this change.

## Limits

This does not enlarge the desktop model's configured context. The API's advertised served window is used when available; otherwise the existing 4,096-token fallback applies. Token estimation is conservative UTF-8 sizing, not an exact model tokenizer. A 512-token template reserve and output allowance remain enforced.

Very large required schemas, documents, user requests or action results can still exceed a small served window and fail explicitly. The app does not truncate arbitrary required arguments or silently discard execution evidence. This update does not add provider fallback, change production services, or claim live model/tool-account verification.

Persistent memories, history, encrypted storage, scopes, approvals and deletion behavior are unchanged. Install using the existing signing identity over the previous app.


## Response recovery and visible context (0.4.17)

An incomplete-action message is a response-format failure; it does not prove a context limit was hit. Complete provider responses that fail the action parser, or report an output-length stop, can trigger at most two corrective inference calls in a foreground turn or worker invocation. A new complete response must still pass the ordinary account, discovered-tool, schema and approval checks. This does not retry service requests or ambiguous writes. Rejected fragments are not included in correction prompts; the original request and exact tool/operation context remain.

A response already being spoken is never replaced by recovery. Cancellation, network/API errors and content filtering do not trigger format recovery. Exhaustion reports that this response did not execute an action and directs the owner to earlier task receipts; it does not falsely claim that the entire conversation executed nothing.

In phone AI settings, Test API shows the catalog ceiling and whether automatic sizing is supported. Completed model responses report the active window from `x-kai-context-size` or verified `kai_context` metadata when present; a missing value is shown explicitly, never inferred from the ceiling. Desktop Core 0.7.32 offers optional model limits under Model library → your model → Automatic context. Remote Access 0.3.4 advertises those limits. No new phone context override is needed.


## Generation diagnostics (0.4.18)

A successful Test API result verifies authenticated discovery, selected-model availability, and the allowed context ceiling. It does not start the model or prove that generation fits. Test response performs one synthetic local generation with two short messages and 128 output tokens; it does not enter the agent loop or use chat, memory or connections. The result reports the active allocation only when returned by the API. No failed generation is automatically retried.

Remote Access 0.3.5 reads at most 16 KiB of unsuccessful streaming inference responses and emits a fixed error category with the original HTTP status. Validation failures identify their own stage. Android accepts only fixed diagnostic labels and never displays raw provider errors, paths, prompts or credentials. Legacy validation messages and unknown HTML/empty responses remain identifiable. Core 0.7.32 is sufficient; update the plugin and fully quit/reopen Core to ensure the new service is running.

The observed phone configuration is desktop Remote Access, llama31-8b, automatic context up to 131,072 tokens. The particular HTTP 400 has not been reproduced using the owner's live model or credentials.


## Undiscovered action recovery (0.4.19)

The latest screenshot shows a completed model proposal rejected by the phone's discovered-tool check. It does not show a context overflow or an HTTP 400. The screenshot alone cannot distinguish a guessed tool name, a skipped discovery step or an incorrect account ID, so all three are covered without relaxing execution guards.

Before preparing a task, both agent runners now validate the exact active account and previously discovered tool. An unknown account receives only authoritative account data. An unknown tool may trigger one bounded catalog read for that account, using only a constrained tool-name query and never the rejected arguments. An exact discovered match returns its schema; other results expose real tool IDs and require inspection as needed. No fuzzy match, account alias or service name becomes execution authority. Invalid argument fields receive the real schema rather than an immediate dead end.

Every recovery discards the proposed action and requires a new model response. Original instructions and operation receipts remain in context. Three failed proposals exhaust recovery; network execution failures and uncertain writes are never retried by this code. The same path runs in chat/voice and saved tasks. Catalog permission failures retain their existing structured explanation rather than implying OAuth is broken. Cancellation propagates.

This release changes phone-side orchestration only. Automatic context, the Test response diagnostic, Core 0.7.32 and Remote Access 0.3.5 remain unchanged. The prepared instrumentation uses the real Android controller/store and simulated model and connected-service responses. These cases compiled but this run was blocked during emulator installation, before tests started. Unit tests passed; they cannot prove the owner's Llama model or real Google account will finish the request.
