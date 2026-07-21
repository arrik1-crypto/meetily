# Meetily for Android

A lightweight, privacy-first companion app to the Meetily desktop app: record meetings on your phone, get a live transcript, take notes alongside it, and generate a summary — all stored locally on the device.

This is a standalone native Android app (Kotlin). It does **not** depend on the desktop Rust core or the archived Python backend.

## Features

- **Live transcription** while you record, using Android's built-in speech recognition. On Android 12+ you can prefer the fully on-device recognizer (Settings → "Prefer on-device transcription") so audio never leaves the phone.
- **Notes** — type notes in a pane below the live transcript during the meeting, or edit them afterwards.
- **Summaries**, two ways:
  - **On-device (default, zero config)**: an offline extractive summarizer produces key points and detected action items. No network involved.
  - **LLM (optional)**: point the app at any OpenAI-compatible endpoint — Ollama running on your computer (`http://YOUR_PC_IP:11434/v1`), Groq, OpenRouter, etc. Configure URL, model, and API key in Settings. If the LLM call fails, the app falls back to the on-device summary.
- **Local storage** — meetings are JSON files in the app's private storage. Nothing is uploaded anywhere unless you enable the LLM option.
- **Share/export** — share a meeting (summary + notes + transcript) as text to any app.

## Getting the APK

Every push touching `android/` builds an APK via GitHub Actions (`Build Android APK` workflow). Download the `meetily-android-apk` artifact from the workflow run, unzip it, and install `app-release.apk` on your phone (you'll need to allow "install unknown apps" for your browser/file manager).

The APK is signed with a debug key — fine for personal sideloading, not for store distribution.

## Building locally

Requirements: JDK 17+, Android SDK (API 34).

```bash
cd android
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

## Notes and limitations

- Speech recognition uses the device's recognition service (Google's on most phones). Continuous listening is implemented by restarting the recognizer between utterances, so an occasional word can be missed at segment boundaries, and some devices play a beep on each restart.
- On-device (offline) recognition requires Android 12+ and the language pack downloaded in system settings; otherwise the device's default (usually networked) recognizer is used.
- The app records transcription only; it does not save an audio file of the meeting.
- Capturing the other side of a phone/VoIP call is restricted by Android for third-party apps; this app transcribes what the microphone hears (in-person meetings, or calls on speaker).
