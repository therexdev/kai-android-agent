KAI Android 0.5.0 testing release: shared agent runtime, encrypted saved progress, retained research and drafts, exact approvals and uncertain-write reconciliation, bounded model recovery/fallbacks, remote MCP connections, workflow templates, and cited file retrieval. Includes the earlier context, document, voice, account-credit and model-dropdown updates.

Install the signed ARM64 APK over the existing kai app. Android 9+ is required. Keep an export before upgrading if you need to return to an older APK: the encrypted database schema upgrade is forward-only.

Validation: 528 automated JVM tests, Android lint and ARM64 release assembly passed; original signing identity and 16 KB alignment verified. Emulator storage/restart tests could not execute because Android package services were unavailable; device validation remains open. Physical-phone voice, real connected-service behavior and production gateway/native model activation remain unverified.

Use the test guide at docs/architecture/ALPHA_TEST_GUIDE.md. See docs/architecture/IMPLEMENTATION_STATUS.md for the 64-item audit: 23 implemented, 27 partial, one gated, and 13 planned. This test build does not complete the full roadmap.

SHA-256: `6794141bab4b76aaeb92a01dcef184ae4b4b8f60c8d02ba271c1c8fab1b8e587`
