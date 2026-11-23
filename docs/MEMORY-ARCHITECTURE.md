# KAI 0.2.0 memory architecture

Status: implemented in the Android app. See `VALIDATION.md` and `qa/0.2.0/` for executed checks and device limits. This document describes shipped behavior, not an unrestricted memory guarantee.

## Ownership and storage

The phone owns the memory graph, identity resolution, evidence, confirmations, corrections, forgetting and retrieval. Koinos AI and OpenAI remain interchangeable inference providers. Composio is not involved in memory.

`EncryptedKaiDatabase` uses SQLCipher 4.20.0 directly with explicit versioned schema migrations. Room is not used. A random database secret is encrypted with an Android Keystore AES-GCM key. The SQLCipher database, rollback journals, keyword index and vector payloads are encrypted; temporary SQL storage stays in memory. The database lives in the app's no-backup directory.

One transaction commits application state, changed conversations, normalized memory records and derived indexes. Unchanged graph/vector rows are not rewritten. The current personal graph is cached in memory; entity traversal is bounded over those canonical records. SQLite indexes support scoped subject/object/source lookup, and FTS4 supplies keyword matches. This is a personal memory store, not an unbounded document knowledge service.

| Record | Stored information |
| --- | --- |
| Entity | Stable ID, type, display name, scope |
| Alias | Entity ID, normalized name, scope, supporting fact IDs |
| Fact | Subject ID, predicate, entity object or typed literal, status, revision, lineage and optional valid dates |
| Evidence | Original finalized user message and conversation IDs, exact quote/span, source kind, confirmation state |
| Candidate | Proposed facts and identities, reason for review, explicit identity choices, pending/accepted/dismissed state |
| Job | Durable source reference, extraction version, attempts, status and deletion epoch |
| Vector | Fact ID/revision, model ID, projected-text digest, dimensions/values and optional dependency revisions |
| Suppression | Content-free fact/source/claim digests and lineage that prevent old sources or imports reviving deleted facts |

Existing manual notes remain editable through the original note API and are projected into `legacy_note` graph records using their original IDs. Migration does not invent relationships from their prose. These notes participate in the same local keyword and semantic retrieval.

## Linking facts

For the Aurora example, the stored relationship is from the current user's ID to a separate person ID. The birthday belongs to that person:

| Subject | Predicate | Object/value |
| --- | --- | --- |
| Current user | has_daughter | Person A |
| Person A | name | Aurora |
| Person A | birthday | month 10; day and year unknown; precision month |

A query about the daughter's birthday follows the confirmed daughter relationship to the birthday property. Context includes the supporting fact IDs and user evidence. It does not create a third independently writable copy of “my daughter's birthday.” Corrections therefore update one canonical property.

Names are not unique IDs. Two Auroras stay separate. Ambiguous names, pronouns or a relationship that could refer to an existing different person require a choice in Memory. A relationship and its dependent birthday wait together; an unresolved daughter is never silently attached to an existing friend. Similarity scores never merge entities or establish truth. If the requested relationship was forgotten, retrieval does not reconstruct it from a nearby birthday vector.

## Capturing facts and reviewing suggestions

Only completed user messages enter extraction. Speech partials, assistant text, connected-tool content, quoted examples, hypothetical stories and instructions to pretend do not automatically become personal facts.

A conservative English parser handles supported clear statements, including names, family relationships, birthdays and preferences. In Automatic mode, supported unambiguous assertions save immediately with a Remembered notice and Undo. Ask first routes them to a review card. Off disables automatic extraction; an explicit `Remember…` command remains a user-directed save and is validated against its real source. Exact facts previously forgotten remain conservatively suppressed; KAI reports when such a save was blocked instead of falsely claiming success.

For broader personal statements that the parser does not handle, an encrypted job queue can request structured proposals from the currently selected inference provider after the conversation becomes idle. The prompt contains the finalized source, at most 12 recent/context turns within 6,000 characters, an explicit hand-off anchor when present, and bounded scoped entity hints. Assistant questions may explain a short answer but cannot ground stored values. The phone checks source quotes, schema, values, scope, speaker identity and current deletion state. In Automatic mode, clear supported textual preferences, occupations, home cities and favorite colors can commit directly. Sensitive details, ambiguous identity, conflicts, unsupported wording and uncertain model interpretations require review. Model confidence alone is never save permission. Exact supporting user quotes, including context used for identity, become evidence; forgetting a source invalidates dependent facts and in-flight jobs. See CONVERSATIONAL-MEMORY.md.

Memory review is separate from external action approval. Selecting Yes on a fact never authorizes an email, social post or other tool operation. Uncertain identity cards require choosing the intended person or a different person before saving.

The queue survives process death and resumes in a later foreground session. It does not run as an always-on background service. Live generation, listening or speaking takes priority; interrupted extraction is requeued. Timed-out or failed reviews do not claim that anything was saved. API settings are not copied into jobs or memory prompts.

## Hybrid retrieval

Retrieval combines confirmed entity relationships, scoped keyword matches and learned semantic similarity. Only current, permitted, unsuppressed canonical facts can reach the model. The bounded context carries IDs, typed values, source excerpts and an explicit clarification when a person is ambiguous.

Semantic search runs on the phone with the bundled Apache-2.0 `sentence-transformers/all-MiniLM-L6-v2` int8 ONNX model. It uses 384-dimensional normalized vectors, a WordPiece tokenizer, mean pooling and cosine similarity. The model file is approximately 23 MB; no embedding API key or embedding network request is involved. Switching the conversational provider does not change the embedding model or recreate memories.

Each vector derives from a short canonical description of one fact. Relationship and property facts are joined during retrieval, not stored as duplicate inferred claims. A vector is accepted only when its model, dimension, fact revision and description digest still match. Renaming a person, correcting a property or deleting evidence invalidates stale derivatives. Embeddings are generated in small batches off the UI thread while idle; exact/keyword retrieval works before indexing finishes. Current vector search is an exact scan, appropriate for the bounded personal corpus; it is not an ANN service.

The included semantic checks compare real paraphrases with distractors using the actual model. Host/emulator timings are not physical-phone latency or battery guarantees. The bundled model is English-oriented; multilingual retrieval is not promised.

## Corrections, forgetting and temporary chats

A clear correction such as `Actually, Aurora's birthday is November` supersedes the old birthday on the same person. An unexplained conflict asks first. Month-only dates never gain a day, year or age. Ordinary retrieval excludes superseded facts. Valid-time fields exist, but generalized historical/temporal question answering is not implemented.

Forget removes the correction family, affected source excerpts, aliases, candidates, vectors and keyword entries. Unsupported entity names are anonymized or removed. Non-content suppression records block old imports, stale extraction and source reprocessing. Where one source supports several intertwined claims, cleanup deliberately removes the affected source-derived claims together rather than retaining forgotten text in another evidence quote. Undo is separate: it can restore an unchanged immediately superseded predecessor while retracting the new fact.

Original visible chat is a separate user-controlled record. Forgotten source messages and older context are excluded from subsequent memory context; Delete conversation also removes its supported memories and jobs. Local deletion cannot retract context already sent to an inference provider.

Temporary chats stay in the current session and are excluded from persisted conversation history, exports, memory capture and memory use. Leaving the temporary conversation deletes its session transcript. External actions still retain durable task/receipt records so a temporary chat cannot erase an uncertain submitted operation.

## Upgrade and recovery

The original AES-GCM JSON is decoded locally and initially preserved for recovery. A later privacy-sensitive mutation retires that old snapshot so it cannot retain a second copy of forgotten facts. Migration stages a new encrypted database, imports IDs, sources, notes, settings and deletion records, reads it back, runs integrity verification and activates it atomically. A durable authority marker prevents a missing or damaged activated database from silently falling back to the older snapshot. Failed migration preserves recovery files and surfaces an error; unreadable data is never reset to an empty profile.

Exports omit configured credentials and derived vectors/jobs. Imports merge local data without granting action permission or resuming imported work. Local deletion wins over old backups, including orphan entity names. Temporary conversations are never imported as durable chats.

## Verification scope

The suites cover Aurora facts in both orders/across chats, missing date components, homonyms and multiple daughters, relation-dependent ambiguity, corrections and conflicts, aliases, quotes/negation/hypotheticals, review acceptance/dismissal, scopes, Off and temporary modes, stale vectors, job cancellation, deletion/import suppression, SQLCipher migration/reopen and the real bundled model. Refer to the release validation report for which checks actually passed, blocked checks and physical-device limits.

Primary technical sources:

- https://github.com/sqlcipher/sqlcipher-android
- https://www.zetetic.net/sqlcipher/sqlcipher-for-android-community/
- https://sqlite.org/fts3.html
- https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
- https://onnxruntime.ai/docs/tutorials/mobile/deploy-android.html
- https://arxiv.org/abs/2410.10813
