# Action review — 0.4.21

Chat previously placed the entire approval card among the fixed controls below its weighted transcript. A long title, account label, description, or enlarged text could consume the available height and clip the last row containing Approve and Decline. Scrolling the argument box could not reveal the rest of the card. There was no intentional background-tap approval handler.

New chat and character proposals now open a full-screen review. Account, action, and all arguments share one scrollable details region. Approve and Decline occupy a separate fixed footer. The keyboard is dismissed when the review opens. Close and Android Back hide the review but leave the exact action pending; the compact Review action card reopens it. Tapping details does not decide the action. Task lists open the same review explicitly, avoiding multiple automatic dialogs for multiple pending tasks.

The UI calls the existing approve/deny controller only from the respective decision button. Approval binding, durable submission, service ownership checks, and UNKNOWN/no-replay behavior are unchanged. No old operation is retried by installing the update. Model choice, tool discovery, action selection, error summaries, and report sanitization are unchanged.

The user's screenshot shows a proposed Google Docs table operation followed by a generic service-failure summary. It does not show the review at the time of the tap, the model name, the submitted arguments, or the underlying error. The report for that exact conversation is needed to determine whether an approval event was recorded and why the action failed. The earlier report from a different run cannot establish those facts.

Regression coverage includes a 320 × 320 dp review viewport at 200% font scale with long arguments, complete footer visibility before/after scrolling, detail taps, Close, Android Back, reopening, changed proposal identity, and Compose saved-state restoration. These are local Android UI simulations, not live provider/service tests or physical-phone validation.

On the phone: install over the existing app, open a pending action from Chat/Character or Tasks, scroll the details, and use Close to leave it pending. Approve only when the displayed action matches the task. For a failure, open that conversation and choose Report → Save report; share the JSON for diagnosis.
