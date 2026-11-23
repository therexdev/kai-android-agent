# kai Android 0.4.18 validation

Version code 25. ARM64, Android 9+, original signing identity retained.

- 341 JVM tests passed; zero failures, errors or skips. New checks cover safe rejection classification, fixed diagnostic labels, synthetic response-test requests, active allocation reporting, missing models and no retry of rejected generation.
- Android lint: zero errors, ten existing warnings. Universal and ARM64 builds completed. The ARM64 installer has 883 identical shared payload entries; 21 other-ABI entries are omitted. Original certificate and 16 KiB alignment verified.
- The initial combined Gradle run lost its daemon during packaging after unit tests completed. A subsequent packaging attempt failed on incremental state; clearing only generated packaging outputs and rebuilding succeeded. Final build logs are included.
- Remote Access 0.3.5: 36 tests passed locally and on Windows, macOS and Linux CI. Signed source/package identity verified. Request errors retain their HTTP status and one safe category; raw model errors are never forwarded. No inference retry or usage record on rejection.
- A cross-repository test passed through the actual hosted-relay implementation, Remote Access server/client, Foundation SDK and Core, allocating 4K then 16K with a synthetic engine and enforcing a reduced owner cap.
- A separate test used the live koinosai.com relay and the real Remote Access 0.3.5 code with a synthetic HTTP 400 backend. The client received chat_template/local_model with HTTP 400, exactly one model call and no raw private fixture text.
- Registry 0.3.49 / sequence 54: 15 tests, signature verification and all 13 published-package verifications passed. Only Remote Access changed; the other 12 entries and archived legacy catalog are unchanged.

## Scope and limits

The owner's screenshot confirms desktop Remote Access, llama31-8b and automatic context up to 131,072 tokens. This is a catalog ceiling, not proof of successful inference or an allocated 131K window. The reported HTTP 400 has not been reproduced using the owner's actual model or credentials. This release fixes hidden-error handling and adds an explicit short response test; it does not claim to repair an unobserved underlying model failure.

Test API remains catalog-only. Test response uses two synthetic messages and a 128-token output allowance, performs no agent actions, and does not read conversations, memories or connections. It requires a finished nonempty response to report success. Existing provider-check cancellation owns the check; the button is unavailable during an active conversation. There are no new database, migration, microphone or approval/ledger changes.

No emulator/device run was performed for this diagnostic patch. Earlier 0.4.17's nine completed Android checks and two blocked voice checks remain historical evidence only. The new phone UI and the owner's live llama31-8b runtime still require a phone test. No real Google document or user data was changed.

## Install and check

Update Remote Access to 0.3.5 in Core Plugins, then fully quit Core from its tray and reopen it. Core 0.7.32 remains sufficient. Install kai 0.4.18 over the existing Android app. In AI settings, leave automatic context enabled, select llama31-8b and tap Test response. Send the resulting status; if it succeeds, repeat the original document request and capture any specific rejection. Do not share API keys.
