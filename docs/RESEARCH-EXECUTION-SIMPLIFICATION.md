# Research execution simplification (0.5.7)

The 0.5.6 regression happened before document creation, not in the added email step. The retained report contained all 207 trace events and no action proposal. One invalid quote could reject an entire findings batch. Canonical context then retained source metadata but no page body when zero findings had been saved. Cached reads returned pointers with an ambiguous slash-combined address; repeated failed lookups triggered the new collection/stalled-run policies. The content was stored, but the working context and control rules obstructed its use. A later answer-only native request independently omitted `native_protocol: 2` because it offered no tools.

## Correction at the owning boundaries

| Boundary | Current behavior |
| --- | --- |
| Research prerequisite | No mandatory quote ledger or citation rejection phase before drafting, Docs proposals or final research answers. Prompts request citations; this is not a factual verifier. |
| Stored research | Phone-produced retrieval receipts commit before being returned. Source type, URL, observation time, truncation and original text remain available. |
| Working context | Canonical projection includes fetched text with zero findings, prioritizing recent page excerpts. It is capped at 9,000 UTF-8 bytes with explicit omitted counts. It is not the full evidence store. |
| Retrieval | Each source advertises an exact `entryId`, such as `source:s1_0`. `research` lists all sources. Source reads return plain excerpt pages of up to 4,000 characters and `nextOffset`, with metadata outside the excerpt. Cached web reads return the retained result without another network request or new receipt. |
| Execution | Chat and saved-task/routine adapters no longer observe heuristic progress, reserve a completion phase, close collection, or stop after six repeated/failed results. Existing network, run, inference and per-step limits still bound execution. |
| Native transport | Managed native requests carry protocol 2 even when the current request offers no tools but retains native history. |
| External effects | Account/schema checks, exact approval, durable intent-before-submit, UNKNOWN protection, duplicate-write rejection and document text read-back remain independent. A source quote never grants approval. |

This removes policy rather than expanding allowances or adding retries. The older advisory research-budget text still reports repeated retrievals; it cannot close collection or stop the driver. Search and page-read network availability remain independent. When a network allowance is exhausted, original sources are still accessible through `read_context`; JSON cache hits also remain readable without network admission.

## Compatibility and retained limits

SQLCipher schema 3, package and signer are unchanged. Old findings and progress records still decode strictly. `RunProgress` now exists only to retain 0.5.6 data: its old counters/closure flags have no admission authority. Historical `NO_PROGRESS` records retain their outcome until the user resumes them. The JSON parser and legacy finding handler remain compatible with old stored conversations; neither prompt advertises `record_findings`, and the native registry no longer offers it. No migration manufactures or deletes notes. Forget, deletion, temporary-run exclusion and import authority boundaries remain in force.

A page is still an extracted excerpt, not proof of full-page review. Receipt/quote storage limits remain unchanged. Removing the citation floor means the phone no longer rejects a researched draft solely because it lacks a recorded quote or cites a URL outside the prior citation gate. The user reviews the proposed content; automatic claim entailment, completeness and freshness assurance are unfinished. Public retrieval URL restrictions and exact effect authority are separate and retained.

## Evidence and limits

The failing report is replayed privately against compiled classes. Its directory page now appears in full in canonical context with zero findings, and every stored source can be recovered by exact paged retrieval. The diagnostic receipt strings are reconstructed for an offline fixture; the replay is not a production-state import or a new model execution.

Public component scenarios use scripted model proposals and simulated web/Composio transports. Both document-only and document-plus-email sequences cross actual parsing, context projection, run persistence, exact approval, direct-client dispatch, receipt, read-back and duplicate-write contracts. Each runs across chat/task/routine origins, JSON/native protocols and fresh/resumed states (24 combinations total). They force catalog compaction, retain zero findings, verify all source pages, create exactly one document, read it back, and use its returned link in exactly one approved email where requested. The tests manually compose shared components; setting a run origin does not execute the corresponding Android adapter. A separate transport test checks native history with no offered tools.

The full JVM suite, lint, release build and instrumentation assembly results are recorded in `qa/0.5.7`. Passing these checks does not certify physical-phone lifecycle, the live provider's choice of actions, live service behavior, research quality or email delivery. Original-request phone validation is required before claiming the regression is resolved in use. No production service update, paid model call or real connected-service write is part of this implementation.
