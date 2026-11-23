# kai 0.4.26 validation

- App version: 0.4.26; versionCode: 33; package: com.kai.agent.preview.
- JVM tests: 434 passed, 0 failed, 0 skipped; includes 24 new contextual memory regression tests.
- Android lint: 0 errors, 25 warnings (dependency updates and existing API/style/ABI warnings).
- Debug, Android test, and arm64-v8a release assemblies completed.
- Signed APK verified with the existing update certificate: a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89. APK 16 KB zip alignment verified.
- Device result: Passed 18 instrumentation tests in three isolated runs on an Android 11 (API 30) x86_64 software emulator. Includes encrypted storage/reopen, context evidence export/import and forgetting, diagnostic persistence, background interruption, and mock OpenAI/Koinos extraction.

## Coverage

Natural introductions, named speaker hand-offs and return, bounded conversation context, Automatic/Ask first/Off, sensitive or uncertain review, duplicate and conflict handling, ambiguous names, fictional/quoted text, unsupported assistant guesses, temporary chats, stale jobs, exact evidence, and directional forgetting.

Tests use synthetic model responses and mock HTTP services. No live OpenAI, Koinos AI, or physical-phone inference session was run here. A successful mock test does not prove a particular model will extract every fact correctly.

## Phone acceptance check

Install the APK over the existing app without uninstalling. No desktop update is required for this memory change. Select Automatic, start a new regular chat, introduce a family member and discuss an interest. End voice mode and leave the app open for background extraction. Check the person and fact in Memory, then start a new chat to check recall. Use Report → Save report if something is missed. Existing chats are not automatically rescanned.
