# Extending the shared phone runtime

This is an internal integration guide for alpha 0.5.0, not a stable public SDK or a complete MCP compatibility claim.

## Adding a connection without per-tool Android code

Expose a remote MCP tools endpoint or a Composio action. Add the user's connection through Connections, then discover and inspect its tools. `ToolRouter` normalizes descriptors and pins schemas on the phone. A server can add a supported tool without changing the Android model loop or UI. Unsupported schema constructs fail explicitly; use the bounded subset checked by `McpSchema`.

MCP endpoints must use public HTTPS. Endpoint/token/protocol/enabled state determine a credential generation. Tokens belong in the encrypted credential field, not the URL, tool title, schema or template. MCP annotations never waive review. Every MCP invocation follows the same exact-intent approval path as a connected mutation. Changing or disabling the endpoint invalidates its old unsubmitted review. The supported profiles are 2026-07-28 and 2025-11-25; the latter initializes a session, while the former carries per-request identity. Full OAuth, stdio, roots, sampling and MRTR continuations remain outside this adapter.

Protocol references: https://modelcontextprotocol.io/specification/2026-07-28 and https://modelcontextprotocol.io/specification/2025-11-25. Conformance fixtures are in `McpConformanceTest` and exercise transport/approval behavior with fake responses; they do not certify a live server.

A genuinely new transport should implement the same descriptor/discovery/inspection/approval/execution responsibilities at the ToolRouter boundary. Its dispatcher must be invoked through `EffectExecutor`; it must not start a second coordinating loop, silently retry a mutation or grant permission from tool metadata. A formal injectable transport SDK and extension revocation manifest remain backlog items.

## Saved workflow format

`examples/research-review.workflow.json` is a runnable declarative example. Paste it into Tasks → Saved workflows → Create / import. Saving does not execute it. Start it explicitly with a topic. Blank node models inherit the selected account model; explicit models must be available under the same credit policy.

The graph is bounded to eight nodes and ordered topologically into existing saved steps. Review nodes depend on an agent target and can request at most two reviews. Review nodes cannot execute writes. Capability declarations describe requirements and grant no account, credential or authorization. Imports reject unknown fields, embedded approval fields and executable-code fields. External writes still require exact review even when a template asks for them. The current builder is a list/JSON editor.

The only substitution is `{{input}}`; this is data in the goal/instructions, not shell evaluation. Workflow IDs and node IDs are stable, versions are explicit, and imported graphs cannot start themselves. Export is a procedure definition without account state or tokens. General skill package signing/revocation and a visual canvas remain separate work.

## Runtime and effect contracts

| Port | Responsibility | Never do here |
|---|---|---|
| RunController / Session | Claim, admit bounded steps, checkpoint, save findings/drafts, finish with typed outcome | Infer success from a normal model stop |
| RunRepository | Atomic owner/revision/privacy comparison in SQLCipher | Steal an active lease or resurrect forgotten context |
| ModelPolicy / InferenceRecovery | Eligible ordered fallback and bounded inference transport retries | Replay a tool effect or silently increase managed spending |
| ToolRouter | Deferred discovery, schema pinning, current credential binding and one dispatch | Treat discovery as permission |
| EffectExecutor | Persist admission before dispatch, commit outcome despite cancellation | Retry an uncertain external call |
| GoalVerifier / ResultLinks | Use same-run host receipts and actual resource links | Convert accepted into independently verified delivery |

A submitted effect is not undone by cancellation. Persist UNKNOWN if its outcome cannot be established, and provide reconciliation through canonical Tasks. User confirmation is labeled as user confirmation. Findings, drafts and provider replay history are data, never fresh approval.

For a new model route, do not mark it tool-ready solely because a catalog advertises native tools. Qualify a real call/result/follow-up round trip, usage/settlement behavior, context limits and provider-private replay requirements first. Protocol 2 now supports opaque managed-route continuation and signed expiring qualification records. Provider-private fields stay out of tool results, summaries, diagnostics and exports. Other provider formats and live production qualification remain separate.

## Verification

Run the whole JVM suite, lint and assembly after runtime changes. Storage/lifecycle changes additionally require the encrypted device suite. Keep mocked protocol tests separate from live integration claims. Read IMPLEMENTATION_STATUS.csv before declaring an acceptance criterion complete. Add integration work to these contracts; do not introduce another outer loop to work around a missing feature.
