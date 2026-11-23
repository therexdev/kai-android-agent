# Conversation and workspace update · 0.4.0

## Talk directly to a character

Agent opens in Character mode. Tap **Start talking**, grant microphone access, and talk. **Your turn · interrupt** stops the current answer and returns to listening. **End conversation** stops voice. Opening the app alone does not start the microphone, and leaving the foreground stops capture.

**Chat** and **Open chat & transcript** show the same conversation, including spoken user turns and assistant answers. Conversation history remains in the top bar. Temporary chats still do not persist. The character reacts while listening, thinking and speaking; tap greetings remain local animations.

**Voice** is always visible on Agent. Select or preview the bundled KAI Desktop · Azelma voice or a device voice. A preview does not change the saved choice. Settings → Voice options & preview opens the same panel. The desktop Cute KAI treatment remains +9 semitones at 1.08 tempo, combined with the user's speech-rate preference. The native synthesizer itself was not replaced in this release. Initial synthesis speed, acoustic echo and recognition quality require testing on the actual phone; the software emulator is not representative of phone latency.

## Priority 1: resumable tasks

Tasks → New task accepts a goal and optional explicit steps. Otherwise the selected AI plans one to eight steps. The phone saves the plan, each tool exchange and completed-step output in encrypted storage. WorkManager runs bounded segments with network constraints. Pause/resume/cancel, step review and **Answer / change directions** are available from the expanded task. Open chat shows the task's durable transcript. A model can pause with a specific question instead of reporting a blocked step complete.

Notifications report completion, required approval or a problem and open Tasks. On Android 13+, the app asks for notification permission when creating work; the task remains visible in the app if notifications are disabled. Lock-screen public text is generic.

UI and workers share a reference-counted store and one transaction mutex. Worker leases reject stale updates. The pending step pointer and exact action intent commit together before submission. Connections revalidate ownership, pinned tool version, schema, arguments and the approval fingerprint. Outgoing actions require a concrete in-app approval. Read-only metadata-marked actions may execute within the requested task.

Submitted actions recovering after interruption become UNKNOWN. UNKNOWN, ACCEPTED and in-flight outcomes block automatic replay. A user may check the service and explicitly skip a step; this preserves its original receipt and does not convert an uncertain result into success. Cancellation cannot undo an action already submitted. Tool responses are reference data, never permission.

## Priority 2: routines and recovery

Tasks → New routine supports hourly, daily and weekly recurrence. Daily/weekly schedules retain the chosen device time zone, handle daylight-saving gaps, and avoid running the repeated fall hour twice. A unique occurrence ID binds routine, revision and due time. Creating the occurrence and advancing the next due time is one encrypted transaction. The schedule is then reconciled with WorkManager.

Late runs create at most one current occurrence. An unfinished prior occurrence, including work awaiting approval or attention, blocks overlapping copies. Edits and disabled routines invalidate old scheduled requests. Startup repairs queued work and missing future requests. Imported routines are disabled and imported tasks are paused; import never authorizes execution.

These are approximate Android background schedules. Network access, Doze, battery restrictions and force-stop can delay them. The selected AI endpoint must be reachable; local Koinos inference needs the desktop online. Recurring inference may incur provider charges. No cloud scheduler or always-on microphone is involved.

## Priority 3: files, camera and project memory

Projects holds separate conversations, written project memory, automatically captured scoped facts and files. Personal conversations use their own scope. Scope filtering occurs before retrieval; matching keywords cannot expose a different project's content. The Memory tab reflects the current project. Turning off saved memory also withholds written project memory.

Add files in Chat or Projects. Camera uses Android's camera app and a narrowly scoped, non-exported FileProvider. No broad storage or camera permission is requested. Picker results retain their original project/conversation even if navigation changes while the picker is open. Temporary chats cannot create persistent attachments.

Supported input: UTF-8 text, Markdown, CSV, JSON, XML, DOCX, PDF and images. Maximum raw size is 15 MB, extracted text 200,000 characters, and PDF length 12 pages. Bundled Latin OCR reads photos and rendered PDF pages locally. Images are normalized to JPEG without original metadata. Extracted text and normalized photos are saved in SQLCipher; raw PDF/camera staging files are removed after import. The app caps documents, stored text and stored images.

Project references contain bounded keyword-ranked excerpts and source filenames. This is not whole-document semantic indexing. Selecting a file boosts its excerpts for the next question. Photos send extracted text by default. **Photo analysis with a vision model** additionally sends up to two selected normalized photos to the configured OpenAI-compatible model. The user must choose a model that accepts images; Koinos's current text-only API rejects image payloads explicitly.

Removing files clears their saved task context and advances the conversation/project context boundary. Project deletion removes associated files, conversations, scoped memories, tasks and routines, including unsubmitted chat actions. Submitted action details and receipts remain as replay evidence, with saved instructions and model-result context cleared. Late receipts still update the outcome without restoring that context. Tombstones prevent old backups from restoring deleted files, projects or their pending actions. Privacy changes also retire an obsolete encrypted recovery snapshot. Exports include project content and photos and are plaintext; configured API credentials remain excluded.

## Build and primary references

Native libraries are compressed in the APK and extracted by Android at installation, preserving arm64-v8a, armeabi-v7a, x86 and x86_64 support while reducing download size. Installed storage requirements remain larger than the download.

- Android WorkManager: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- Unique work: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work
- Android TakePicture: https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture
- Bundled ML Kit OCR: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- OpenAI image inputs: https://developers.openai.com/api/docs/guides/images-vision

Validation results and limits are recorded in `VALIDATION.md`; controlled fixtures are not live account or physical-phone tests.
