# Meetily for Android

A lightweight, privacy-first companion app to the Meetily desktop app: record meetings on your phone, get a live transcript, take notes alongside it, and generate a summary — all stored locally on the device.

This is a standalone native Android app (Kotlin). It does **not** depend on the desktop Rust core or the archived Python backend.

## Features

- **Live transcription** while you record, with two engines:
  - **System** (default): Android's built-in speech recognition; instant partial results. On Android 12+ you can prefer the fully on-device recognizer (Settings → "Prefer on-device transcription").
  - **On-device Whisper**: whisper.cpp compiled into the app (arm64/x86_64). Fully offline on any device, no per-utterance gaps, better accuracy on names/jargon; text arrives with a few seconds of latency and uses more battery. Download a model in Settings (Tiny 75 MB → Small 466 MB, English-only or multilingual).
- **Global search** across all meetings — titles, transcripts, notes, summaries, and attendees — from the search bar on the main screen.
- **Calendar integration** — start recording during a scheduled event and the meeting's title and guest list pre-fill from your calendar (read-only, toggleable, permission asked on first use); a "From calendar" button lets you pick the event manually.
- **Accent colors & theme** — Settings → Appearance offers six accents (Indigo, Teal, Emerald, Amber, Rose, Graphite) that retint the whole app, plus a System / Light / Dark theme toggle.
- **Background-safe recording** — capture and transcription run in a foreground service with an ongoing notification (pause/stop actions), so recording continues when you switch apps or lock the phone. The meeting is saved incrementally, so an interrupted session (crash, process kill, swipe-away) is recovered on next launch.
- **Backup & restore** — Settings → Data exports your whole library (meetings + photos) to a single `.zip` you control, and restores from one. API keys are never included.
- **First-run onboarding, recording-consent notice, and an in-app privacy policy** (Settings → Privacy policy).
- **Notes** — type notes in a pane below the live transcript during the meeting, or edit them afterwards.
- **Attendees & speaker tagging** — list attendees for a meeting, then tag who said what three ways: tap an attendee **chip** while recording (that speaker sticks to everything said until you tap another), tap any transcript segment to tag it directly (tagging the latest segment also sets the sticky speaker), or use **Suggest speakers (AI)** on the meeting page to label untagged lines from conversational context via your LLM — always previewed, never overwriting manual tags. Speaker names flow into exports and summaries.
- **Auto-detect speakers (experimental)** — with the Whisper engine, an on-device voice-embedding model (sherpa-onnx) groups transcript segments by who's talking, labeling unnamed voices "Speaker 1", "Speaker 2"…; tap any auto-labeled segment and choose "Name Speaker N everywhere…" to rename the whole voice at once. Enable in Settings under the Whisper section and download a speaker model (one-time, 26–97 MB: WeSpeaker ResNet34 is the recommended English default; TitaNet large is the most accurate; TitaNet small and CAM++ English+Chinese are also available). Manual tags always win over auto labels. (Design: DIARIZATION_PLAN.md.)
- **Audio file import** — share any recording (from the phone's voice recorder, a call platform, a field recorder) to Recap, or use Import audio on the home screen; it's decoded and transcribed fully on-device with Whisper, including speaker detection when enabled, and becomes a normal meeting. Cancelling keeps the partial transcript.
- **Capture tuning (Whisper engine)** — pick how the phone pre-processes the mic ("Far-field" camcorder tuning often beats the default for a phone on the table; "Raw" disables processing where supported) and choose the input device, including USB and wired external microphones.
- **Highlights** — a star button while recording marks the current moment; long-press any transcript line afterwards to toggle. Highlighted moments get priority treatment in summaries.
- **Ask this meeting** — with an LLM endpoint configured, ask free-form questions on the meeting page ("what did we decide about pricing?"); answers come strictly from the transcript, notes, and summary, and the Q&A thread is saved with the meeting.
- **Summary templates** — choose the summary's shape when generating: General minutes, Action items, Decisions & open questions, Standup, Sales call report, 1:1, Job interview, User research, Retrospective, Project status, or Follow-up email — plus your own **custom templates** (name + free-form instructions, saved on-device, managed from the picker's "Custom…" button).
- **Structured action items** — summaries also produce a checkable to-do list with owners (from your speaker tags or the LLM); check items off, long-press to remove.
- **Photo attachments** — snap the whiteboard from the recording screen or add camera/gallery photos on the meeting page; stored privately with the meeting.
- **Markdown/PDF export** — save any meeting as a .md or .pdf document wherever you choose, including summary, action items, notes, and the timestamped speaker transcript.
- **Auto-titling** — meetings left with the default title get named from their content after recording (instant on-device heuristic, refined by the LLM when configured).
- **Summaries**, two ways:
  - **On-device (default, zero config)**: an offline extractive summarizer produces key points and detected action items. No network involved.
  - **LLM (optional)**: point the app at any OpenAI-compatible endpoint — Ollama running on your computer (`http://YOUR_PC_IP:11434/v1`), Groq, OpenRouter, etc. Configure URL, model, and API key in Settings. If the LLM call fails, the app falls back to the on-device summary.
- **Local storage** — meetings are JSON files in the app's private storage. Nothing is uploaded anywhere unless you enable the LLM option.
- **Share/export** — share a meeting (summary + notes + transcript) as text to any app.

## Getting the APK

Every push touching `android/` builds an APK via GitHub Actions (`Build Android APK` workflow). Download the `meetily-android-apk` artifact from the workflow run, unzip it, and install `app-release.apk` on your phone (you'll need to allow "install unknown apps" for your browser/file manager).

The APK is signed with a debug key — fine for personal sideloading, not for store distribution.

## Building locally

Requirements: JDK 17+, Android SDK (API 34), NDK 27 + CMake 3.22 (installed automatically by the Android Gradle Plugin). The native module fetches whisper.cpp (pinned release tarball) at CMake configure time, so the first build needs network access to github.com.

```bash
cd android
./gradlew testReleaseUnitTest   # pure-logic unit tests
./gradlew assembleRelease       # debug-signed sideload APK
```

## Releasing (signed, Play-ready)

The sideload APK from `assembleRelease` is signed with the debug key. For a real release, provide an upload keystore — the build picks it up automatically:

- **Locally:** create `android/keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, `keyPassword` (this file is gitignored), then run `./gradlew bundleRelease` for a signed `.aab`.
- **CI:** the `Release Android (signed AAB)` workflow (manual dispatch) builds a signed AAB + APK from repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

When no keystore is configured the release build falls back to the debug key, so day-to-day CI stays green without secrets.

## Deploying to the Play Store — checklist

- Provide an upload keystore (above) and enable Play App Signing.
- Complete the Play **Data safety** form. The app stores meetings on-device; data leaves the device only when you enable an LLM endpoint (transcript/notes sent to the endpoint you configure) — declare that.
- Provide a privacy policy URL (the in-app policy screen text is a starting point).
- Recording-consent laws vary by jurisdiction; the app shows a one-time consent notice before the first recording, but you are responsible for lawful use.

```bash
cd android
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## Notes and limitations

- Speech recognition uses the device's recognition service (Google's on most phones). Continuous listening is implemented by restarting the recognizer between utterances, so an occasional word can be missed at segment boundaries. The recognizer's start/stop chime is muted during recording by default (Settings → "Mute recognizer chime while recording"); turn that off if you need phone audio (e.g. a call on speaker) to stay audible while recording.
- On-device (offline) recognition requires Android 12+ and the language pack downloaded in system settings; otherwise the device's default (usually networked) recognizer is used.
- The app records transcription only; it does not save an audio file of the meeting.
- Capturing the other side of a phone/VoIP call is restricted by Android for third-party apps; this app transcribes what the microphone hears (in-person meetings, or calls on speaker).
