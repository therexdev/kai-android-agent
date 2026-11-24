# KAI Android 0.5.5 test update

This update adds persistent source receipts and quoted research findings to chat and saved tasks. It distinguishes search snippets from page excerpts and unread navigation links, restores failed-read caches after restart, and makes retained evidence available through `read_context` and the progress panel.

Researched Google Docs bodies and final plain research answers have a bounded citation check before new proposals. It rejects invented/invalid reference links and requires a current quoted finding with a cited source URL. Exact quotation matching proves attribution only; full coverage and factual verification remain unfinished. Review the document's facts and sources before approving it.

Install over the existing preview; version code is 45, SQLCipher schema remains 3 and the original signing identity is retained. No server update or paid native-route probe is required. Validation results and physical-device limitations are recorded in `VALIDATION.md` and `DEVICE_STATUS.json`. Follow `docs/RESEARCH-EVIDENCE.md` for a small phone check.
