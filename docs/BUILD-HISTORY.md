# Build history through 0.4.27

0.4.27 adds **Account** in the top bar: browser sign-in with email or Google, shared website credits, linked-wallet selection, and the live model catalog. Manual inference API configuration is removed. KOIN Vault support requires the accompanying credit-service update; the app enables that button only after the service advertises support. See [account access](docs/ACCOUNT-AND-CREDITS.md) and [validation](qa/0.4.27/VALIDATION.md).

0.4.26 improves conversational memory: natural family introductions, bounded recent-turn and speaker context, evidence-checked Automatic saves, and memory extraction decisions in chat reports. Sensitive or uncertain interpretations still need review. End voice mode and leave the app open for queued extraction to finish. This update does not rescan old conversations. See [conversational memory](docs/CONVERSATIONAL-MEMORY.md).

0.4.25 pairs with Remote Access 0.3.6 to handle slow local-model streams. Keep-alives stay out of chat text; verified context metadata arriving after headers is retained; late timeout errors are classified without exposing provider text or retrying an action. Update Remote Access on the desktop and restart Core; Core 0.7.32 remains sufficient. See [slow-model transport](docs/SLOW-MODEL-TRANSPORT.md).

0.4.24 keeps the chat editor above the keyboard, repairs action JSON wrapped in prose, and checks literal document-body tasks against actual write receipts. Google document target evidence can pass between Drive and Docs only for the same verified Google identity. See [keyboard and document fixes](docs/KEYBOARD-AND-DOCUMENT-CONTENT.md) and [validation](qa/0.4.24/VALIDATION.md).

0.4.23 added bounded longer local-inference waits, request/response timing stages, and focused recovery from guessed tools. See [timeout investigation](docs/LOCAL-INFERENCE-TIMEOUT.md).

0.4.22 added model-capacity-based tool discovery, document creation ranking, target-ID validation and service-error reporting. See [document action fixes](docs/DOCUMENT-ACTION-FIXES.md).

KAI is a native Android companion and agent. The phone owns its conversations, memories, characters, permissions and task records. Your AI credit account provides access to the website’s available models and shared credits. Connections call Composio directly using the phone owner’s project API key. No desktop memory, Connections or Workflow plugin is required.

This is an installable early-access build. The broader product and engineering plan remains the roadmap; the capability table below describes this build.

Version 0.4.21 fixes action-review layout in Chat and Character mode. New proposals open a full-screen review with independently scrolling details and fixed Approve/Decline controls. Close or Android Back keeps the action pending; the compact Review action card reopens it. Background taps do not approve or decline. Task-list cards use the same review when opened. That update did not change model selection, tool discovery, execution permissions, or service-error handling. A chat report is still needed to diagnose why a particular connected-service action failed.

Version 0.4.20 adds automatic encrypted diagnostics for regular chats and saved task chats. Tap **Report → Save report** in the chat header, or **Report** beside a conversation in history, to save a JSON report with its transcript, task ledger, bounded model requests/responses, context budgets, tool discovery, runtime decisions, approvals, HTTP errors and service receipts. Recording starts with this update; old internal steps cannot be reconstructed. The report is a snapshot for review, not an importable backup. Credentials are filtered before persistence and again at export; personal conversation, relevant memory context and service data remain. Nothing uploads automatically. Temporary chats are not recorded. Forget/correct/delete and credential changes clear diagnostic traces and invalidate in-flight writers. See `docs/CHAT-REPORTS.md` for retention, privacy and coverage details. No desktop update is needed for this feature.

Version 0.4.19 recovers when a model skips discovery, guesses an action name/account ID, or supplies fields that do not match the real tool schema. The phone rejects that proposal and may read the selected active account's tool catalog, then requires a new model response before creating any review card. It never substitutes a similar tool into the rejected action. Recovery is bounded to three corrections per conversation turn/worker invocation; a guessed action triggers at most one automatic catalog search per account. Exact approval, durable submission and UNKNOWN outcome rules remain. This applies to either inference provider and does not change context sizing. No desktop update beyond the already released Core 0.7.32 / Remote Access 0.3.5 is required for this fix.

Version 0.4.18 distinguishes request-validation errors from local-model rejections and classifies context, chat-template, model-availability, memory-allocation and generation-parameter failures without exposing raw server text. Update Remote Access to 0.3.5 for upstream model reasons. Settings adds **Test response**, an explicit local inference check with synthetic text and a small output allowance. It does not use conversations, memories or connected apps. Test API remains a catalog-only check. The owner's reported HTTP 400 is not yet reproduced; these changes expose the next useful diagnostic instead of assuming the advertised 131,072-token ceiling proves generation works.

Version 0.4.17 recovers from malformed or truncated model action proposals with at most two corrective inference attempts per turn. Rejected fragments cannot execute, and original instructions, schemas, receipts, approvals and UNKNOWN outcomes remain binding. Recovery stops once speech has begun, on network failures, or when the retry limit is reached. AI settings now show the model's advertised context limit after Test API and the actual active window reported after a response.

Version 0.4.16 automatically discovers the selected Koinos API model's context limit before budgeting a chat or saved task, including after an app restart. It refreshes that capability after one minute and requests enough context when the API supports automatic allocation. Missing metadata is identified as a conservative fallback. Larger windows require the matching KAI Core and Remote Access updates, released as Core 0.7.32 and Remote Access 0.3.4. The optional desktop control is **Model library → your model → Automatic context**; the phone requests context automatically. See `docs/SMALL-CONTEXT.md`.

Version 0.4.15 changed the launcher to the KAI robot head and the name **kai**, removed the early-access badge, and fixed another small-context document path. Literal supplied-content document requests use a focused connected-action prompt; mistaken public-search decisions are redirected before network access. Optional memory retrieval can be deferred without deleting memory or removing the task ledger. Full current schemas and execution receipts stay pinned.

The previous update reduces context overhead for KAI Core’s small served windows. It uses concise agent instructions, bounds the optional memory review view, and discovers tool metadata before loading one selected schema. Earlier discovery details can be compacted, while the newest schema and actual action results remain available. The 4,096-token fallback and output/template reserve remain enforced; no model context size is fabricated. See `docs/SMALL-CONTEXT.md`.

The previous update extracts structured facts from memory suggestions and reconciles them with current facts and pending cards. A corrected surname updates the same review card or replaces the earlier saved property after confirmation. Reviews show the extracted facts, the earlier value when applicable, and the original user message as evidence. Natural family-and-birthday statements produce linked person, relationship and date facts. Repeated facts are deduplicated; delayed extraction cannot undo a newer correction. Ambiguous spoken spellings require clarification.

Simple older surname notes and cards can be matched during correction. Revisiting an older pending transcript card converts it in place when its entire text can be extracted into known facts. Mixed notes retain their unrelated information. Existing encrypted storage, scopes, temporary-chat boundaries, forget records and the original signing identity are preserved. The update does not guess missing phone data or bulk-rewrite every old free-form note.

The previous spoken-link fix remains: bare HTTP(S)/www URLs are spoken as “link,” readable link labels stay readable, and destinations remain clickable.

Previous update: This update adds phone-managed web search and public page reading for either AI provider. DuckDuckGo works without a key; Settings accepts an optional personal Brave Search API key, with public-search fallback. Retrieved URLs accompany answers, blocked/empty searches are reported honestly, and research-dependent outgoing actions still require their exact review. Search uses the internet; “local” describes the phone-owned agent and transport, not an offline web index.

KAI Core requests now use the served context window when advertised, otherwise a conservative 4,096-token budget. The app reserves output/template space, drops only older chat, preserves current instructions and operation receipts, and reports context/template errors without echoing server secrets. The request builder is tested with `llama31-8b`-style 4K follow-up conversations; the owner's live Core endpoint still needs a phone test.

Hand waves, arm gestures, torso twists, and body search poses are removed for all four characters. Gentle whole-character floating, head motion, blinking, gaze, and speech facial expressions remain. Floating translates the entire character instead of stretching planted feet.


The previous update opens on the **Agent → Character** screen, with a large **Start talking** button, a visible **Voice** picker and a **Chat** tab for the same conversation. Settings also links directly to voice options. The three new priorities are implemented: resumable tasks, scheduled routines, and project files/camera/memory. See `docs/CONVERSATION-AND-WORKSPACE.md`.

The previous update brought the original desktop KAI cartoon and animated rig to Android, alongside Nova the alien, Spark the fuzzy monster, and Sage the woodland owl. Replies can speak in phrases while the selected model is still generating. Listening, thinking, speaking, task work, review, success and error have distinct character reactions. Voice controls add pacing, speed, language and local speech preferences, with reduced motion and optional touch feedback. See `docs/VOICE-AND-CHARACTERS.md` for the implementation and its limits. The 0.2.1 Gmail identity/discovery fixes and structured memory remain in place; memory architecture is documented in `docs/MEMORY-ARCHITECTURE.md`.

## Install

1. Download `KAI-Android-0.4.27.apk` on your ARM64 Android phone, then open the APK. Install over your existing kai app to retain its local data; do not uninstall first.
2. Open it and allow installation from the download app if Android asks.
3. Launch **kai**. It installs beside the older Koinos AI app and does not migrate or overwrite its data.
4. Tap **Account → Sign in / Create account**. Use your website AI credit account (email or Google), approve the phone code in your browser, then return and choose an available model. Select a linked wallet and disable **Use free credits only** when you want to use purchased credits.
5. For email, documents, and other connected actions, open **Connections** and paste your own **Composio project API key**, then tap **Save & connect**. This integration credential is separate from your signed-in AI credit account.
6. Choose an app from the catalog, complete its Composio/provider authorization in your browser, then return to KAI. Existing active accounts offer **Use in chat**.

**Updating from an earlier alpha, including 0.4.8:** install this APK over the existing KAI (Alpha) app; do not uninstall it first. The package and owner signing certificate are unchanged. Credentials, exact connection ownership, conversations, encrypted memory, deletion records and speech preferences are preserved. Kiki replaces removed voice IDs and automatic defaults across characters; existing deliberate device-voice selections remain. Later explicit Device default selections remain selected. Retired Desktop/Kokoro model caches are removed only after the new Kiki model has loaded successfully; state databases and keys are outside those cache paths. Legacy encrypted snapshots are never silently substituted for an unreadable activated database.

Android 9 or newer is required. This APK uses a development signing certificate and the separate application ID `com.kai.agent.preview`. It is not a Play Store release or an upgrade to `io.koinosai.mobile`.

## What works in this build

| Area | Implemented behavior |
| --- | --- |
| Interface | Native Kotlin/Compose, dark and light themes, five main screens, accessible labels, conversation history, selectable text and share-to-KAI text drafts |
| Characters | Original desktop KAI plus alien Nova, woodland owl Sage and fuzzy monster Spark; articulated local artwork, state reactions, tap greetings, per-character prompts and voice selection |
| Conversation | Streaming text, cancellation, typed corrections during generation, bounded context, account-based model access and server model discovery |
| Voice | Kiki as the bundled default plus ordinary installed Android TTS voices, phrase-streamed replies, partial captions, Natural/Patient/Quick turn pacing, best-effort spoken interruption, explicit Your turn, installed voice previews, conservative heard-position tracking, speed/language/local-only preferences |
| Memory | Automatic clear facts, stable people/relationship IDs, precise partial birthdays, confirmation for ambiguity, source evidence, corrections, Undo/forget, Automatic/Ask first/Off, temporary chats, scoped relationship/keyword/semantic retrieval |
| Persistence | Phone-local SQLCipher database with a random key wrapped by Android Keystore; transactional state/graph/evidence/index updates, encrypted credentials, migration from the original AES-GCM snapshot, no automatic Android backup |
| Tasks | Saved plans of up to eight steps, pause/resume/cancel, progress and outputs, user directions, notifications, exact write approvals, receipts and UNKNOWN replay guards |
| Routines | Hourly, daily and weekly WorkManager scheduling in the saved time zone; one occurrence per due time, no catch-up backlog, no overlap while prior work needs attention |
| Projects & files | Scoped conversations and memory, TXT/Markdown/CSV/JSON/DOCX/PDF/image import, system camera capture, bundled local OCR, cited excerpts and optional photo input for a compatible vision model |
| Connections | Direct Composio project key setup, safe paste normalization, read-only per-endpoint access diagnostics, paginated searchable catalog, multiple accounts, hosted OAuth, exact user checks, disconnect and phone-only key removal |
| Portability | JSON export/import of app data; account sessions and integration credentials excluded; imported tasks never execute automatically |

Try `My daughter’s name is Aurora`, then start another chat and say `Aurora’s birthday is in October`. Memory shows the relationship and month-only birthday with their original sources. With an inference provider configured, `When is my daughter’s birthday?` retrieves the relationship and birthday together. `What do you remember about me?`, manual saves and deterministic fact extraction work locally; an ordinary conversational answer still uses your selected inference API.

Clear supported statements save automatically by default with Undo. Ask first puts every new fact into a memory review card. Broader facts are extracted by your selected model after the conversation is idle. Automatic mode saves clear supported preferences, occupations, home cities and favorite colors; uncertain meaning, identity conflicts and sensitive details require review. These memory confirmations never approve email/social/tool actions. Temporary chats stay in the current session, use no saved memories, create no memory jobs and disappear when you leave them or the process closes. Connected-action receipts remain durable even when an action originated in a temporary chat.

Connected read-only tools can run within the current request. Outgoing writes require review of the exact account, tool and arguments. A model suggestion is never itself execution permission. A sent request cannot be unsent by interrupting speech.

## Account and model setup

Open **Account** to sign in or create an account with email or Google through the official credit website. Approve the phone code and return to the app. Free allowance and purchased credits are shown separately because they have different funding sources. Purchased credits are shared with the website and belong to the selected linked wallet. Credit costs vary by model; the screen shows current input/output prices per million tokens.

Select a model from the server catalog. Premium models require purchased credits; choose a linked wallet and switch off **Use free credits only** to authorize paid usage by chats, tasks and automatic memory extraction. Your KAI Core desktop does not need to remain online for this account-based network route.

**Connect KOIN Vault** and **Buy credits** open the account website for approval and payment, then refresh the balance on return. The Vault button remains disabled until the backend update is deployed. No live payment was performed during validation.

Sign-out removes the phone’s session and stops foreground model work. Local chats, memories, characters, connections and task history remain on the phone. Relevant conversation and memory context are sent to the selected service for inference; connected-tool arguments go directly to Composio. Account login does not sync the local workspace across devices.

## Direct Composio setup and account reuse

This version uses the documented Composio v3.1 REST API at `https://backend.composio.dev`. Account lists are filtered to the configured user ID. Catalog, tools and account lists use bounded pages, replacing the old managed-service response limit that produced “The connection response is too large.” The app never logs configured keys, forwards them to redirected hosts, or silently retries writes. Use **Check access** when setup fails: it tests only read endpoints for toolkits, connected accounts, tools and auth configurations. A 401 can mean a missing/revoked key, the wrong credential kind, or missing scoped permissions; it does not by itself prove your key is invalid. Successful read checks do not grant or verify write permissions. See `docs/COMPOSIO-SETUP.md` for exact key and permission guidance.

You do **not** need a new Composio account just for Android. To reuse existing connections, use a key from the **same Composio project** and the **exact user ID** those connections belong to. The key selects the project; the user ID selects its connected accounts. An active connection under a different project or user ID does not automatically transfer. The Advanced setup field lets you enter the existing ID; upgrades from 0.1.0 preserve the previous KAI-managed ID automatically. A fresh install creates and persists a unique `kai-mobile:<UUID>` identity.

Use **Open Composio dashboard** to obtain/manage a key for your own project. If the key has restricted API permissions, it must permit the account, toolkit, auth-config and tool operations you intend to use. For a clean separation from existing production connections, you can create a separate project in the same Composio account, then authorize the apps there. A new project does not inherit connected accounts from another project.

The key is entered by the phone owner and encrypted in app-private Android Keystore-backed state. It is excluded from JSON exports, prompts and logs. **No shared KAI project key is bundled into this APK.** A project key is privileged project access: the user ID filter does not turn it into a per-user security credential. This is a personal bring-your-own-key mode. For a public app using one shared publisher key, keep that key in an authenticated broker; Composio recommends server-side project keys. Each user of this standalone build should supply a key for their own project, not a copy of a publisher’s shared production key.

Composio still stores managed provider credentials and performs provider API requests. KAI on the phone owns its memory, agent loop, approvals and task records. Provider sign-in necessarily happens on the Composio/provider consent pages in the browser; no Koinos AI website login is involved. Returning from the browser triggers an account refresh, and an account is usable only when Composio reports it active and enabled.

A project with an existing **OAuth identity verifier** can require that verifier to complete a new authorization. The app does not disable or bypass that project policy. Existing active accounts can still be reused. Apps requiring custom OAuth credentials may need an auth configuration in your Composio project first; apps with supported managed authentication can be set up from the app.

**Remove key from this phone** removes local access without revoking accounts used elsewhere. **Disconnect** removes the selected account connection from the Composio project and can affect other agents using it; the confirmation explains this difference.

An accepted tool request is not proof of email delivery or completed publication. Unknown outcomes block automatic retries. After an interrupted submission, verify the target app before initiating another operation. Client approvals bind the exact user, credential generation, account, tool version, schema and arguments.

Primary references checked for this implementation:

- [Composio account and project matching](https://docs.composio.dev/docs/authentication/session-cant-find-account)
- [Connect Link API](https://docs.composio.dev/reference/api-reference/connected-accounts/postConnectedAccountsLink)
- [Composio production and project-key guidance](https://docs.composio.dev/docs/production-readiness)

## Current limits

- Kiki speaks English and runs offline with bundled Kitten assets. One sentence is prepared ahead of playback and the model warms during voice mode. Cold model preparation and synthesis can still take time; phone responsiveness needs testing on the actual device.

- Recognition uses the Android speech service and can process audio online unless Local speech only is enabled. Local-only recognition fails closed if the phone cannot provide it. This does not make AI inference or connections offline. Echo handling, Bluetooth and automatic interruption still need physical-phone testing. Use Your turn to interrupt while retaining voice mode, end voice to stop, or type a correction. Device voice quality and language coverage depend on the phone.
- Voice capture stops when KAI leaves the foreground. The app does not promise an always-listening wake word, background microphone or uninterrupted work while Android suspends it.
- Tasks save checkpoints and resume through Android WorkManager. Submitted interrupted actions become UNKNOWN and are not replayed. Android may delay routines for network/battery constraints; force-stopping the app prevents work until it is opened again. No exact-alarm or always-running service is promised.
- Memory uses conservative English statement patterns for immediate automatic saving. Additional model-extracted details wait for review. It does not claim perfect unrestricted autobiographical understanding. Ambiguous people require explicit choice; a name or vector score alone never merges identities.
- Semantic search uses bundled English all-MiniLM-L6-v2 int8 embeddings (384 dimensions, approximately 23 MB model) and ONNX Runtime. It needs no embedding API and sends no embedding requests. Indexing runs while the foreground conversation is idle; newly saved facts are available through exact/keyword retrieval while vectors are pending. The durable extraction queue resumes in the foreground, not as an always-running background service.
- Relationship traversal is deliberately bounded, and vector retrieval currently scans the small personal corpus. Project files use bounded keyword-ranked excerpts, not a full semantic document index. Large knowledge collections and automatic graph-wide reasoning are not implemented. Phone-specific performance and battery use still need measurement.
- Files are limited to 15 MB, 200,000 extracted characters and 12 PDF pages; OCR currently uses bundled Latin text recognition. General photo understanding requires explicit photo-input opt-in and an OpenAI-compatible vision model. Screen control, custom character authoring, cloud premium voices, MCP server setup, Grok/Muse bridges and on-phone LLM inference are not implemented here.
- A listed model or Composio catalog entry is not proof that every model/action/account type is compatible. This release has not been tested with the owner's live API credentials or connected email/social accounts.
- Exported JSON is **not encrypted** and may contain private conversations, memories, task content and results. Keep it private. Configured credentials are excluded, but secrets manually pasted into chat are ordinary conversation content.

## Build and test

Use JDK 17 or 21, Android SDK platform/build-tools 36, and the checked-in Gradle wrapper (8.13). Android Studio can import this directory directly. Set `sdk.dir` in a local `local.properties`, or set your Android SDK location in the usual development environment.

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.kai.agent.data.KaiStoreDeviceTest
```

The second command requires a connected emulator/device and tests real Keystore persistence. The output APK is `app/build/outputs/apk/debug/app-debug.apk`.

The distributed test APK is ARM64. The default source build remains universal; use `./gradlew assembleDebug -PkaiTargetAbi=arm64-v8a` for the phone package.

The source archive includes the pinned Kiki voice assets and isolated runtime AAR. Gradle still needs the other project dependencies in its cache or access to their repositories.

Updates to an already installed alpha must use its existing signing identity. A separate private `KAI-Android-Alpha-PRIVATE-Signing-Backup.zip` preserves that identity; it is deliberately excluded from this source archive. A newly generated debug signing key cannot update the existing installation. The backup contains development signing material, not AI/Composio credentials or phone data.

The repository includes tests for streaming/cancellation, endpoint credential isolation, memory deletion and scope, exact approval binding, session ownership, schema validation, unknown-outcome recovery, receipts, voice turn logic, and Keystore persistence. See `VALIDATION.md` for the actual run results and their limits.

`qa/mock_inference_server.py` is an explicitly fake, local-only provider for testing the real APK without using paid inference. It is not bundled into or used by the app. Its records contain only counts and booleans. Start it on the emulator host before running `com.kai.agent.ui.MainActivitySmokeTest`; its API appears to the emulator at `http://10.0.2.2:18765/v1`. These UI tests deliberately use an isolated emulator, back up its original encrypted preview profile, and restore that profile after phase 2. Run the two phases separately with an app force-stop between them to test process restart. They skip on physical phones.

## Code layout

- `ui/`: Compose screens, theme, character-state mapping and a restricted local SVG animation surface.
- `app/src/main/assets/characters/`: original KAI assets with MIT provenance, three generated companion textures/rigs and recorded prompts.
- `data/`: SQLCipher persistence/migration, graph/evidence/jobs/FTS/vector records, deletion rules, task state and export/import.
- `memory/`: conservative extraction, identity resolution, review, bounded retrieval and foreground scheduling.
- `memory/embedding/`: bundled offline sentence model, WordPiece tokenizer, native inference and cosine scoring.
- `inference/`: provider-neutral streaming client and endpoint policy.
- `connections/`: direct Composio REST client, bounded pagination and account/action checks. Legacy managed-service classes remain for compatibility tests; the running app does not instantiate them.
- `voice/`: Android speech lifecycle, turn aggregation and playback handling.
- `agent/`: strict response protocol, bounded model context and tool result interpretation.
- `KaiViewModel.kt`: phone-owned orchestration and lifecycle integration.

Inference compatibility is based on inspected contracts in `therexdev/kai` and `therexdev/kai-plugin-remote-access`; direct connections use the official Composio REST contract. No changes were pushed to the legacy `therexdev/kaiapp` repository and no production service was modified.
