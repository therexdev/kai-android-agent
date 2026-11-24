# Android 0.5.1: research, table completion and approval presentation

The reported collection task created a spreadsheet but never submitted the values write. The retained trace used the legacy JSON protocol and contained five rejected model proposals, a refused website, repeated cached research requests, and compaction after tool discovery. Research stopped after three repeated results with only four search requests and three page requests consumed. The run was nevertheless labeled completed because it had an accepted file-creation receipt.

A second document-plus-email report created a document with an initial body, then failed with `MALFORMED_PROPOSAL` before preparing the requested email. The captured tail shows repeated research, tool rediscovery, and mixed/invalid legacy JSON despite the email action already having been discovered. Earlier trace entries were dropped by the bounded report recorder, so the report cannot establish complete research coverage or independent document readback.

The original reports remain private. Tests use synthetic practices, accounts, websites and spreadsheet identifiers.

## Changes

- Web request limits no longer remove connection tools or block archive retrieval. Search and page allowances are separate; duplicate/cached results request a new approach without closing every remaining source. Explicit multi-entry collection requests receive bounded allowances proportional to their size, under the same account and free/paid policy.
- Compaction retains a deterministic source-evidence index with original archive pointers and contact/review excerpts. Tool-schema discovery cannot displace every prior research result. Legacy approval continuation resolves old schemas on demand rather than fetching the whole old catalog.
- A current-run progress record remains visible at every context compaction level: accepted operations, body-text presence, returned resource links, missing effect kinds, and previously discovered tools. Both chat and saved task execution use it. Checkpoints retain unloaded tool references (within the existing 100-reference bound), current schema versions take precedence, and the visible index prioritizes the missing service step over unrelated catalog entries. It directs continuation toward the remaining service step while preserving exact approval and unknown-outcome rules. Format corrections explicitly reject trailing brackets/code/prose and request a fresh proposal for remaining work rather than restarting research.
- Creating a workbook does not prove that requested rows were written. Completion requires same-run, accepted, submitted values writes to the created workbook with sufficient row coverage. Header-only requests remain valid. Partial and uncertain writes cannot establish completion. This is a minimum content-evidence check, not independent verification of every cell or claim.
- Old completed chat runs with missing spreadsheet-content evidence reopen as partial, retaining their archives and receipts. No execution starts automatically. Continue requires an explicit user action; submitted or uncertain writes retain their existing replay protection. Forget still cancels old context rather than reopening it.
- Trusted internal reads do not appear as pending user approvals. Writes, including MCP actions, retain exact review. Review state uses the canonical intent fingerprint, so a refreshed display label cannot close or reopen the same review.
- Report is the last button in Settings. Conversation-history reports remain available.
- Website refusal is distinct from search-key failure. Published JSON-LD and visible header/footer contact details are retained as bounded, site-reported evidence. Scripts are not executed; sign-in and refused pages are not bypassed. Site-reported ratings and testimonials do not become independent review verification.

## Test scope

Regression coverage includes setup-only and header-only sheet actions, row coverage, batch and column-major values, different accounts/runs/workbooks, unknown and unsubmitted writes, old-run recovery and Forget, cached research and separate budgets, native tools after web closure, source compaction, read-only review presentation, and stable write-review dialogs. Continuation fixtures cover document-plus-email progress, accepted body versus title-only creation, unknown/unsubmitted/other-run receipts, and archive-preserving compaction. Page-reader fixtures cover footer contacts, JSON-LD, malformed/oversized scripts and untrusted review content.

Malformed or mixed model action text remains rejected. No external action from a rejected response is admitted. The change does not guarantee that a model will always produce valid proposals or that a website permits direct reading.

Final build evidence belongs in qa/0.5.1. Mocked tests are not a live connected-service acceptance test. Real-phone audio/lifecycle, actual Google Sheets readback and live provider/website behavior require separate validation.
