# KAI Android 0.5.0 test release validation

The released code is the shared-runtime implementation at local snapshot cbffeba, with the earlier 0.4.32 changes. SOURCE_HASHES.json pins every build input from the frozen implementation. The signed APK retains package com.kai.agent.preview, version code 40 and the installed alpha certificate.

- 528 JVM tests passed, with zero failures, errors or skips.
- Android lint passed with zero errors and six warnings.
- The isolated ARM64 release assembly passed.
- Original signing-certificate continuity and 16 KB ZIP alignment passed.
- Gateway transport suite: 48 mock-service tests passed.
- Gateway deployment guards and focused transport/diagnostics: 44 mock-service tests passed.
- Emulator encrypted-storage, upgrade and restart checks were attempted. The emulator did not reach usable Android package services, and no instrumentation test ran. A separate existing attempt also timed out transferring the APK. These device gates remain unverified; the APK is published only as a test prerelease.
- Physical-phone voice, Bluetooth, modern Android background behavior and live Google Docs/MCP/provider acceptance remain unverified. No production credit was spent and no real connected-service write was performed.
- Production gateway deployment and native route qualification are separate. APK installation does not activate native tools.

APK SHA-256: `6794141bab4b76aaeb92a01dcef184ae4b4b8f60c8d02ba271c1c8fab1b8e587`. Size: 125607883 bytes. Certificate SHA-256: `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89`.

See IMPLEMENTATION_STATUS.md and IMPLEMENTATION_STATUS.csv in docs/architecture for the 64-item audit. This is an alpha testing release, not completion of the entire roadmap. The schema 3 upgrade is forward-only; preserve an export before updating if a deliberate downgrade may be needed.
