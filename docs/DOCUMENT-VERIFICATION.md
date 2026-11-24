# Document body verification

KAI keeps an accepted service write and a later text verification separate. A write is never upgraded by model prose, a link, metadata, or a user saying it worked. The original receipt and action approval remain unchanged.

The supported Composio Google Docs adapter compares create/whole-document Markdown body text, literal creation text, an inserted passage, or one body insertText batch request with a successful later GET_DOCUMENT_PLAINTEXT or GET_DOCUMENT/GET_DOCUMENT_BY_ID. The read must be dispatched after the accepted-write receipt and belong to the same execution, conversation, owner, connection generation and account. The requested and returned document IDs must agree. Creation IDs come from that write's own receipt, not an unrelated creation. Legacy tasks without a run identity stay unverified.

Full-document writes require full normalized visible-text equality. Insertions require the supplied passage to be present. Unicode normalization and whitespace differences are allowed; wording, case, punctuation and numeric values remain significant. Markdown headings/emphasis and link labels are rendered as text. Optional list glyphs are matched only where the approved Markdown has list items. Escaped line separators used by the supported Markdown action are handled without changing approved arguments. From 0.5.4, the observed 50-underscore thematic-rule rendering is optional only at an actual Markdown thematic-break node; underscores elsewhere are never discarded.

Only documented body fields and known response wrappers are read. Arbitrary JSON strings, request echoes, titles, comments and metadata cannot establish body verification. Truncated or malformed output, unsupported tabs/ranges, code/HTML/images/tables and unrecognized operations remain unverified. The newest dispatched read supersedes earlier evidence, even if the newest read fails or is incomplete. A content mismatch reports partial work and does not prepare or submit a corrective write.

The shared progress projection supplies TEXT_MATCHED, TEXT_DIFFERS or UNVERIFIED, the confirming read ID and observation time. Chat and saved-task completion use the same evaluator. A document_text_verified journal event links the write and read tasks; repeat evaluation does not duplicate it. Evidence is recomputed from encrypted task records, so removing readback data removes current verification. A historic journal event records an observation, not permanent proof that a document is unchanged.

This verifies visible body text at one observation time. Formatting, hyperlink destinations, source truth, access rights and delivery are separate claims. Large or unsupported documents may still be correctly written while KAI conservatively reports acceptance without verification.

## Phone check

Install 0.5.4 over the existing app. Start a new small research-to-document request, review the exact creation action once, then allow the later read. Confirm that the final link opens the expected document and the run finishes without repeated create/insert proposals. Repeat with a literal body request. A failed read should preserve the link and report the verification limit; never retry an UNKNOWN write automatically. Saved-task execution and reopening a retained run remain physical-device checks.
