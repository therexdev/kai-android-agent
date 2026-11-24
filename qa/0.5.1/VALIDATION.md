# KAI Android 0.5.1 validation

This patch addresses research-to-spreadsheet completion, approval presentation, and document-to-email continuation. It does not complete the broader architecture roadmap.

## Automated checks

- Frozen source: 707 input hashes retained in SOURCE_HASHES.json; no source drift between the final frozen-input record and signing.
- Full JVM suite: 550 cases, 549 passed. One existing AgentFlowTest hit its 20-second timeout.
- Isolated AgentFlowTest rerun: all six cases passed, including that failure. No timeout or assertion was weakened. All 550 unique cases passed across the full run and targeted rerun.
- New regression cases cover spreadsheet body/row evidence, headers, batch/column writes, cross-run and uncertain effects, old completion recovery and Forget, cached research, native tools after web closure, retained source evidence, approval identity, published contacts/JSON-LD, and document-plus-email continuation through compaction.
- After the final checkpoint-reference refinement, the frozen-source focused suite passed all 38 cases, including a new regression that keeps an unloaded email schema visible among unrelated tool catalogs. The broad suite and these focused checks cover 551 unique cases; the full 551-case suite was not rerun after this small refinement.
- Android lint: zero errors and six warnings. See LINT.json.
- Final frozen-source JVM/lint/ARM64 release assembly gate passed. Original certificate, package/version, ARM64-only contents and 16 KB alignment verified. See APK_METADATA.json and RELEASE_GATE.log.

## Limits

No Android device is attached. Instrumentation, physical-phone lifecycle/audio, live model behavior, and actual connected-service write/readback remain unverified. The previous software emulator could not provide ready package services. Mock-service tests are not evidence of a real email or spreadsheet write.

The captured test reports used the legacy JSON action protocol. Invalid or mixed action text remains rejected. Native gateway activation remains separate and unverified after the reported missing dependency; this patch does not claim that production deployment succeeded.

The original reports and private identifiers are excluded from this repository. Fixtures use synthetic data. Contact/ratings extraction preserves published claims as site-reported data; it does not bypass refused sites, execute scripts, or verify third-party review authenticity.

## Distribution

The signed APK is supplied directly for testing. GitHub binary release publication could not be completed because the release browser repeatedly timed out. The README does not link to an unpublished asset. Source changes are tracked in PR #3.

Install over 0.5.0. The package/signing identity is preserved, and encrypted schema 3 is unchanged. Old setup-only spreadsheet completions may display as partial; use Continue to resume remaining work. No write runs automatically from that repair.
