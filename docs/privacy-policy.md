# Recap — Privacy Policy

_Last updated: July 23, 2026_

Recap is a privacy-first meeting recorder, transcriber, and summarizer for
Android. It records and processes your meetings **on your device**.

## What is stored

Meeting audio is transcribed to text on the device; the app saves the
transcript, your notes, summaries, attendee names, speaker tags, action
items, voice profiles you enroll, and any photos or files you attach. All of
this is stored in the app's private storage on your phone. No account is
required, and the developer operates no servers: we never receive, collect,
or have access to any of your data.

## What leaves your device

By default, nothing. Three optional features can send data out, and only
when you turn them on:

- **AI endpoint (optional).** If you configure an OpenAI-compatible endpoint
  (such as Ollama on your own computer, Groq, or OpenRouter), the meeting
  transcript, your notes, and attendee names are sent to that endpoint to
  generate summaries and answers. Data handling is then governed by whoever
  operates that endpoint. The embedded on-device AI engine, by contrast,
  never touches the network, and "Local-only AI" is on by default — the app
  refuses AI endpoints outside your private network.
- **Cloud speech recognition (optional path).** The system transcription
  engine may use your device's speech-recognition service, which on many
  phones may process audio in the cloud. Use the on-device Whisper, Parakeet,
  or Nemotron engines to keep audio fully on the device.
- **Model downloads.** Enabling on-device transcription or AI models
  downloads model files from Hugging Face or GitHub. These downloads send no
  personal data; they are ordinary file fetches.

## Sharing and export

When you share or export a meeting, a clip, or a backup, you choose where it
goes; the app never sends anything anywhere on its own. Exported backups can
be encrypted with a passphrase (AES-256).

## Calendar

If you enable calendar pre-fill or meeting nudges, the app reads event
titles and attendees (read-only) to pre-fill meetings and remind you to
record. It never writes to your calendar and never uploads calendar data.

## Microphone and screen-capture permissions

The microphone is used only while you are recording a meeting or enrolling a
voice profile, always behind an explicit start action and a visible ongoing
notification. The "record device audio" mode uses Android's screen-capture
consent dialog solely to capture audio from other apps (for example a
webinar); no screen video is captured or stored.

## Security

Meeting data is excluded from Android's device and cloud backups. You can
require your fingerprint, face, or device PIN to open the app, and block
screenshots and the recents preview.

## Deletion

Deleting a meeting removes its data, audio, and photos from the device.
Uninstalling the app removes everything.

## Children

Recap is not directed at children under 13.

## Consent to record

Recording people may require their consent depending on your jurisdiction.
You are responsible for recording lawfully.

## Contact

Questions about this policy: open an issue on the app's GitHub repository.

## Changes

If this policy changes, the updated text will be published at this same URL
with a new "last updated" date.
