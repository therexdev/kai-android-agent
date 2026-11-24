# Shared run inference allowances (0.5.4)

The phone persists an admission before each chat or saved-task model request, including planning, context summaries, recovery attempts, fallback attempts and explicit answer-from-findings finalization. Request IDs connect the ledger to diagnostics. No prompts, keys, account identifiers or provider continuation are stored in the ledger; route identity is hashed. Aggregate counters survive the bounded 32-entry attempt history.

| Allowance per new/continued run | Chat | Saved task/routine |
| --- | --- | --- |
| Model requests, including helpers | 80 | 400 |
| Input allowance | 2,000,000 | 8,000,000 |
| Cumulative active model time | 15 minutes | 60 minutes |
| Output allowance | 131,072 | 524,288 |

Existing model-step, provider-output, research and worker limits still apply and may stop a run earlier. Input admission uses UTF-8 wire bytes, schemas and bounded message/image overhead as a conservative estimate, not an exact tokenizer. Output admission reserves the configured maximum. Valid provider usage replaces that request's token reservation. Missing, invalid or failed usage retains the reservation; failures do not become free retries. An ordinary cancellation retains token/cost uncertainty and records elapsed model time. A process death retains the entire admitted model-time reservation. Active model time excludes user approval waits, external tool IO and retry backoff; it is not an end-to-end wall-clock deadline.

Account offers an additional estimated-credit allowance of 1, 5, 10 or 25 credits, disabled by default. Admission estimates input/output at the catalog rates and reserves the result in integer microcredits, rounding upward. Prices must match the model and be no more than 24 hours old. A missing, malformed, future or stale price blocks a capped request before transport. Refresh Account to obtain a new catalog observation. Selecting an old cached model does not refresh its price timestamp. Fallback models use their own catalog rate and retain the original wallet/free-or-paid policy.

These are catalog estimates, including on free-eligible routes, not settlement receipts or a guaranteed monetary cap. Reported usage can exceed an estimate; it is recorded and prevents further admission when exhausted. The current gateway stream does not expose final settled credit charges. Unpriced attempts are counted explicitly, and older releases' incomplete accounting is labelled rather than reconstructed as exact billing.

Reaching a limit preserves progress as partial work. Explicit Continue authorizes another bounded allowance when the run is budget-blocked. Worker restarts and exact approval resumes never grant more allowance. Answer from findings authorizes just one model request, at most 2,048 output tokens and six minutes of model time, with no new tools or remote summary. An already approved effect can settle without another model request; pending UNKNOWN actions still require reconciliation. All exact approval bindings and durable intent-before-submit rules remain in force.

KAI-016/018 remain partial: the ledger covers the chat/saved-task model engine. Background memory extraction, complete tool/network wall-clock accounting, authoritative gateway settlement and cross-device/child-run budgets remain unfinished.

## Phone checks

1. Install over the existing preview, refresh Account, and select a small estimated allowance. Verify the selected free/purchased-credit policy remains unchanged.
2. Run a bounded chat and saved task; inspect requests, summaries, estimated cost and unknown-usage counts. When blocked, verify progress survives restart and Continue is explicit.
3. Pause an active request and force-stop/reopen during a separate request. Confirm retained usage does not disappear and no external write replays.
4. Approve a prepared action at an exhausted model allowance. Confirm the exact action settles once and further model work remains paused. UNKNOWN requires checking the service.
5. Use Answer from findings and confirm one final model request with no new search, tool or summary call.
6. Create a small document containing a horizontal rule and email its link. Review actual recipient, subject/body and URL before approval. Confirm readback and email receipt separately; accepted mail is not independent delivery proof.

These checks require a connected physical device. Compiling the instrumentation APK is not execution evidence.
