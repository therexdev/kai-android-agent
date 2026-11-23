# Local inference timeout — kai 0.4.23

## Recorded failure

The 0.4.22 phone report from November 20, 2026 contains 32 trace events, zero dropped events, zero trace write failures, and no task/action ledger entries for this conversation. The selected model was `gemma3-27b`, routed locally through Koinos AI. The user requested a Google document named `test`, containing `test doc`, and its link.

- The authenticated catalog advertised an automatic 131,072-token limit; expanded agent context was enabled.
- First inference: estimated input 2,830 tokens, context request 5,390, HTTP 200, response in 53,011 ms. The server reported 1,932 input tokens and an active 8,192-token allocation.
- The response guessed `create_doc` on a YouTube account. The phone rejected that undiscovered action. No action review or service execution followed.
- Recovery searched the YouTube tool catalog. Empty semantic search triggered catalog fallback, examining 67 entries. The model received metadata for 20 unrelated tools plus several schemas.
- Second inference: estimated input 7,963 tokens, context request 10,523, zero omitted/compacted messages. It failed after 90,343 ms with no recorded HTTP response or partial output, matching the client's 90-second read timeout. No service POST appears in this trace; only catalog GETs were dispatched.

This is evidence of both a model selection error and inadequate software recovery/timeout behavior. It is not a context-overflow rejection. The phone report does not establish whether the desktop was evaluating a prompt, loading/resizing a model, queued, stalled, or whether a network/relay delay prevented a response. The second request's active allocation and actual token count were never reported.

## Changes

1. Koinos generation keeps the 20-second connection limit, allows 300 seconds between incoming network reads, and has a 360-second total call limit. OpenAI generation remains at 90/180 seconds. Model-catalog checks remain at 10 seconds. No automatic inference retry was added. Cancellation still closes the HTTP call.
2. Saved-task inference uses the same provider-aware total allowance plus 20 seconds for discovery/cleanup, rather than an earlier 120-second cutoff, capped by the remaining worker time budget. Existing task checkpoints, approvals and UNKNOWN/no-replay rules remain unchanged.
3. Generation diagnostics record configured limits, request-body completion, response headers and first generated content with elapsed times. A transport error records its phase, whether headers/content arrived, and exception class without raw exception text. Timeout messages distinguish connection establishment, waiting for the model, and waiting for the remaining response.
4. Recovery from a guessed action filters by requested object, not merely a shared verb such as `create`. If the examined catalog has no object match, the correction includes the selected service, active account choices, incomplete-catalog status and a request to perform specific discovery. It does not feed unrelated schemas back to the model. It never substitutes an account or executes a different tool. Exact tool inspection and explicit discovery remain available.

Full relevant tool schemas and capacity-based context sizing from 0.4.22 remain. This update does not increase the model's intelligence or prove that a larger wait will resolve a desktop stall.

## Regression checks

Ten new JVM cases cover production timeout-policy selection, saved-task allowance, delayed headers, timeout before headers, headers followed by a stalled body, a partially generated stream, a total deadline despite incoming bytes, cancellation during the longer wait, the captured unrelated YouTube catalog, relevant schema retention/account boundaries, and verb-only guessed actions. Some cases exercise multiple assertions/scenarios. The slow-network tests use scaled millisecond deadlines against a local mock server; they do not simulate actual Gemma compute performance.

See `qa/0.4.23/VALIDATION.md` for final build/test counts and limitations. No live inference or connected-service write was performed during development.

## Phone comparison

Install 0.4.23 over the current app without uninstalling. No new Core/Remote Access update is required by this Android patch. Keep the desktop online, keep the same model selected, and start a fresh conversation with the same request. Allow the request to finish or reach its bounded timeout; Stop remains available. Review any proposed action and export **Report → Save report**, including when it succeeds. The new report will show whether time was spent before response headers, before the first content, or during generation.
