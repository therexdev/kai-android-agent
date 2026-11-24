# One request budget for messages and tools (0.5.10)

## Observed failure and correction to earlier validation

The supplied 0.5.9 Android report has 108 events and zero dropped events. Nine model requests receive successful responses. After collecting sources, the model calls broad document-tool discovery again. Discovery returns a 16,304-character response. Before the tenth inference dispatch, the phone raises its local context-limit exception. No action was prepared, submitted or created. This run does not reach the title/body error from the preceding report.

0.5.9 correctly preserved the latest result, but exposed the missing shared budget: the harness chose up to 24 KB of native definitions independently, then the context manager tried to fit messages in whatever remained. Research, the catalog, instructions and opaque provider continuation all compete for the same 60 KB application allowance. A larger model context window does not increase that byte allowance. The earlier unit and replay checks did not reproduce that complete combination. Preserving the latest exchange without testing it together with a populated registry and continuation data was insufficient release validation.

The report redacts continuation ciphertext and omits the complete native definitions. Reconstructing only the visible messages yields a 35,865-byte strongest projection, which can narrowly fit with a maximum registry. Adding surrogate continuation data reproduces the rejection. This is a bounded reconstruction, not an exact replay of the private supplier request or proof of the missing token's size. The actual local rejection is directly recorded. No real continuation token is recovered, imported or published.

## Shared implementation

`AgentContextManager.request` selects the registry and messages together. It reserves the strongest existing protected message projection first, including exact latest results, current research, instructions, receipts and opaque continuation bytes. It then computes the remaining UTF-8 byte and estimated token capacity under the existing limits. Optional native definitions are selected within that capacity; the existing message preparation runs against the resulting configuration. The same paired configuration/messages are used for admission, inference and native parsing.

Chat and saved-task/routine adapters use this entry point. JSON protocol continues without a native registry. The harness keeps its existing account/tool focus and exact schema binding. Deferred tools remain in the catalog and can be activated with `inspect_tool`. An explicitly selected schema or required phone tools that cannot fit produce an explicit capacity failure; neither the current result nor the schema is silently truncated. Registry accounting now includes the serialized array delimiters and separators.

This changes request construction before inference. It adds no paid inference, retry, generated summary, service-specific rule, schema rewrite or automatic approval. The 0.5.9 current-result preservation, source archive, exact approval and UNKNOWN protections remain. Limits and dependencies are unchanged. Extremely large protected exchanges can still exceed the hard allowance even with no optional tools; this change does not claim unlimited context.

Diagnostics record protected-message, schema and continuation byte counts. They do not record continuation contents. These counts make future capacity failures distinguishable without weakening export privacy.

## Verification boundary

The public tests cover crowded catalogs, continuation data, UTF-8 schemas, both protocols, serialization/restart, token and wire limits, deferred-tool inspection, exact schema/account bindings and explicit hard-limit rejection. A local mock HTTP server receives the complete constructed request. Document-only and document/email component flows use the shared planner, including native discovery and activation of a deferred action. They retain zero-note source text, approval, read-back and duplicate-write checks.

Private reconstruction uses the exact exported visible messages/results and synthetic missing registry/continuation data. It also reruns the earlier correction/source-read retention checks, the premature-final-response rejection and the earlier successful Markdown arguments. See `../qa/0.5.10/VALIDATION.md` for measured results and limits.

These tests cannot establish GPT OSS's next choice, research quality or physical-phone completion. No live model or connected-service write was performed. The APK remains a candidate until the original document-only workflow succeeds on the phone.
