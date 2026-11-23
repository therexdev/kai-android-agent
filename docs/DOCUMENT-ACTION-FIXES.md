# Document actions — 0.4.22

The reported 0.4.21 run on gemma3-27b retrieved ten Google Docs tools, including Create a document, but provider-based compact discovery exposed only three edit tools. Exact-word ranking favored `doc` in the table tool over `document` in the creation tool. The model then proposed a table edit with an empty document ID twice and finally substituted `Please provide the document ID.`. String validation accepted that placeholder, and the approved request failed with the service's 404. The model had sufficient context; no request messages were dropped.

## Changes

- Agent context now uses the authenticated model's reported limit. Koinos limits of at least 16,384 tokens use the expanded prompt and catalog; smaller or unreported limits retain the conservative path. Catalog results remain bounded, and model request budgeting remains authoritative. Existing OpenAI behavior is preserved.
- Normalize doc/docs/documents to document and prefer the exact requested action name. The regression catalog now ranks Create a document first for create doc and includes all ten tools for the reported model. Expanded discovery includes schemas within its budget; inspection preserves the selected tool's complete descriptions and schema constraints. Catalog examples remain omitted to avoid treating example identifiers as real targets.
- Document ID fields on Google Docs/Drive actions require an exact user-supplied ID/link or an ID returned by a successful/accepted execution for the selected account. Model explanations, catalog examples, failed/unknown operations, other accounts, forgotten context and unrelated conversations do not establish a target. Grounding does not authorize execution; exact approval and ownership binding still apply. A new document's creation action does not require a preexisting ID.
- Repeated proposals with the same missing or invalid input stop after one correction. The correction explains prerequisites and the option to ask a question. No rejected proposal is submitted and no uncertain write is replayed.
- Returned document/file IDs and document links are retained as receipt evidence, including multiple search results, so subsequent reviewed steps can use them.
- Failed service receipts display a bounded, credential-redacted error message instead of only a generic failure. Diagnostics preserve malformed model outputs with trailing text, while still redacting credentials.
- Chat and saved tasks use the same capability and target checks. The 0.4.21 approval UI remains intact.

## Validation and limits

The fixture contains catalog definitions only, extracted from the reported failure; it contains no private account IDs, credentials or conversation history. Tests exercise capability discovery and the actual serialized HTTP prompt against a mock API, document creation ranking, full selected schemas, placeholder/unknown/account-mismatched targets, receipt reuse and privacy boundaries, bounded recovery, useful redacted errors and diagnostic fidelity. These are deterministic software tests, not a claim that a live model chose correctly or Google Docs succeeded. See `qa/0.4.22/VALIDATION.md` for the final test/build results.

Install the APK over the existing app, retain the same model for comparison, start a new conversation, and repeat the same document request. Check the exact action before approving it. Export Report > Save report afterward, including on success, to compare action selection, latency and service results. No desktop update, credential reset or data reset is required by this release.
