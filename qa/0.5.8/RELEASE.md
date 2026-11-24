# KAI Android 0.5.8 test release

This update corrects the action preparation/reply boundary exposed by the latest report. Write calls explicitly prepare a concrete approval card; ordinary readiness prose cannot finish an outstanding action. Missing facts and actual blockers use the existing outcome types. Both chat and saved-task/routine adapters use the same validation and existing correction limits. Native corrections retain their explanation.

Install over the preview app: version 0.5.8/code 48, package `com.kai.agent.preview`, original signing certificate and SQLCipher schema 3. Existing data, approved actions and uncertain-effect protections remain. No server deployment or new route qualification is required. See `VALIDATION.md` for measured evidence and limitations.

## Phone acceptance check

1. Confirm existing chats/tasks still open after upgrading. Keep the same GPT OSS 120B model and repeat the original document-only request in a fresh chat.
2. Expect a concrete phone review card containing the document title and body. Review it and approve once. Inspect the real document and returned link; distinguish service acceptance from successful content read-back.
3. Repeat with only the email step added. Expect the same document flow, then a separate exact recipient/subject/body review using the actual returned document link. Check the service result separately from delivery.
4. Repeat as a saved task; pause/reopen before approval. Verify the same content/target and retained sources survive, and an accepted or UNKNOWN write is never replayed.
5. Check a genuine missing recipient/account question still pauses for that specific information. Check ordinary conversation and Answer from findings still work without preparing an action.

Export a fresh report at the first unexpected question, missing card or failed write. This candidate has not yet passed the physical-phone/live-model gate; do not infer that result from scripted fixtures or replay.
