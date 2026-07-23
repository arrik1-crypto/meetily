# Meetily for Android

A lightweight, privacy-first companion app to the Meetily desktop app: record meetings on your phone, get a live transcript, take notes alongside it, and generate a summary — all stored locally on the device.

This is a standalone native Android app (Kotlin). It does **not** depend on the desktop Rust core or the archived Python backend.

## Features

- **Live transcription** while you record, with two engines:
  - **System** (default): Android's built-in speech recognition; instant partial results. On Android 12+ you can prefer the fully on-device recognizer (Settings → "Prefer on-device transcription").
  - **On-device Whisper**: whisper.cpp compiled into the app (arm64/x86_64). Fully offline on any device, no per-utterance gaps, better accuracy on names/jargon; text arrives with a few seconds of latency and uses more battery. Download a model in Settings (Tiny 75 MB → Small 466 MB, English-only or multilingual).
- **Global search** across all meetings — titles, transcripts, notes, summaries, and attendees — from the search bar on the main screen.
- **Calendar integration** — start recording during a scheduled event and the meeting's title and guest list pre-fill from your calendar (read-only, toggleable, permission asked on first use); a "From calendar" button lets you pick the event manually.
- **Nocturne design** — the app wears the Nocturne identity from a Claude Design handoff: Inter type, a deep indigo-violet dark theme (the hero) and a "Daylight blue" light theme, depth via glow and layering. Home centers on a record orb ("Tap to record — nothing leaves this phone") over a gradient hairline and RECENT MEETINGS cards with icon wells; recording gets a radial ground, a breathing orb glow, live EQ bars, a 44sp light-weight timer, and outlined pill controls; the meeting page has Summary | Transcript segmented tabs with a staggered reveal after generating; speakers appear as tinted chips throughout. A sun/moon toggle in the home header flips themes instantly (System/Light/Dark also in Settings).
- **Background-safe recording** — capture and transcription run in a foreground service with an ongoing notification (pause/stop actions), so recording continues when you switch apps or lock the phone. The meeting is saved incrementally, so an interrupted session (crash, process kill, swipe-away) is recovered on next launch.
- **Backup & restore** — Settings → Data exports your whole library (meetings + photos + audio + voiceprints) to a single file you control — plain `.zip` or a passphrase-encrypted `.recapbak` (AES-256-GCM, PBKDF2 key derivation) — and restores from either. API keys are never included, and meeting data is excluded from Android's automatic device/cloud backups.
- **Security hardening** — optional **app lock** (fingerprint / face / device PIN required to open the app after it's been in the background), optional **screenshot & recents-preview blocking** (FLAG_SECURE), and **Local-only AI** (on by default): the app refuses AI endpoints outside localhost / your LAN / Tailscale-style private ranges until you explicitly allow cloud providers, and plain-http endpoints are never allowed beyond the private network at all.
- **First-run onboarding, recording-consent notice, and an in-app privacy policy** (Settings → Privacy policy).
- **Rich notes** — type plain notes during the meeting; afterwards the meeting page renders them as formatted Markdown (headings, **bold**/*italic*/`code`, bullets) with **live checklists** — tap a checkbox to toggle it right in the rendered view. An edit mode with a formatting toolbar (B / I / H / list / checklist) writes the markers for you; storage stays plain text, so exports and LLM context are unchanged.
- **File attachments** — attach any file (PDF, deck, doc…) to a meeting from the Files section; stored privately with the meeting, opened with the matching app on tap, included in backups, long-press to remove.
- **Attendees & speaker tagging** — list attendees for a meeting, then tag who said what three ways: tap an attendee **chip** while recording (that speaker sticks to everything said until you tap another), tap any transcript segment to tag it directly (tagging the latest segment also sets the sticky speaker), or use **Suggest speakers (AI)** on the meeting page to label untagged lines from conversational context via your LLM — always previewed, never overwriting manual tags. Speaker names flow into exports and summaries.
- **Auto-detect speakers (experimental)** — with the Whisper engine, an on-device voice-embedding model (sherpa-onnx) groups transcript segments by who's talking, labeling unnamed voices "Speaker 1", "Speaker 2"…; tap any auto-labeled segment and choose "Name Speaker N everywhere…" to rename the whole voice at once. Enable in Settings under the Whisper section and download a speaker model (one-time, 26–97 MB: WeSpeaker ResNet34 is the recommended English default; TitaNet large is the most accurate; TitaNet small and CAM++ English+Chinese are also available). Manual tags always win over auto labels. (Design: DIARIZATION_PLAN.md.)
- **Tags & series** — give meetings comma-separated tags on the meeting page; the home screen grows a filter-chip row with your #tags plus automatically detected recurring-meeting series ("Team standup ×5", matched by title with dates/numbers stripped). Tags also boost "Ask your library" retrieval.
- **Weekly digest** — "Weekly digest" in the home-screen menu compiles your last 7 days: every meeting with its gist, open action items grouped by owner, and starred highlights — instantly and offline; with an LLM configured it upgrades in place to a written digest (themes, decisions, follow-ups, citing meetings). Shareable as text.
- **Transcript editing** — tap any line on the meeting page for "Edit text…" (fix transcription errors; clearing the text deletes the line) and "Split into two…" (separate overlapping speakers: place the cursor where the second voice starts, split, then tag who said the second half — which can feed their voice profile too).
- **Voice profiles** — save a person's voiceprint once and Recap names them automatically in every future meeting (and import) from their first sentence. Three ways to teach it: enroll directly (Settings → Whisper → Voice profiles → "Add a voice…", ~15 s of speech), tap "Save voice" when you name a detected speaker during a meeting, or tag transcript lines after the fact in any meeting that kept its audio — the exact audio behind those lines feeds the profile. Manual tags always override. Voiceprints stay on-device, ride in backups, and are deletable per person.
- **Ask your library** — the sparkle icon on the home screen answers questions across every meeting at once ("what did we decide about pricing?"), with on-device retrieval picking the relevant meetings and answers citing their sources; without an LLM configured you get the matching moments directly, each linking to its meeting.
- **Quick-record entry points** — long-press the launcher icon for Record / Import shortcuts, add the "Record meeting" Quick Settings tile, or place the **home-screen widget** (Record + Import in a Nocturne card).
- **Translate to English (Whisper)** — a Settings toggle runs Whisper's translate task, so speech in any language is transcribed as English text, fully on-device. Works in live recording, imports, and re-transcribes; needs a multilingual model (English-only models ignore it).
- **Topics / auto-chapters** — "Topics" on the meeting page splits the transcript into titled chapters: LLM-detected topic shifts when an endpoint is configured, otherwise an offline pass that finds conversational seams and titles sections by their most distinctive keywords. Chapters render inline in the transcript and the Topics menu jumps to any of them.
- **Action-item reminders** — long-press any action item → "Remind me…" fires a notification at the chosen time, deep-linking back to the meeting; checking the item off cancels it. Reminders survive reboots.
- **Whiteboard OCR** — attached photos are read on-device (bundled ML Kit Latin model, no network): whiteboard text becomes searchable from the home screen and feeds LLM summaries automatically.
- **Meeting nudges (optional)** — with "Suggest recording at meeting start" enabled, a notification at each calendar event's start offers one-tap recording; nothing records until you tap.
- **Background import** — file imports run in a foreground service with a progress notification, so you can leave the screen or lock the phone; tap the completion notification to open the meeting.
- **Follow-ups** — the check icon on the home screen lists every open action item across all meetings; checking one off completes it in its owning meeting (and cancels its reminder), long-press jumps to that meeting.
- **Word-level tap-to-seek** — with the Whisper engine and saved audio, every transcript word carries its own timestamp: tap any word on the meeting page to play from that exact moment (Pixel-Recorder style). Timings ride in the meeting JSON and are dropped for lines you edit.
- **Record device audio (Android 10+)** — long-press the record orb and pick "Device audio" to capture what the phone is playing (webinars, videos, voice notes) via AudioPlaybackCapture, after a one-time screen-capture consent per session. Apps that mark their audio private (most VoIP calls) are excluded by Android; media playback captures fine. Needs the Whisper engine.
- **Meeting audio + synced playback** — with the Whisper engine, each meeting keeps a compact on-device recording (~20 MB/hour, ADTS AAC — playable even if the app dies mid-meeting; toggle under Settings → Whisper). A player bar on the meeting page replays it; tap any transcript line → "Play from here" jumps to that exact moment. Share the audio file, or **re-transcribe** it later (⋮ menu) with whatever better Whisper/speaker models you've since downloaded. Audio is included in backups and deleted with the meeting.
- **Audio file import** — share any recording (from the phone's voice recorder, a call platform, a field recorder) to Recap, or use Import audio on the home screen; it's decoded and transcribed fully on-device with Whisper, including speaker detection when enabled, and becomes a normal meeting. Cancelling keeps the partial transcript.
- **Capture tuning (Whisper engine)** — pick how the phone pre-processes the mic ("Far-field" camcorder tuning often beats the default for a phone on the table; "Raw" disables processing where supported) and choose the input device, including USB and wired external microphones.
- **Highlights** — a star button while recording marks the current moment; long-press any transcript line afterwards to toggle. Highlighted moments get priority treatment in summaries.
- **Ask this meeting** — with an LLM endpoint configured, ask free-form questions on the meeting page ("what did we decide about pricing?"); answers come strictly from the transcript, notes, and summary, and the Q&A thread is saved with the meeting.
- **Summary templates** — choose the summary's shape when generating: General minutes, Action items, Decisions & open questions, Standup, Sales call report, 1:1, Job interview, User research, Retrospective, Project status, or Follow-up email — plus your own **custom templates** (name + free-form instructions, saved on-device, managed from the picker's "Custom…" button).
- **Structured action items** — summaries also produce a checkable to-do list with owners (from your speaker tags or the LLM); check items off, long-press to remove.
- **Photo attachments** — snap the whiteboard from the recording screen or add camera/gallery photos on the meeting page; stored privately with the meeting.
- **Markdown/PDF export** — save any meeting as a .md or .pdf document wherever you choose, including summary, action items, notes, and the timestamped speaker transcript.
- **Auto-titling** — meetings left with the default title get named from their content after recording (instant on-device heuristic, refined by the LLM when configured).
- **On-device AI (embedded)** — Settings → AI summaries → engine "On-device" runs a small LLM (llama.cpp, GGUF) entirely on the phone: summaries, Ask, digest, topics, and speaker suggestions with zero network involvement, ever. Pick a model in the manager (Qwen 2.5 0.5B/1.5B, Llama 3.2 3B; 0.5–2 GB one-time downloads through the background download service). Slower than a server; long meetings are handled with a map-reduce pass — the transcript is condensed section-by-section on-device, then summarized from the ordered notes, so hour-long meetings get full coverage (at the cost of a few extra minutes of compute). Quality sits below big cloud models, privacy sits above everything.
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
- Meeting audio is kept only with the Whisper engine (toggleable); the system-engine path stores transcription only.
- Capturing the other side of a phone/VoIP call is restricted by Android for third-party apps; this app transcribes what the microphone hears (in-person meetings, or calls on speaker).
