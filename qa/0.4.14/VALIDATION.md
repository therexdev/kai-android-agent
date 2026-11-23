# KAI Android 0.4.14 alpha validation

Release: version code 21, 18 November 2026. APK is ARM64, min Android 9, original alpha signing certificate.

## Results

- 312 JVM tests passed; zero failures, errors or skipped tests.
- The regression fixture reproduces an overflow using the earlier broad prompt/memory/catalog behavior and verifies the compact flow through real HTTP request serialization. Prompt estimates across its four stages are 1,543 / 2,041 / 2,301 / 2,455 tokens; each keeps 1,024 output tokens plus the 512-token template reserve inside 4,096.
- Latest inspected schemas cannot be replaced by an instruction to inspect again. Oversized required schemas still fail explicitly. Field descriptions retain short hints, and validation constraints and properties named like annotations remain intact.
- Bounded memory views remove whole records, flag omitted data, and do not alter the graph.
- Android lint: zero errors, ten warnings (six existing implementation/ABI/style warnings and four available-dependency-update notices).
- Four Android controller/store tests passed on the signed universal build: populated-memory document request reaches the Koinos-configured provider, existing Koinos memory conversation, existing OpenAI memory conversation, and legacy pending-family-card conversion/persistence.
- Final ARM64 APK has the same 882 non-signature payload entries as the tested universal APK, including code, manifest, resources, assets and ARM64 native libraries. Only non-ARM64 native libraries are omitted. Version and original signing certificate verified; 16 KiB zip alignment checked.

## Test limits

Provider and connected-tool responses in regression tests are simulated. No live KAI Core model, Google account or external write was exercised. The document regression verifies the context path and protocol, not the quality of llama31-8b-generated decisions or a live Google document creation.

The Android emulator uses software CPU emulation. Its first streamed APK installation timed out before tests began; the successful installation used a file push followed by package installation. No phone data was modified or recovered. Physical-phone speech recognition was not tested in this change.

This update does not enlarge the server's actual context window. Very large required schemas, user input, project material or action results can still require a larger served context. No arbitrary required result is silently deleted, and ambiguous submitted writes are not automatically replayed.
