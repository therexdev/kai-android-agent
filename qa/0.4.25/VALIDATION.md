# kai Android 0.4.25 validation

Based on released 0.4.24 source, SHA-256 e93a62409c666b6b91e7a533b7681ca864dd736a5b2579921b6b19e1a01c2357.

- 410 JVM tests passed with no failures/errors/skips, including four new tests for keep-alives during a slow reply, verified late context metadata, invalid metadata, and late timeout status without replay or provider-text leakage.
- Eight existing Compose UI tests pass, including keyboard visibility and action review. Android lint: 0 errors, 24 warnings.
- ARM64 phone APK and Android instrumentation APK compiled. Original installed certificate and 16 KB alignment verified. Package com.kai.agent.preview, version code 32, version 0.4.25.
- Remote Access 0.3.6: 46 Node tests pass; real Core SDK/runtime context integration passes; a 140-second synthetic inference passes through the actual Relay implementation and a loopback 125-second idle proxy. The workspace cannot resolve the hosted relay, so this is not live Cloudflare verification.
- No physical-phone, emulator instrumentation, real model or live Google action was executed. No database schema or signing identity changes.

See docs/SLOW-MODEL-TRANSPORT.md for behavior and limits. Update Remote Access to 0.3.6 and restart Core. Core 0.7.32 is sufficient. Remote inference has a 170-second bound before the Core network limit. No automatic action retry is introduced.
