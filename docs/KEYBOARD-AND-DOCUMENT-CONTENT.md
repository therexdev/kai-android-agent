# Keyboard and document-body fixes — kai 0.4.24

## Report findings

The supplied 0.4.23 Android 13 report contains 84 recorded events, no dropped events and no trace write failures. Gemma 3 27B first guessed a `create_doc` action on the Google Drive connection. Recovery supplied real catalog schemas. The model then selected `GOOGLEDRIVE_CREATE_FILE` with a name and native-document MIME type, but no uploaded body. The service accepted the approved creation and returned a real Google Docs URL. The model replied with that URL without writing the requested body. The report contains no document-content write.

On the follow-up, Gemma emitted prose, an action JSON code block and an approval question in one response. The completed-response parser treated a response beginning with prose as an ordinary reply, so it displayed the embedded action rather than rejecting its format. The subsequent user message `yes` did not approve an actual task, and the model's `Updating the document now` reply had no execution behind it. The only task in this report is the accepted empty-document creation. There was no timeout in this run.

The screenshot also shows the editor obscured with the keyboard open. The chat layout retained a fixed character panel and other controls in a reduced viewport, and applied IME padding below unconsumed Scaffold padding.

## Changes

- Apply keyboard insets once at the Scaffold boundary and consume Scaffold padding in the content. Hide bottom navigation while the IME is visible. In chat, a keyboard/short-viewport layout collapses the character stage, provider row, optional attachment buttons and tagline. The weighted transcript can shrink while the editor remains at the bottom. Draft state is retained across resizing and keyboard closure. Selected attachments still remain available.
- Reject action/discovery JSON embedded in prose as a recoverable protocol error. Never extract and submit an embedded object. Require a fresh complete model response, then apply discovery, schema, target-evidence and exact-approval checks. Ordinary prose and explicit JSON `reply` messages explaining code remain supported.
- For narrowly recognized literal document-body requests, check the current conversation's scoped task ledger before allowing a final reply. An accepted metadata-only creation is not evidence of a text write. Supply up to two remaining-step corrections; if the model still cannot proceed, show a factual partial-completion message instead of its unsupported progress claim. Specific missing-detail questions remain possible. While this check is active, delay spoken/display reply preview until validation.
- Prevent a correction from creating the same document again or repeating an already accepted text insertion. UNKNOWN/RUNNING outcomes pause further writes. Acceptance is still distinct from independently verified saved contents; this change does not fabricate a successful readback.
- Permit a real Google Docs URL from a successful/accepted Drive receipt to establish the document target for Docs only when both active connections have the same provider-verified `google_profile` email and owner. Display aliases, different identities, inactive accounts and generic Drive file IDs do not qualify. Conversation, privacy-boundary and connection-generation checks remain in place. This passes resource evidence only; account selection and separate action approval are still required.
- Validate Drive `fileId` fields as well as Docs document IDs against observed or user-supplied evidence.

## Scope and validation

The literal-content completion check covers the reported create-and-fill request and quoted `write ... in that Google doc` follow-ups. It is not a general semantic verifier for every possible task or language. Existing saved-task protocol parsing and document-target checks share the relevant fixes; the literal-content completion guard is in the interactive chat loop.

Regression tests exercise a dispatched Android IME inset, compact and large-text layouts, draft retention, wrapped action rejection, empty creation versus body-write receipts, bounded correction, duplicate/unknown outcomes, scope/privacy boundaries and verified Drive-to-Docs resource evidence. See `qa/0.4.24/VALIDATION.md` for final results. Robolectric UI tests are Android simulations; they are not a physical Samsung keyboard test. No live model or Google account action is executed during development.

## Retest

Install over the current app without uninstalling. No new desktop update is required by this phone patch. In the existing conversation, ask to write the exact quoted text into the already-created Google document. Review the actual action card; an ordinary chat `yes` is not a substitute for its Approve button. Check the document and export **Report → Save report**. Also verify that the editor and typed text remain visible when the keyboard opens and closes.
