# Saved research evidence (0.5.5)

> Historical 0.5.5 design. In 0.5.7, mandatory finding/citation gates and automatic collection/stalled-run stops are retired; cached reads again return content and canonical context includes fetched text. The current contract and compatibility limits are in [Research execution simplification](RESEARCH-EXECUTION-SIMPLIFICATION.md).

KAI now stores phone-produced public web receipts separately from model conversation text. Each source has a run-local ID, URL, observation time and captured excerpt. Search snippets, extracted page text and unread navigation links have different meanings. A page receipt proves only that the shown excerpt was returned, not that KAI reviewed every part of the page.

The native and JSON agent protocols expose `record_findings`: one to twelve model-authored claims with exact quotations from those excerpts. The phone rejects unknown source IDs and quotations absent from the source. A batch commits atomically. Quote matching establishes attribution; it does not establish entailment, truth, complete coverage, freshness or independent corroboration. A later page request, including a failure through a known redirect alias, makes older findings from that source ineligible for the citation check.

## Persistence and retrieval

Receipts commit in the encrypted run record before a web result reaches the model. Findings survive provider failure, route changes, worker restarts and approval pauses. `read_context` supports `research` (source index), `findings`, and `source:ID`, with 4,000-character pages and `nextOffset`. A compact current index stays in goal progress. Source results and quotes remain untrusted data, never instructions, personal memory or action permission.

The run panel shows source counts and the latest four quoted findings, including after a research run completes. Action review shows the counts and attribution limitation. The chat diagnostic export includes the evidence, so treat it as private. Temporary runs are excluded from serialized state. Forget/context invalidation clears research along with other run content; conversation deletion removes the run. Imported runs cannot supply new phone retrieval authority.

Old archives remain available as historical text. This version does not manufacture source receipts for earlier archives. New research sessions restore their cache only from the separate receipt ledger, including failed reads. Duplicate cached requests retain the original observation time. There is no automatic refresh or freshness certification. Existing web request limits and inference allowances still apply.

## Proposal checks

For an explicit public research request, a new Google Docs body and a final plain research answer need a current quoted finding and at least one citation to that finding's URL. New reference URLs must come from this run's retrieval/navigation receipts, the user's request, or service result links bound to the same execution. HTTP, private and malformed reference URLs are rejected. Unread navigation links may identify a project, but cannot supply a quotation or meet the supporting-source requirement by themselves.

This is a **minimal citation floor**, not a per-sentence verifier. A document containing one valid attributed citation may still contain unsupported statements. Research for other services, full claim coverage, semantic entailment, conflicting-source resolution and source independence remain unfinished. KAI-028 and KAI-032 remain partial.

The shared controller allows two model corrections across resumes; a third unresolved proposal saves a partial outcome. Existing approved or uncertain effects are not replayed. Citation review does not insert text, rewrite arguments or transfer approval to changed arguments. The proposed body is saved before review, and every external write still requires its exact approval. Explicit **Answer from findings** performs no new tools or required attribution correction; it checks link provenance and may return a partial result. Legacy unattributed archives may need fresh research to satisfy a new document proposal.

Limits are 128 retrieval receipts, 96 findings and 900,000 evidence characters per run, within the existing combined run-storage limit. Reaching a limit preserves prior evidence. Search snippets have at most 500 characters and page excerpts at most 6,000 (less in small-context mode). The system does not infer complete coverage from these caps.

## Phone checks

1. Install 0.5.5 over the current preview. Start a small new research request and inspect **View progress** for page/search labels, claims, exact quotes and source URLs.
2. Pause or restart before approving a document. Continue the saved run and confirm the source IDs/quotes remain and previously fetched URLs are served from the cache.
3. Review the exact document body, recipient/account and source URLs before approval. Read-back text matching remains separate from factual/source verification.
4. Forget the chat context or delete a disposable test conversation. Confirm its research cannot reappear through Continue, imports or old callbacks.

JVM fixtures use fake web/provider/repository responses. Android instrumentation covers encrypted restart and invalidation but requires a connected device to execute. Neither a compiled test APK nor the older user-supplied report proves this version's physical-phone behavior.
