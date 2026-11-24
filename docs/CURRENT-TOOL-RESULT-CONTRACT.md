# Preserve the current tool result (0.5.9)

## What the latest report proves

The 0.5.8 Android report contains 101 events with zero dropped events, nine successful model responses, two rejected creation proposals and no prepared or submitted action. The model does not end in the preliminary confirmation behavior addressed by 0.5.8. It chooses the ordinary document-create tool, initially supplies only a title, and then places the researched content into that title. Neither proposal includes the optional `text` field.

The earlier successful 0.5.2 proposal uses the Markdown creation tool, where `markdown_text` is required. Both tools are valid service interfaces, but their required fields differ. Prior scripted document-flow tests favored the required-content interface and missed this recovery path. Native Android serialization and the reviewed local gateway code preserve optional schema properties; no evidence establishes provider-side field stripping. The report does not capture the full upstream supplier request.

The body guard correctly rejects an empty body. Its first correction contains the complete service schema and is 2,098 characters long. Before the next model request, strongest context projection reduces it to an excerpt ending before the schema. A just-requested archived source read also loses its excerpt and returns only metadata to the model. Canonical research context still contains the source; this is a separate current-result defect from the 0.5.6 source-retention regression.

The second missing-body proposal triggers the existing repeated-invalid-input stop. No approval card, document or email exists from this run. The draft also ends with a 2024 date despite the supplied 2026 current date. Preserving context does not establish factual quality or correct that date automatically.

## The shared correction

`AgentTurnContext` already promises that the newest result stays complete because it is needed for the next decision. In `project`, the level-3 1,500-character cap was checked before the last-result exemption. Reordering those existing conditions restores the contract. It applies to all newest tool results, including errors, inspection/discovery data, source reads, receipts and each result in a native call group. It is not a Docs-specific projection exception.

Older exchanges remain compactable and retrievable from the unchanged archive. When an active exchange truly exceeds a token or payload limit, `AgentContextManager` stops with its existing context error; the update does not enlarge limits or silently remove the needed information. This may expose oversized current results that previously appeared to fit only because they lost content. Tool producers still need bounded results/paging for such cases.

`AgentToolRecovery` now uses the current protocol for its existing correction. The old text asked native callers for a JSON response. A callback reads the current harness mode across fallback without resetting recovery counters. Both chat and saved-task/routine adapters supply it. `DocumentContentGuard` names the body field from the actual schema in its existing error. It never moves title text into that field or constructs content.

The body check, repeated-invalid-input stop, total recovery limit, action argument limits, exact approval, account/schema binding, durable submission and UNKNOWN non-replay remain. SQLCipher schema 3 and signing identity are retained. The server is not changed.

## Evidence and limits

Private before/after replay uses the exact reported correction and source-read response against compiled classes, in native/JSON and fresh/restored contexts. The previous compiled class loses both current results at level 3; the candidate retains both byte-for-byte within the existing gateway allowance, including a conservative maximum registry. The two invalid action arguments remain invalid. The earlier successful Markdown arguments still validate unchanged, and the 0.5.7 premature-ending regression check still passes.

Public synthetic tests now cover both creation schemas, document-only/email sequences, native/JSON, all run-origin labels and fresh/resumed state. These are component tests with scripted model/service replies, not live model decisions or actual Android adapter execution. A model can still choose the wrong tool/field or generate inaccurate content. This change removes an observed host defect; it does not prove why the model made its first mistake or that it will now finish on the phone. Measured results and open device/live checks are in `../qa/0.5.9/VALIDATION.md`.
