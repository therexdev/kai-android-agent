# KAI 0.5.0 alpha test guide

This alpha exercises the shared phone runtime. It is not the completed 64-item roadmap. See IMPLEMENTATION_STATUS.md and IMPLEMENTATION_STATUS.csv for exact scope.

Install the signed ARM64 APK as an update to the previous KAI preview app. The signing identity and package stay the same. Database schema 3 is a forward migration; older app binaries cannot open it. Use the existing workspace export if you need a deliberate pre-update backup.

1. Open an existing conversation and check that its messages, imported files and pending approvals remain present. A previously interrupted run should show saved progress or an explicit paused/uncertain state. It must not silently submit an old operation.
2. Use the account's selected model and output allowance. Optional ordered fallbacks stay within the same account and credit policy. A native-tool flag is not evidence of live route qualification; production native rollout remains separate.
3. Ask KAI to research Koinos projects, include source links and short descriptions, and put the results in a new Google Doc. Watch for retained findings and a local draft. Review the exact connected account, tool, arguments and document text before approving. Declining must not create a document.
4. When approved, check that KAI retains the actual returned document URL and distinguishes accepted from independently verified contents. A later inference failure must not cause another submission of the completed action.
5. Before approving a draft, close and reopen KAI. Use Continue from the saved run. Findings should remain available. If a provider fails or research is exhausted, use Answer from findings to obtain the retained partial result without executing more tools.
6. Answer a specific clarification in the same conversation. The run should retain the original request and findings. A new request should invalidate old unsubmitted approval rather than silently inherit it.
7. Try a long imported file. Search results must identify the file and offsets. An exhaustive request must disclose partial coverage when not every range was read.
8. In Connections, add a user-owned public HTTPS MCP endpoint using its supported protocol profile. Check tools before running a real action. Every MCP action requires exact review. Changing or disabling the connection invalidates its old unsubmitted review.
9. In Templates, import a bounded kai.workflow.v1 JSON graph or create a template. Run an agent/reviewer pair and check per-node model selection. Capability declarations do not grant accounts or permission. The current editor is a list/JSON interface, not a visual canvas.
10. Test voice on the physical phone: speak a normal question, allow a full response, interrupt intentionally, then repeat with background noise and Bluetooth if used. Stop speaking should stop playback without cancelling retained task work. Pause should pause the selected task. The original acoustic cutoff is not proven fixed by emulator storage tests.

For each failure, use Report and retain the model, run/event code and action status. Do not retry an UNKNOWN write until its actual service outcome is reconciled in Tasks. User confirmation is labeled as user confirmation, not independent KAI verification.

Automated tests intentionally use fake model/HTTP responses. No live paid model call, Google Docs write, production MCP request or physical acoustic test is represented as having passed in this release.
