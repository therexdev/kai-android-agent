# Shared progress control (0.5.6)

> Historical 0.5.6 design. In 0.5.7, mandatory finding/citation gates and automatic collection/stalled-run stops are retired; cached reads again return content and canonical context includes fetched text. The current contract and compatibility limits are in [Research execution simplification](RESEARCH-EXECUTION-SIMPLIFICATION.md).

## Report evidence and root causes

The supplied Android 13 / 0.5.5 report records 39 admitted model requests, including four summaries, 24 saved findings, no saved draft and no action proposal. Its retained tail includes repeated cached page reads and denied searches. The final inference request received an upstream HTTP 502. Earlier trace entries were dropped under the report-size limit, so the retained tail is not a complete execution history.

The original document-and-email request was still in the actual model inputs. This was not simply a lost user instruction. The app had the necessary data structures but did not consistently enforce progress between them:

| Boundary | Observed implementation defect | Shared correction |
| --- | --- | --- |
| Tool admission | Search stayed registered until both search and page-read limits were exhausted. Native parsing accepted any built-in name, even if omitted from that request. | Separate search/read availability, validate the current native registry, and enforce the same admission on JSON proposals. Show current capabilities in canonical goal progress. |
| Progress ownership | Repeat keys included the preceding result text, including changing budget counters. State reset with adapter recreation. | Persist normalized operation/outcome signatures and the no-progress streak in the execution run, shared by chat, tasks and routines. Writes retain their separate intent journal. |
| Service-result progression | Chat could label an explicitly failed service response as progress; saved-task settlement used a different condition. This was found during caller review, not demonstrated by the supplied report. | Both adapters feed committed task status to one receipt handler. Success/acceptance advances; failure consumes the shared streak; duplicates and UNKNOWN states cannot reset it. |
| Research-to-output transition | Search/reading could consume the allowance needed to draft, review and finish. | Close collection after repeated unchanged research or when the remaining run allowance needs reserving for completion. Keep archive retrieval, findings, discovery and reviewed connected actions available. |
| Context projection | Cached pages added full duplicate copies; only four recent claims were pinned, while a separate older excerpt index duplicated research context. Summaries could consume requests without being usable. | Cached reads return bounded pointers. Keep a byte-bounded canonical claim/source projection, preserve originals for paging, and use deterministic projection without automatic summaries when canonical run progress is available. Reuse a fitting projection without repeatedly announcing compaction. |
| Source interpretation | A hard-coded topic keyword rule labeled a successfully retrieved relevant tool page off-topic. | Retrieval reports transport/extraction outcomes. It does not decide semantic topic relevance from a word list or special domain. Quote provenance remains a separate shared check. |
| Finding identity | A longer quote for the same source and exact claim could create a second finding. | Preserve the first attribution for the same source and whitespace-normalized claim. Different sources/claims remain separate; no semantic entity merge is inferred. |
| Diagnostic boundary | An HTML gateway error page could carry network details into a report. | Omit HTML error-page bodies before diagnostic persistence/export; retain HTTP status and request metadata. Structured JSON errors still receive normal credential filtering. |

The HTTP 502 is a separate upstream availability observation. These changes do not claim to repair its server-side cause. They reduce avoidable work, preserve evidence and keep external failure distinct from app-side no-progress. No production server change or paid supplier probe is part of this update.

## Runtime contract

`RunProgress` retains up to 256 hashed operation/outcome signatures and a bounded consecutive repeated/failed-result counter. Web signatures exclude moving budget, index and cache wrapper text. Archive/file/discovery outcomes compare canonical results. Failed or denied reads do not become progress merely because a query changes. Pagination and genuinely different returned data remain available. This measures reuse/failure, not semantic novelty or factual correctness.

The third identical web outcome, or a web result reaching three consecutive repeated/failed outcomes, closes further collection for that run. Six consecutive repeated/failed local read/discovery results stop the shared driver with a saved partial `NO_PROGRESS` outcome. Native and JSON proposals share these boundaries. Each underlying network action still has its existing bounded transport behavior.

When research exists, collection also closes with eight or fewer model steps remaining, four or fewer request admissions remaining, or 4,096 or fewer output tokens remaining. These are reserves, not a completion guarantee; corrections, provider errors and review can still leave partial work. Limits are not raised automatically. The saved-task per-step limit remains separate.

Collection closure survives process/worker restart and model changes. Explicit continuation resets the stalled counter, retains signatures and closure, and only extends inference allowance under the existing authorization rules. It continues synthesis rather than silently restarting collection. Additional research can be requested as new work. A committed successful/accepted service read/effect receipt advances progress; failed receipts consume the shared no-progress allowance. UNKNOWN and unsubmitted states never reset it, and duplicate callbacks do not count again. Already approved effects settle before new-model/no-progress admission, while UNKNOWN outcomes remain unreplayed.

A proposal, source quote, summary or progress transition never authorizes a write. Exact argument/schema/account bindings remain in the existing action journal. Connected read results are observations, not verified truth; completion and document read-back remain independently checked.

## State, compatibility and evidence

Progress is serialized alongside source evidence in the encrypted execution record. Missing progress in older records initializes an empty ledger; malformed/future progress does not silently reset data. Forget clears it, deleted conversations remove it and temporary executions remain excluded from serialized state. Imports cannot create trusted execution authority. Original source excerpts, quote IDs and action receipts are preserved.

Canonical findings are not a complete coverage verifier. The projection is capped at 9,000 UTF-8 bytes with explicit omitted counts and archive pointers. Existing 0.5.5 findings are not rewritten or merged on upgrade; their projection avoids duplicate identical claims, and new attributions use the shared identity rule. Historical off-topic receipts retain their historical status; new retrievals no longer use that keyword gate.

Regression coverage spans both wire protocols and all three run origins, unchanged discovery/schema/archive/file outcomes, alternating cached reads, changing budget text, independent tool admission, pagination, explicit continuation, service-receipt settlement, encrypted restart/privacy, full canonical claim retention and diagnostic filtering. Private report replay is an offline structural check; synthetic public fixtures exclude account identifiers and report text. Physical-device and live-provider behavior remain separate gates.

For future fixes, follow the report-to-contract-to-callers-to-regression sequence in `AGENTS.md`. Record what the test proves and what remains unverified before declaring a cross-cutting issue closed.
