KAI Android 0.5.2 test build (code 42)

Adds current route qualification for managed native tools and encrypted provider continuation through approvals and saved-task resumes. Keeps continuation out of reports, exports and model summaries. Switching accounts or model routes preserves completed-action receipts while discarding reusable provider wire.

Install over 0.5.1 with the same package and original alpha signing identity. Encrypted schema 3 is unchanged. All external writes still require exact approval; accepted or uncertain writes must not be automatically repeated.

All 557 JVM tests passed. Lint passed with no errors and 12 warnings (six existing code/platform warnings and six dependency-update notices). The companion gateway passed all 140 deployment regression tests. See VALIDATION.md and the adjacent machine-readable evidence for release checks and limits.

Release and instrumentation APK assembly passed. Original signing identity, package/version, ARM64-only contents, 16 KB ZIP alignment and frozen-source consistency were verified.

APK SHA-256: `fd30a38fc11dd4308dbc4e45d54819c103e6dd3839d3693e29ce5a40913b87c3`.

Native activation still requires the companion gateway deployment, explicit two-turn supplier qualification and an Account refresh. Device restart/lifecycle/audio and live connected-service acceptance remain unverified. This phase does not complete the wider architecture roadmap.
