# kai for Android

A local-first AI assistant and agent for Android, powered by the Koinos AI model network.

**[Download the latest APK](https://github.com/therexdev/kai-android-agent/releases/latest)** · **[Koinos AI](https://koinosai.com/)**

## Current release: 0.4.28

- Model selection is a compact dropdown in **Account**.
- **GPT OSS 120B** is the default for new sign-ins and previously empty model selections.
- An existing saved model choice is preserved. Choosing a different model saves it for future chats and tasks.
- The dropdown shows model availability; unavailable models cannot be selected. Free-credit eligibility and input/output token prices remain visible for the selected model.

The catalog comes from your signed-in Koinos AI account. GPT OSS uses the catalog ID `akash-gpt-oss-120b`; availability and prices can change.

## What the app does

| Feature | Details |
| --- | --- |
| Chat and voice | Streamed replies, Markdown, selectable characters, speech recognition, and local speech output. |
| Personal memory | Encrypted local memory, reviewable suggestions, corrections, forgetting, and offline semantic retrieval. |
| Tasks and routines | Saved task state, resumable work, Android WorkManager scheduling, and explicit action approvals. |
| Projects and files | Local workspaces, text extraction, PDF reading, and Latin text OCR. |
| Web research | Search and page reading from the phone using the chosen model; internet is required. |
| Connected apps | Composio integrations configured with the phone owner's own project key and user identity. |
| Shared AI credits | Website account sign-in, free allowance, linked-wallet balances, and the live model catalog. |
| Diagnostics | User-initiated chat report export with credential filtering. Review personal content before sharing. |

The phone owns conversation history, memory, task records, files, and permissions. Model inference runs through Koinos AI; on-phone language-model inference is not included in this build. A desktop KAI installation is not required.

## Install or update

1. Open [Releases](https://github.com/therexdev/kai-android-agent/releases/latest) and download `KAI-Android-0.4.28.apk`.
2. Open the APK on an **ARM64 Android 9 or newer** device. If Android asks, allow installation from the app you used to download it.
3. Install over the existing **kai** app. Keep the existing installation to retain its encrypted local data.
4. Open **Account** in the top bar, choose **Sign in / Create account**, and complete email or Google sign-in in your browser.
5. Approve the phone's displayed code, return to kai, and refresh the account if needed.
6. GPT OSS 120B is selected by default unless you already saved another model. Use the dropdown to change it.

The package remains `com.kai.agent.preview` for update continuity. This release is version code **35**. APK signing continues the installed alpha identity; it is an early-access distribution, not a Play Store release.

## Credits and connections

Sign in with the same account you use on the Koinos AI website. Free allowance and purchased credits are shown separately. To spend purchased credits, select their linked wallet and turn off **Use free credits only**. Prices are quoted in credits per million input/output model tokens.

Wallet connection and credit purchases open the website account in the system browser. KOIN Vault controls wallet approvals; the app enables the Vault button when the account service advertises support.

Composio connections use a separate project API key entered by the phone owner. Reusing existing connections also requires the same Composio user ID. No shared project key, signing key, user account, or private phone data is included in the public source.

## Privacy and limits

Local state uses SQLCipher with Android Keystore-backed protection. Sign-in controls inference and billing; it does not synchronize your conversations, memories, or connected apps. Your selected model receives the context needed for each request. Web search and connected-service actions contact their respective services.

Microphone use is explicit and stops when the app leaves the foreground. Android may defer scheduled work. Unknown external-action outcomes are preserved and are not automatically replayed. Exported conversation/workspace files are not encrypted and can contain personal information. Speech quality and responsiveness depend on the device; physical-phone acceptance is separate from automated tests.

## Source and development

This repository contains the standalone Android project. Release assets include the signed ARM64 APK, matching source archive, and SHA-256 checksums.

Use JDK 17 or 21, Android SDK platform 36 / build-tools 36.0.0, and the checked-in Gradle 8.13 wrapper. Configure `sdk.dir` in your local `local.properties` or set `ANDROID_HOME`.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew assembleRelease -PkaiTargetAbi=arm64-v8a
```

`assembleRelease` produces an unsigned APK. Private update-signing material is deliberately excluded; independently signed builds cannot update the existing alpha installation. Do not replace your installed app with a differently signed build if you need to keep its data.

## Documentation

- [Account, models, and credits](docs/ACCOUNT-AND-CREDITS.md)
- [Connections setup](docs/COMPOSIO-SETUP.md)
- [Conversations and workspace](docs/CONVERSATION-AND-WORKSPACE.md)
- [Memory architecture](docs/MEMORY-ARCHITECTURE.md)
- [Web research](docs/WEB-RESEARCH-AND-CORE.md)
- [Chat reports](docs/CHAT-REPORTS.md)
- [Build history](docs/BUILD-HISTORY.md)
- [0.4.28 validation](qa/0.4.28/VALIDATION.md)

Bundled third-party notices are retained with their assets under `app/src/main/assets`; the isolated speech runtime remains under `app/libs`.
