# kai Android 0.4.19 validation

Version code 26; ARM64 Android 9+ installer; original signing identity retained.

## Change

The screenshot reaches the phone's discovered-tool guard, rather than showing an input-window or HTTP rejection. The exact generated identifiers are not available, so the fix covers skipped discovery, guessed tool names, incorrect/inactive account IDs and invalid schema fields.

Conversation and saved-task runners reject those proposals before preparation, perform bounded catalog reads where appropriate, and require a fresh model response. No fuzzy tool substitution or guessed-account mapping occurs. Rejected arguments are discarded. Three corrections exhaust recovery; at most one automatic catalog lookup per account is allowed. Exact review, ownership/schema validation, durable submission and UNKNOWN-outcome rules remain binding. Original instructions and receipts stay in context; cancellation propagates. No Core/Remote Access change is included.

## Completed verification

- 351 JVM tests passed with zero failures, errors or skips. Ten new recovery tests cover metadata lookup, exact IDs, account isolation, invalid arguments, cancellation, catalog denial, empty results, bounded attempts and preserved UNKNOWN context. Existing parser, approval, submission and small-context tests passed.
- Android lint: zero errors and 13 warnings in existing dependency/style/ABI locations. Dependencies were not changed.
- Universal, ARM64, x86_64 and instrumentation builds passed. Original certificate and 16 KiB ZIP alignment verified. The phone APK has 883 identical shared entries and omits 21 other-ABI entries relative to the universal build. Every x86_64 emulator APK payload entry also matched the universal build.
- A longer draft prompt reduced the old populated 4K regression's output allowance. Concise wording restored its existing assertion without weakening the test.

## Blocked verification

No Android instrumentation case completed in this release run. Nine selected regression cases compiled, including the real controller/store approval flow, repeated undiscovered-action rejection, saved-task UNKNOWN blocking, existing document/worker cases and turn cancellation.

An initial simultaneous build/emulator attempt was interrupted. Final builds ran successfully without an emulator. The subsequent universal APK emulator installation timed out after losing the device connection. A separate 1 GiB software emulator with the smaller x86_64 APK also timed out during installation; it later exited with signal 9. The latter still had no installed kai package when inspected. Both failures occurred before instrumentation began. These are environment failures, not passing app tests; their exact host cause was not established.

The owner's physical phone, live llama31-8b instance and Google account were not used. No real Google document was created. Unit fixtures validate recovery logic and action boundaries but cannot prove the full phone/model/service flow will succeed. Prior Android test results remain historical only.

## Installation

Install 0.4.19 over the existing kai app. Retry the Google document request and review the account/title/text before approving. Keep automatic context. Core 0.7.32 and Remote Access 0.3.5 remain sufficient; only the Android update is required for this patch. Send any remaining error so the live behavior can be checked.
