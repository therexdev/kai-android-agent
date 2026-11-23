# Conversational memory — 0.4.26

The phone now recognizes natural introductions such as “say hi to my daughter Aurora” and keeps the relationship linked to the person. Ordinary pretend-play preferences no longer exclude an entire message. Unsupported or uncertain clauses are filtered separately where they can be distinguished from useful assertions. Quoted material, fictional setups, pasted instructions and credentials remain ineligible.

Background extraction sees up to 12 same-chat turns with a 6,000-character context budget, plus scoped current/pending memory. A recent explicit hand-off identifies a guest speaker; its anchor is retained within that budget when the rest of the conversation grows. A parent returning with an explicit name changes the speaker without silently merging that person into the phone owner. Names shared by multiple people require identity review. Assistant questions help interpret answers, but model guesses do not establish facts. Context quotes must come from actual user messages.

Automatic mode saves clearly grounded textual preferences, occupations, home cities and favorite colors when supported by the user's words and an unambiguous subject. Sensitive information, single-value conflicts, unclear speech and uncertain interpretations remain review items. Ask first continues to review new facts; Off and temporary chat boundaries remain enforced. Repeated claims reconcile with current facts; a reviewed correction supersedes the old property. The same rules apply to both inference providers.

Saved facts retain exact source evidence. Context evidence is tracked separately: forgetting a preference keeps its family relationship, while forgetting the identity anchor also invalidates facts that depended on that anchor. Forgetting a source also invalidates facts that depended on it, queued work and late diagnostic callbacks. No new database schema is required. Existing chats are not retroactively reprocessed.

Reports now include memory capture, whether a job was queued, background inference requests/responses, validation counts, save/review counts, and job outcome. Empty claims, rejected evidence, malformed output, interruption/requeue and timeout are distinct. Existing credential filtering and diagnostic retention apply. A report includes personal chat and memory context; it does not upload itself.

## Phone check

1. Install over the existing app. Select Automatic in Memory.
2. Start a regular chat. Introduce a family member naturally, then answer questions about their interests.
3. End voice mode, if active, and leave the app open while background processing finishes. Live conversation always takes priority; memory extraction does not compete with listening or speaking.
4. Check Memory for linked facts or a review card for uncertain details. Verify the interests belong to the right person.
5. Start a new chat and ask about a saved detail. Use Report → Save report if anything is missed.

This is a phone-only update; no KAI Core or Remote Access upgrade is required for these memory changes. A provider is needed for broader extraction; simple locally recognized facts do not require an additional inference request.
