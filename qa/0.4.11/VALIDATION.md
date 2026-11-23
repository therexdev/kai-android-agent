# 0.4.11 validation

- 291 JVM tests passed, zero failures/errors/skips. Includes 4K Core follow-up budgeting, provider switching, safe error classification, source retrieval/fallback/cancellation, bounded research retries, simulated search-to-email proposal with either inference provider, exact approval protection, encrypted settings codec/export exclusion, and all-character resting limb/torso poses.
- Android lint: zero errors, 13 dependency/tooling/style warnings and one existing-style KTX hint. Universal app and instrumentation APKs built successfully. ARM64 phone APK built successfully.
- Android 9/API 28 x86_64 emulator: 0.4.10 (17) installed, then 0.4.11 (18) installed in place with the same owner certificate. Six distinct Android tests passed: four native rendering/lifecycle/motion tests and two encrypted-settings/parser tests.
- The first visual attempt was obstructed by an Android System UI ANR dialog in the software emulator. After choosing Wait, the same app and visual tests passed. Both attempts and the obstructing screenshot are retained here; this was not hidden by relaxing assertions.
- Screenshots show all four characters in resting body poses, with face/head activity retained. Software-canvas rendering, audible-mouth gating, reduced motion, and background/resume behavior passed. This does not substitute for physical-phone animation/audio testing.
- Live DuckDuckGo HTTPS retrieval succeeded, and the production Kotlin parser passed against that captured response. Android parsing ran on-device with simulated HTTP data. Brave auth/fallback and failures were tested with synthetic credentials/transports; no live Brave subscription was used.
- The ARM64 APK matches 852 relevant payload entries in the tested universal APK (code, manifest, resources, assets and ARM64 libraries). All seven ARM64 native libraries are unchanged from 0.4.10. Source assets match the APK, memory mmap packaging is preserved, and Kiki weights remain compressed.
- Signing certificate SHA-256 remains `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`. APK verification passed.

## Limits

The owner's live KAI Core endpoint/llama31-8b instance and real connected email/document accounts were not used. The context mismatch is verified in source and regression tests; the exact earlier device-side HTTP 400 cannot be conclusively attributed without its provider diagnostics. This update budgets requests and improves errors, but a physical-phone retest is still needed. Public search endpoints may throttle, change markup or return no results; the optional Brave API provides a dedicated service route. Search requires internet and page reading does not execute JavaScript or sign into websites. No real email was sent during these tests.
