# Recap — Privacy Policy

_Effective date: [effective date — set before publishing, and match
`privacy_policy_effective_date` in the app]_

<!-- This page and the in-app policy (privacy_policy_body in
     android/app/src/main/res/values/strings.xml) must say the same thing.
     Change both together. -->

Recap records, transcribes and summarises meetings on your phone. This
policy explains what the app stores, what can leave your phone and when, and
how to delete it.

## In short

- No account, no ads, and no analytics or tracking of Recap's own. The
  developer runs no servers and receives nothing from the app.
- Your meetings stay on your phone unless you send them somewhere: by sharing
  or exporting, by switching to Android's speech service, or by connecting an
  AI endpoint.
- Two things use the network without your meeting content: model downloads
  you ask for, and diagnostics that Google's ML Kit (which reads text in
  photos) sends to Google.

## What is stored on your phone

All of this is kept in Recap's private storage, which other apps cannot read:

- Meetings: title, date, transcript with timings and speaker names, the
  language it was spoken in (when known), where an unfinished transcription
  stopped, your notes, summaries (one for each summary style you use),
  action items and reminders, highlights, topics, tags and attendee names.
- Meetings you delete, kept in Recently deleted with their audio, photos
  and files for 30 days unless you erase them sooner (see Deleting your
  data).
- Meeting audio, when kept. On-device transcription keeps it by default.
- Files you import: a recording, the sound of a video (only the sound is
  kept when it can be taken out on the phone; otherwise the whole file),
  or a WebVTT or SRT transcript, which becomes a meeting without audio.
- A clear-voice copy of a meeting's audio, if you make one: the audio with
  background noise removed on the phone. Nothing is sent anywhere.
- Photos you attach, the text read from them, and files you attach.
- Voice profiles you save: a voiceprint (numbers describing how a person
  sounds, used to recognise them in later meetings) and the short recordings
  it was made from. Ask people before saving their voice.
- Details from your calendar saved with a meeting: the event title, attendee
  names, and the name and time of an earlier event you link it to.
- Downloaded transcription and AI models.
- Settings, including an AI endpoint address and API key if you enter them.
- Reports of the last three crashes or unexpected closes, with Android's record
  of a native crash where there was one.

## Backups

Recap opts out of Android's cloud backup and device-to-device transfer, so
Android does not copy any of this off the phone. The in-app backup
(Settings → Data) writes a file to a place you choose. It contains meetings,
audio, photos, attached files and voice profiles, can be encrypted with a
passphrase (AES-256), and never includes settings or API keys.
Clear-voice copies are not included; they can be made again from the
recording. Meetings in Recently deleted are not included either, nor are
their files.

## Transcription

By default Recap transcribes on the phone with a model you download once
(Whisper, Parakeet or Nemotron). Until a model is downloaded, meetings are
recorded as audio only and can be transcribed later. Neither the audio nor the
words leave the phone. Speaker detection and voice profiles also work entirely
on the phone. Imported recordings and videos are transcribed the same way;
the sound of a video is taken out on the phone, and an imported WebVTT or SRT
transcript is read on the phone. A transcription that stops part-way can be
continued later from where it stopped, also on the phone. Clear voice, which
removes background noise from a recording, uses a small model built into the
app and runs on the phone too.

If you turn off on-device transcription in Settings, Recap uses Android's
speech service instead. On most phones that service is Google's, and Android
documents that it is likely to send audio to remote servers; the speech
provider's own privacy policy then applies. With "Prefer on-device
transcription" on, phones running Android 12 or later that have an offline
speech model use that instead. This engine keeps no recording.

## Reading text in photos

Text in photos you attach, such as a whiteboard, is read on the phone with
Google's ML Kit text recognition, so you can search it and use it in
summaries. The photos and their text are not sent anywhere for this. ML Kit
does send Google diagnostic and usage-analytics information: details of the
device, the app and the network connection (such as phone model, Android
version, app version and connection type) and performance measurements,
which may include identifiers used for those diagnostics. ML Kit starts with
the app, so this can happen even if you never attach a photo.
Google describes it in its
[ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).

## AI summaries and questions

Summaries, answers, enhanced notes, titles and digests are made on the phone
unless you set up an AI endpoint: by default with a built-in summariser, or
with an AI model you download, which never uses the network.

If you turn on an AI endpoint, the meeting's transcript, notes, title,
attendee names, text from its photos, your name (if you entered it) and the
instructions of the summary style are sent to the address you entered, with
your API key. That happens whenever you use an AI feature, or after every
meeting if you turn on automatic summaries or titles and agree to send them.
Asking across your library, a pre-meeting brief or a weekly digest sends
excerpts from several meetings.

Local-only AI is on by default: Recap then sends only to this phone or your
private network (for example Ollama on your computer), and plain http is never
used outside your private network. If you turn Local-only AI off and use a
cloud provider such as Groq or OpenRouter, that provider receives the data and
its own terms and privacy policy apply.

## Model downloads

Models are downloaded only when you ask, over https, from Hugging Face
(huggingface.co and its download servers) or GitHub (github.com and its
download servers). Like any website, they see your IP address, which file is
requested and the technical details Android sends with a web request, such as
the Android version and phone model. Their own privacy policies apply. Nothing
about your meetings is sent.

## Crash and bug reports

If Recap crashes, or Android closes it because it stopped responding, ran
short of memory or used too many resources, a report is saved on the phone:
the app version, Android version, phone model and the technical error, which
can include fragments of what the app was working on. When Android recorded
the close (Android 11 and later), the report also notes which kind of
on-device work and which model were running, how much memory Recap was using
and how much memory the phone has; for a native crash (Android 12 and later),
Android's full crash record is kept with it on the phone, and only a summary
of the error and where it happened goes into the report. Recap never sends it.
On the next launch you can share it through your own share sheet, to whoever
you choose, or decline. Separately, if your phone shares usage and diagnostics
with Google, Android may report crashes and freezes of any app, Recap
included, to Google Play, which shows the developer aggregated statistics.
That is a setting of your phone, not of Recap.

Report AI output, offered with summaries, answers and other generated text,
opens a draft email to the developer in your own email app. It holds the
reason you pick, your comment, where the text was shown, the app and Android
versions, and the model and summary style used; the generated text itself is
added only if you tick the box. Nothing is sent unless you send that email. If
no email app can open it, the report is copied to the clipboard instead, and
Recap keeps no copy of it.

## Sharing and export

When you share or export a meeting, audio, a clip, action items, a digest or a
backup, it goes where you choose and is then outside Recap's control. Recap
never shares your content on its own.

Exports of every kind (Markdown, PDF, Word, subtitles, and your whole library
as Markdown files in a folder) are written only to a file or folder you pick
in Android's picker, and Recap sends them nowhere. If you pick a location
backed by a cloud service, that provider's own terms apply. For a library
export, Recap keeps access to the folder you picked only while the export
runs; if Android stops Recap during an export, Recap gives the access back
the next time it starts.

## No analytics, ads or accounts

Recap has no sign-in, shows no ads, and contains no analytics or tracking of
its own. The developer does not receive or sell your data, and nothing is
shared beyond the ML Kit diagnostics described above; the developer sees only
reports or messages you choose to send.

## Permissions

- **Microphone:** to record meetings (with a notification showing while a
  recording runs) and voice profiles (a 15-second capture on screen in
  Settings), only when you start one.
- **Screen capture:** only if you choose to record audio from other apps, such
  as a webinar. Android asks each time, and only the audio is captured, never
  the screen.
- **Calendar (optional):** read-only, as described below.
- **Notifications:** recording, transcription, clear-voice, passage
  removal and download progress, playback controls, reminders, meeting
  nudges, and storage clean-up notices if you turn automatic clean-up on.
  On Android 16 a recording, and work you start such as an import, can
  show as a Live Update in the status bar and at the top of the lock
  screen; you can turn Live Updates off for Recap in Android's Settings.
  When your lock screen hides notification contents, recording and
  transcription notifications show no meeting or file names there. The
  playback controls (on the lock screen, in the notification shade, and
  on connected headsets, cars and watches) show "Meeting audio" and
  "Recap", never the meeting's title.
- **Internet:** model downloads, your AI endpoint if you set one up, and ML Kit
  diagnostics.
- **Network state:** Recap uses it only to warn before a large model download
  over mobile data, and keeps and sends nothing about the network. Google's ML
  Kit also uses it, and its diagnostics include the type of connection (see
  Reading text in photos).
- **Running in the background, starting after a restart, exact alarms and
  keeping the phone awake:** so recordings, transcriptions, clear-voice
  copies, passage removals and downloads finish with the screen off, a
  meeting you play keeps playing when you leave the app or lock the
  phone, reminders arrive on time, and a once-a-day job erases meetings
  whose 30 days in Recently deleted are up and runs automatic clean-up if
  you turn it on.

Photos come from your camera app or the system picker; Recap has no camera or
photo-library permission. Files you import come from the share sheet, "Open
with" or the system picker, which give Recap access to those files only;
Recap has no storage permission.

## Calendar

If you allow calendar access, Recap reads event titles, times and attendees on
the phone to name meetings, suggest recording when one starts, and link
follow-on meetings. Attendee email addresses are only used to make up a name
when an attendee has none, and are not saved. Recap never changes your
calendar and never sends calendar data anywhere, except as part of a meeting
you send to an AI endpoint.

## Deleting your data

Deleting a meeting moves it, with its transcript, audio, clear-voice copy,
photos and attached files, to Recently deleted (Settings → Data), where you
can restore it for 30 days. Until then it stays on this phone, left out of
your library, search, exports and backups, and nothing is sent anywhere.
After 30 days, or at once if you choose Delete permanently or Empty there,
the meeting and all its files are erased from the phone. You can also
remove only a meeting's audio and keep its transcript, from the meeting's
menu or from Settings → Data → Storage and clean-up. That Storage screen
also deletes downloaded models you no longer use, and can turn on automatic
clean-up: once a day, on the phone, it removes the audio of meetings, or
moves the meetings themselves to Recently deleted, once they are older than
a number of days you choose. Automatic clean-up is off unless you turn it
on, and leaves starred and recently changed meetings alone. Erasing a
meeting or removing its audio also deletes its clear-voice copy, and the
copy can be deleted on its own from the player. Remove passage, in a
transcript line's menu, deletes the words of the stretch you choose and,
on the phone, replaces the recording with a copy in which that stretch is
silent; the earlier recording, clips cut from it and its clear-voice copy
are deleted. Saved voices are deleted under Settings → Voice profiles, and
downloaded models also from their lists in Settings. To remove everything, uninstall the app:
Android's Manage space button for Recap opens Recap's Storage screen rather
than clearing all of its data. Copies you exported or shared, and anything
sent to a speech service or AI endpoint, are outside Recap and have to be
deleted there.

## Security

You can require your fingerprint, face or screen lock to open Recap, and hide
meeting content from screenshots and the recent-apps view. Every AI endpoint
is checked against your Local-only AI setting before anything is sent, and
model files are only accepted over https.

## Children

Recap is not directed at children under 13. Apart from the ML Kit
diagnostics described under Reading text in photos, which include
per-installation identifiers and go to Google, Recap collects no personal
information from anyone: what you create stays on the phone unless you send it
somewhere.

## Recording other people

Recording people can require their consent depending on where you are. You
are responsible for recording lawfully.

## Changes

If this policy changes, the new version comes with an app update and is
published at the same web address with a new effective date.

## Contact

Questions or requests about privacy: [support email — the address on Recap's
Google Play listing, also `support_email` in the app]
