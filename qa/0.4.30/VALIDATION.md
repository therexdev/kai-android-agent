# 0.4.30 validation

## Changes

- The foreground chat and saved-task loops allow two consecutive format corrections per step and six per turn. Successful protocol steps reset the consecutive allowance. Rejected fragments are replaced by a neutral placeholder; they are never executed or treated as permission.
- Truncated responses can request 4,096 then 8,192 output tokens, bounded by the catalog limit and existing context budgeting. Corrections request smaller content chunks and retain document IDs and submission receipts. Free-only and account funding choices are preserved.
- Voice echo checks tolerate small recognition errors, expanded abbreviations and truncated final words. Recent played phrases remain available across recognition endpoints; unplayed lookahead is excluded. Ordinary partial interruptions require 350 ms of stable evidence; explicit stop/correction words and genuine final transcripts remain immediate.
- Speech interruption reasons are recorded through the reply's existing privacy epoch. Delayed callbacks after Forget cannot start a fresh diagnostic session for that reply. No audio is recorded.

## Completed checks

- 160 focused JVM tests passed with zero failures, errors or skips. Includes strict response parsing, streamed mock-provider recovery for the reported sequence, token budgets, action approval/receipt guards, echo, preserved corrections, speech chunking and turn handling.
- Android lint passed with zero errors and 19 warnings.

- ARM64 release, x86_64 debug and Android instrumentation APK builds passed. Final JVM rerun: 160 tests passed, zero failures/errors/skips.
- Signed phone APK: package `com.kai.agent.preview`, versionName `0.4.30`, versionCode `37`. Original alpha certificate SHA-256 `a6b0360e7512c4f9f9d6279b52b8ca94d3eb72f6edcf8d7295d117c76b6d3d89` and 16 KB ZIP alignment verified.
- APK SHA-256: `f0c496dd1c4bdda466cb6b619982d7075abeca666edc045c4d34dc5abab0d4ec`.

## Runtime status and limits

Android runtime checks are pending. The first API 30 software emulator reached Android, then hit a kernel watchdog lockup before the APKs were installed. A single-core retry is in progress. No live account or paid inference request has been made, and physical-phone microphone/speaker behavior has not been verified.
