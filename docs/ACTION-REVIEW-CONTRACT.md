# Action preparation and reply contract (0.5.8)

## Observed failure

The complete latest 0.5.7 report shows six successful model requests and no action proposal. The original document-only request, model, create schema, context/output allowances and fetched directory excerpt match the earlier 0.5.2 document flow. The final canonical context retains the complete excerpt and a reconstructed native registry offers the create function. The model nevertheless ends with a request for preliminary confirmation, without a body or phone approval card.

The native parser accepts that response as an ordinary answer. The existing document guard checks completion claims, so readiness prose bypasses it. The run then ends as partial and displays the prose. This gap already existed in the earlier code; the evidence does not isolate the model, provider or prompt change responsible for choosing that ending. The earlier successful document is real evidence of prior capability, not proof that its entire run completed correctly.

## Owning boundary

An action function describes preparation, not permission to execute. `AgentProtocol` supplies this contract to each prompt, `AgentHarness` describes every write function accordingly, and `GoalProgress` refreshes it even for persisted steps. A complete proposal includes the actual account, target and content. The phone can then ask for exact approval. Conversational assent never approves an unprepared action.

`GoalVerifier.validateReply` checks the existing outcome type against the outstanding goal. Chat and saved-task/routine execution invoke it inside `AgentHarness.parse`, before `AgentResponseRecovery` resets its consecutive failure count. When an effect remains unaccepted, ordinary `ANSWER` is rejected. The model can propose the next tool, return `needs_input` for a specific missing required fact, or report useful partial results/an actual blocker using `partial` or `blocked`.

This replaces the invalid ordinary-answer exit with the existing protocol correction. There is no phrase classifier, new retry counter or new persistence field. The same two-consecutive/six-total correction limits apply; rejection does not authorize a write, grow the output allowance, or replay a service request. Genuine plain-text questions in an outstanding action flow now need the existing explicit question form; ordinary conversation, accepted work and explicit answer-only continuation retain plain text.

Saved tasks use the current step's inferred goal for intermediate steps and the whole run goal at the final step. Research can finish without an external effect; a document step requires its body, and the final combined document/email goal still needs both effects. This reuses the existing goal inference and its language/intent limitations. It is not a new semantic planner.

Native correction is now selected in `AgentResponseRecovery` itself. The removed adapter wrapper used to discard everything before “This is a format correction,” including output-limit and resource-reuse guidance. Both protocols now retain the reason and binding receipts while using the appropriate next-response format.

## Preserved authority and compatibility

The action arguments, account/schema/version bindings, exact approval, durable intent-before-submit, document body checks, source projection, effect receipts, UNKNOWN non-replay and run/inference budgets are unchanged. SQLCipher schema 3 and installed signing identity are retained. Read-only tool behavior is unchanged. No deployment, live service call or paid inference is part of this update.

The saved-task parser's early `needs_input` shortcut is removed so questions use the same full-response validation as other replies. For Android fixture isolation, the existing inference/account clients can be supplied through the ViewModel constructor; production defaults are unchanged. The document adapter fixtures now seed the current managed-account configuration and direct fixture HTTP to a local server rather than relying on retired provider settings.

## Remaining boundary

Typed outcomes are not semantic truth: a model could still label an unnecessary confirmation as `needs_input` or incorrectly claim a blocker. This change cannot guarantee a useful proposal, research quality, correct factual coverage or live completion. Exhausted correction allowance stops safely with retained progress. The original request must still pass on the phone with the actual model, first document-only and then with email added. See `../qa/0.5.8/VALIDATION.md` for measured and unexecuted checks.
