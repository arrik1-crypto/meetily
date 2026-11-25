# Recap device test plan: 3.22.0-beta1 (versionCode 117)

Run this on your own phone before every upload. It condenses the several
hundred checks in [PLAY_RELEASE.md](PLAY_RELEASE.md) §11 "Device checks"
into one prioritised pass on **one arm64 phone running Android 15 or 16**,
plus optional extras: an older phone, an Android 17 beta, the Play build
(AAB) and accessibility. What blocks the first submission as a whole is in
[RELEASE_BLOCKERS.md](RELEASE_BLOCKERS.md).

- **Build under test:** 3.22.0-beta1 (versionCode 117), the pre-release
  review fixes plus the maintainer's version bump. Use the CI build of the
  bump commit: `android/dist/meetily-android.apk` on the Android branch, or
  the `v3.22.0-beta1` GitHub prerelease asset (arm64-v8a only). The x86_64
  APK is only in that run's `meetily-android-apk` workflow artifact. A
  build that still reports 3.21.0-beta1 (116) is the unbumped tree: test
  it if you must, but expect 116 wherever this plan says 117.
- **Not the shipping build:** until the support address and the policy's
  effective date are filled in (RELEASE_BLOCKERS.md A5, A6), the app still
  shows `support@example.com` and "[effective date]". Run section R on the
  build you finally upload.
- **Budget:** about 90 minutes for the core path. Items marked **(+)** are
  extras: skip them first. With only 60 minutes, do 1.2 to 1.5 of Setup,
  P0, then P1-1b (import at least `team_sync_long.m4a`), P1-6 (Re-time
  words), P1-4 (Remove a passage, without its clear-voice line), P1-9
  (crash recovery) and P1-11 ("Keep meeting audio" off). That order
  follows what the steps need from each other and where a failure would
  lose data: Re-time words has never run on a device, Remove a passage and
  crash recovery rewrite or recover meeting data, and P1-11 deletes audio
  by design.

| Part | Core | With (+) |
|---|---|---|
| 1. Setup (after the untimed prep in 1.0) | 12 min, plus model downloads in the background | 18 min |
| 2. P0 smoke test | 33 min | 40 min |
| 3. P1 riskiest paths (Batches 2 to 7 and the review fixes) | 35 min | 63 min |
| 4. P2 platform specifics | 8 min | 25 min |
| W. Wrap-up: clean-install restore | 5 min | 10 min |
| R. Final-build re-smoke (on the build you upload) | 5 min | 5 min |

## How to read a step

- Each step is an action, then **Expect:** what the app must do. Text in
  quotes is what the app shows, checked against `res/values/strings*.xml`
  of the review fixes; `…` stands for a variable part (a name, a time, a
  count) or a sentence cut short. Wording that differs from this plan is a
  minor finding, not a failure. Every expectation comes from reading the
  code: nothing here has been watched on a device yet.
- **Covers:** names the PLAY_RELEASE.md §11 "Device checks" items the step
  satisfies, as `GROUP n` (the n-th checkbox in that group; the legend is
  at the end), each marked **full** or **partial**. Tick an item in
  PLAY_RELEASE.md only when every step that covers it in full has passed.
  For a partial one, add a dated note beside the item ("3.22.0-beta1, one
  phone, no VLC") instead of ticking it. **PD** points to §11 "Product
  decisions": behaviour that is deliberate, so do not file it as a bug.
- **Stop rule:** any P0 failure blocks the upload. A P1 or P2 failure
  blocks it if it crashes, loses or corrupts meeting data, or shows meeting
  content where the app promises not to (lock screen, media controls);
  otherwise triage it.
- **Two habits the app expects:**
  - In Settings, most switches take effect only when you tap **Save
    settings** at the bottom. Pickers and dialogs save at once (the theme,
    "Hide content from screenshots", the **On-device** / **Private
    endpoint** choice, text size, spoken and summary language, the
    automatic-summary switch with its style and timing, microphone tuning
    and input device, and model picks).
  - On a transcript, tapping a **word** seeks playback. To open a line's
    menu ("Who said this?"), tap the line's **speaker or time label** (or
    the space after its text). Long-pressing a line toggles its highlight.
- **Play Console demo videos:** the declarations in PLAY_RELEASE.md §5 need
  videos recorded on an Android 15+ phone, and this pass walks every one of
  those paths. Where a step says **Video**, run
  `adb shell screenrecord /sdcard/<name>.mp4` before it (Ctrl-C to stop; at
  most 3 minutes), then `adb pull /sdcard/<name>.mp4`. Use test data only.

## 1. Setup

### 1.0 Before the session (on the computer, not timed)

- [ ] **S0.1 Get the APKs and check the signer.** You need the 3.22.0-beta1
  APK (above) and, for the upgrade in 1.1, the previous release, taken
  from git, where nothing can replace it:
  `git fetch origin && git show 818d523:android/dist/meetily-android.apk > old.apk`
  (the 3.21.0-beta1 build of `9476ee2`). Not `gh release download
  v3.21.0-beta1`: CI re-uploads that release's asset (`--clobber`) for
  every build whose version still reads 3.21.0-beta1, so once the review
  fixes reach the Android branch before the bump it holds the new code.
  Check that `aapt2 dump badging old.apk | head -1` (Android build-tools,
  where `apksigner` is too) shows `versionCode='116'` and
  `versionName='3.21.0-beta1'`.
  Run `apksigner verify --print-certs meetily-android.apk | grep SHA-256`
  on both and compare with the upload certificate in PLAY_RELEASE.md §1
  (`83:97:D1:…:F0:34`). `apksigner` prints the digest as lowercase hex
  without colons and `keytool -list -v -keystore <upload keystore>` as
  colon-separated uppercase, so compare the hex digits only.
  **Expect:** the same SHA-256 for both. A different one means CI fell
  back to its throwaway debug key (the build log warns
  "ANDROID_KEYSTORE_BASE64 is not set", and the release notes say so):
  stop and fix the signing secrets first.
  Covers: no Device checks item (the related owner item is §11 "Android
  developer verification": register the upload key's SHA-256).
- [ ] **S0.2 Make the test files.** You need one English recording you may
  use (`SRC`), 25 minutes or more, with two or more voices, and a printed
  page or whiteboard with a few distinctive words on it (for the photo in
  P0-2).

```bash
mkdir -p ~/recap-test/media && cd ~/recap-test/media
SRC=/path/to/english-meeting.m4a
ffmpeg -i "$SRC" -t 1500 -vn -c:a aac -b:a 96k team_sync_long.m4a            # 25 min
ffmpeg -ss 300 -t 240 -i "$SRC" -vn -c:a libmp3lame -b:a 128k short_call.mp3 # 4 min
ffmpeg -ss 600 -t 60 -i "$SRC" -vn -ar 44100 -ac 2 stereo_44k.wav            # 1 min, 44.1 kHz stereo
ffmpeg -f lavfi -i testsrc=size=640x360:rate=25 -ss 900 -t 120 -i "$SRC" \
  -map 0:v -map 1:a -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest talk_video.mp4
ffmpeg -f lavfi -i testsrc=size=640x360:rate=25 -t 5 -c:v libx264 -pix_fmt yuv420p -an silent_video.mp4
printf 'Agenda: budget, supplier quote.\n' > notes.txt
cat > zoom_call.transcript.vtt <<'EOF'
WEBVTT

1
00:00:01.000 --> 00:00:04.000
<v Ana Lopez>Morning everyone, let's start with the budget.

2
00:00:04.500 --> 00:00:09.000
<v Ben Okafor>The café supplier sent the new quote yesterday.

3
00:00:09.500 --> 00:00:13.000
<v Ana Lopez>Ben, can you send it round by Friday?
EOF
```

The underscores are deliberate: imported meetings must be named with
spaces ("team sync long"), and the VTT must become "zoom call".

- [ ] **S0.3 Prepare the phone.** Android 15 or 16, arm64. Wi-Fi on, mobile
  data available (for S2.2), battery above 50 %, a device screen lock set
  (app lock needs one), Do Not Disturb off, media volume up, screen timeout
  2 minutes or more, USB debugging on and `adb devices` listing the phone.
  Add Recap's **Record meeting** tile to Quick Settings after S2.1 (edit
  the Quick Settings panel).

### 1.1 Upgrade from 3.21.0-beta1 (5 min)

Sideloaded beta users go from 116 to 117, so check the upgrade on a small
library made with the old build. Skip this if the phone holds a Recap from
Play (Play re-signs with its own key, so an adb install over it fails by
design).

- [ ] **S1.1 Keep your real library.** If the installed Recap holds
  meetings you want to keep: note its settings (models, engine and
  endpoint, clean-up rule, app lock), since backups leave out settings and
  API keys, and restore anything in Recently deleted you want, since
  backups leave those out too. Then Settings → Data → **Export backup** →
  **Encrypt** (plain zip leaves a readable copy of every meeting in
  Download) → save → passphrase twice → **OK**, and
  `adb pull /sdcard/Download/<file> ~/recap-test/real-library.recapbak`.
  **Expect:** "Backup saved". The 3.21.0-beta1 picker suggests
  `meetily-backup.recapbak`; this build suggests `recap-backup.recapbak`.
  This file is your way back in W4.
  Covers: setup.
- [ ] **S1.2 Build a small library on 3.21.0-beta1.**
  `adb uninstall com.recap.mobile; adb install old.apk`, open it, **Choose
  later**, download Whisper Base in Settings → Transcription, record 1
  minute, let it transcribe, summarise it, edit one line's text, delete a
  second short recording so it sits in Recently deleted, and tap **Save
  settings** once in Settings (that can store the old example endpoint
  address).
  **Expect:** nothing new; this is the old build.
- [ ] **S1.3 Upgrade.** `adb install -r meetily-android.apk`, then
  `adb shell dumpsys package com.recap.mobile | grep -E 'versionName|versionCode|targetSdk'`.
  **Expect:** `Success`, `versionCode=117`, `targetSdk=36`,
  `versionName=3.22.0-beta1`. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` means a
  different signing key: see S0.1.
- [ ] **S1.4** Open Recap.
  **Expect:** no crash and no crash prompt. The meeting plays, shows its
  summary, and keeps its edited line; Recently deleted still holds the
  deleted one. In the English meeting the line menu offers "Re-time words"
  even on lines you never edited: deliberate (PD: Re-time words, lines of
  unknown origin). Settings → Summaries → **Private endpoint**: the address
  field is empty, with the example only as a hint (3.22.0-beta1 clears the
  saved example while AI is off).
  Covers: RF 31 (partial: the upgrade half); the prerequisite for every
  upgrade item (PRIV 2, SHERPA 4), no single item.

### 1.2 Clean install and first run (5 min, then downloads in the background)

Play buyers start from a clean install, so P0 runs on one.

- [ ] **S2.1** `adb uninstall com.recap.mobile && adb install meetily-android.apk`
  (this deletes everything Recap held on the phone, models included).
- [ ] **S2.2a** Open Recap: "Welcome to Recap". Press Back, then rotate the
  phone, tap the home header's sun/moon button, and switch Android's dark
  mode.
  **Expect:** the welcome screen does not reopen. Force-stop
  (`adb shell am force-stop com.recap.mobile`) and reopen: it is back.
  Covers: RF 11 (full).
- [ ] **S2.2b** **Get started**.
  **Expect:** "Set up on-device AI" with "This phone: about … GB of RAM ·
  … free", a Transcription and a Summaries suggestion each with its "… MB
  download", and **Download both**, **Transcription only**, **Choose
  later**. On a 6 GB or larger phone with an English locale the
  suggestions are "Small Q (English, compact)" and "Qwen 2.5 1.5B
  (recommended)" (about 1.3 GB together).
  Covers: SETUP 1 (partial: this phone only).
- [ ] **S2.2c** Turn Wi-Fi off with mobile data on, tap **Download both**.
  **Expect:** "Download over mobile data?" with "You seem to be on mobile
  data or another metered connection. This is about … and may count
  against your data allowance." Cancel it, turn on airplane mode, tap
  **Download both** again: "You're offline. Connect to the internet to
  download, or choose later." Turn airplane mode off and Wi-Fi on.
  Covers: SETUP 3 (full).
- [ ] **S2.3** On Wi-Fi tap **Download both**; allow notifications when
  Android asks (Android 13+).
  **Expect:** "Downloading in the background. Progress is in the
  notification and in Settings.", then the home screen and a "Downloading
  … model" notification, the transcription model first (on Android 16 a
  Live Update with a percentage chip). **Video** (dataSync): record the
  shade during the download.
  Covers: SETUP 6 (full), LU 8 (partial: first-run download), PRIV 4
  (partial: these two downloads).
- [ ] **S2.4 Gate: wait for both models.** Before any force-stop and before
  P0-1, wait for both "… model ready" notifications (or check Settings →
  Transcription and Summaries). ModelDownloadService does not restart
  after its process dies, so a force-stop mid-download leaves it
  unfinished: without the transcription model P0-3 gets no transcript, and
  without the summary model onboarding never switches on "Use an LLM for
  summaries", so P0-4 gets no on-device summary.
  **Expect:** Settings → Summaries shows "Use an LLM for summaries" on with
  **On-device** selected. If a download was killed, start it again in
  Settings and switch "Use an LLM for summaries" on by hand.
- [ ] **S2.5 (+)** For speaker labels in P1: Settings → Transcription →
  "Auto-detect speakers (experimental)" on → **Speaker models…** →
  "WeSpeaker ResNet34 (English, recommended)" → **Save settings**.
- Do **not** download the word re-timing model: P1-6 tests its download
  offer.

### 1.3 Push the test files (1 min)

- [ ] `adb shell mkdir -p /sdcard/Download/RecapTest && adb push ~/recap-test/media/. /sdcard/Download/RecapTest/`
  **Expect:** the files appear in the Files app under Download → RecapTest.

### 1.4 Keep logcat running (1 min)

```bash
adb logcat -G 16M     # a larger buffer, where the phone allows it
adb logcat -c
# Terminal 1: everything, to attach to a failure report
adb logcat -v threadtime > ~/recap-test/logcat-$(date +%Y%m%d-%H%M).txt
# Terminal 2: what to watch while testing
adb logcat -v time | grep --line-buffered -E 'com\.recap\.mobile|meetily_|SherpaEmbedder|RecapMeetingPlayback|sherpa-onnx|FATAL EXCEPTION|AndroidRuntime|DidNotStopInTime'
```

Recap logs only the shape of its work, never transcript or prompt text.
Outside the steps that ask for a crash, any of these is a failure even if
the screen looks fine: `FATAL EXCEPTION` with `Process: com.recap.mobile`,
`ANR in com.recap.mobile`, `ForegroundServiceDidNotStopInTimeException`,
`UnsatisfiedLinkError`, `did not load` (tag `meetily_native`), `speech gate
off` (tag `meetily_vad`).

### 1.5 The test library you will build

| In this plan | Made in | Used by |
|---|---|---|
| **M1**, a 3 to 4 minute live recording | P0-2 | P0, P1-3 Clear voice, P1-4 Remove a passage, P1-12 |
| "talk video" | P1-1a | P1-1 |
| "team sync long" | P1-1b | P1-2 Continue, P1-5 playback, P1-6 Re-time words, P2-2 |
| "stereo 44k" | P1-1b | P1-4d (+), P1-4e (+) |
| "zoom call" (no audio) | P1-1b | P1-1c, W1 |
| "short call" | P1-7b (or P2-1 on Android 15) | P1-7, P2-1 |

## 2. P0 smoke test: must pass before any upload (33 min)

### P0-1 Cold start (1 min)

- [ ] `adb shell am force-stop com.recap.mobile`, then open Recap from the
  launcher.
  **Expect:** the home screen in a second or two, no crash: "Tap to record —
  meetings stay on this phone" under the orb, **Import a recording**, a
  search bar reading "Search meetings…", and "No meetings yet".
  Covers: NATLIB 8 (full).

### P0-2 Record (6 min)

**Video** (microphone, then mediaProcessing in P0-3): record P0-2a to P0-3.

- [ ] **P0-2a** Tap the orb.
  **Expect:** "Before you record" with **I understand** (first recording
  only), then Android's microphone prompt (allow; notifications were
  allowed in S2.3, otherwise Android asks for them next). The recording
  screen's timer runs from 00:00 under "Recording audio. The full
  transcript is made when you stop — it comes out more accurate that way,
  and costs about 4% of the battery an hour."
  Covers: MARK 1 (partial: start).
- [ ] **P0-2b** Speak, or play `SRC` from a laptop speaker, for 3 to 4
  minutes. Include about 20 seconds of quiet or distant speech (walk two or
  three metres away, or turn the laptop down). Meanwhile tap the star
  ("Highlight this moment") once, tap **Camera** and photograph the printed
  page or whiteboard, and pull down the shade.
  **Expect:** a notification titled with the meeting's name (or "Recap"
  while it has none), with **Pause**, **Mark moment** and **Finish**.
  Android 16: a status-bar chip runs the recording's time and the card
  heads the shade, the lock screen and the always-on display (Live
  Update). Android 15: the ordinary notification.
  Covers: MARK 1 (partial), LU 1 (full, Android 16) or LU 10 (partial,
  Android 15).
- [ ] **P0-2c** Tap **Mark moment** in the shade, twice.
  **Expect:** for about 3 s the line reads "Marked at 0:01:23" (the
  recording's time). The second tap never removes the star.
  Covers: MARK 1 (full, with P0-2a and P0-2b).
- [ ] **P0-2d** Tap Pause on the recording screen and look at the shade,
  then Resume. Press Home, pull down Quick Settings and tap the **Record
  meeting** tile. Then lock the phone for 30 s and unlock.
  **Expect:** while paused there is no Mark moment (Android 16: the chip
  reads "Paused"); after Resume the time runs again. The tile opens the
  same recording screen, its timer still running (3.22.0-beta1: no second
  screen). After the lock the recording is still running and its time
  kept counting.
  Covers: MARK 3 (partial), LU 2 (partial: Paused), RF 13 (partial: the
  tile; Back is checked in P0-2e).
- [ ] **P0-2e** Tap **Finish**.
  **Expect:** Android 16: the chip reads "Saving" briefly, then the
  notification goes. The meeting page opens (if "Does this follow on from
  an earlier meeting?" shows, tap **Not now**). The photo is under Photos.
  Press Back: the home screen, with no recording screen left behind.
  Covers: MARK 3 (full, with P0-2d), LU 2 (full, with P0-2d), RF 13 (full,
  with P0-2d).

### P0-3 Transcript after stop (3 min, mostly waiting)

- [ ] **P0-3a** Watch the shade and the home screen, then the meeting's
  Transcript tab.
  **Expect:** a notification "Checking …" (the meeting's name) with
  **Cancel**; on home a card "Importing …" with "Transcribing… N%" or
  "Transcribing · …". On Android 16 this is an ordinary notification, not
  a Live Update (deliberate, PD: the automatic transcript is not a Live
  Update). When it ends: "Import complete — tap to open", and the
  Transcript tab has timed lines, the quiet passage included. The line at
  the star and the line at the Mark moment time are starred. Logcat:
  `loaded libmeetily_native_v82.so` (tag `meetily_native`;
  `libmeetily_native.so` on a phone without dot-product cores),
  `whisper_full: … encoder pass(es)` and `VAD model loaded` (tag
  `meetily_whisper`), and no `speech gate off`.
  Watch for: while the pass runs, the Transcript tab may still say "No
  transcript yet. The audio is saved: choose ⋮ → Check transcript accuracy
  to transcribe it on this phone." Do not start a check by hand. If lines
  appear while it runs, tap one's time label → **Edit text…**: "This
  meeting is still being recorded or transcribed. Lines can be edited once
  that finishes." (3.22.0-beta1). Note whether the lines appear without
  leaving the page. The notification's "Checking" and the card's
  "Importing" wording for the same pass is known and minor.
  Covers: MPROC 1 (full), NATLIB 1 (full), NATLIB 2 (full), NATLIB 5
  (full), CONT 14 (partial: marks land on lines), VAD 2 (partial: the
  after-meeting transcript; the live part is P1-10).
### P0-4 Summarise and ask (5 min)

- [ ] **P0-4a** On M1 tap the sparkle (**Summarize**) in the toolbar, or
  **Generate summary**.
  **Expect:** "Summary style" with **Brief** / **Standard** / **Detailed**
  and the styles; tap **General minutes**. Notification "Summarizing “…”",
  then "Summary ready — tap to open". The card shows the summary and a
  checklist under "Action items", a "General minutes" pill and a **+**
  pill, and "Showing: General minutes, written …". The text is sane: no
  runaway repetition, no visible think block, no `<|im_end|>`-style
  markers. Logcat (tag `meetily_llama`): `local model loaded (n_ctx=8192,
  …` for Qwen 2.5 1.5B on a 6 GB or larger phone (4096 on a smaller one)
  and `prompt: … jinja=1`.
  Covers: NATLIB 4 (partial: a 4-minute meeting), CTX 1 (partial: load
  line only), ENG 1 (partial: this one model).
- [ ] **P0-4b** Tap **+**.
  **Expect:** the same "Summary style" list with "General minutes ·
  replaces its summary". Tap **Standup (by person)**: two pills, and
  "Showing: Standup (by person), written …". Tap the General minutes pill:
  it shows at once, with no progress, and the action items stay as they
  are.
  Covers: SUMS 1 (full), SUMS 2 (full), SUMS 4 (partial: the marker only).
  Deliberate: each new summary refreshes the one action-item list (PD:
  several summaries).
- [ ] **P0-4c** On M1's Summary tab, under Ask this meeting ("Ask a
  question about this meeting…"), ask what was decided.
  **Expect:** an answer from the on-device model drawn from the meeting,
  saved with the meeting; logcat `prompt: …` (tag `meetily_llama`), and a
  `reusing N cached prompt tokens` line on a follow-up question.
  Covers: ENG 1 (partial: the Ask path), JNI 1 (partial).

### P0-5 Play back (3 min)

**Video** (mediaPlayback): record P0-5b and P0-5c.

- [ ] **P0-5a** On M1 tap **Play** in the player bar. Tap a word in the
  middle of a line. Tap **Back 15 seconds** and **Forward 15 seconds** near
  the start and near the end.
  **Expect:** playback jumps to the tapped word; with "Follow transcript
  during playback" on, the highlighted word moves with the audio; the skips
  clamp at both ends and the highlight jumps with them. The transcript's
  last line ends just above the player bar, with no extra gap.
  Covers: PLAY 14 (full), E2E 4 (partial: no keyboard).
- [ ] **P0-5b** Lock the phone while it plays. Pause from the lock screen,
  then Play.
  **Expect:** the audio carries on when locked. The lock-screen controls
  show "Meeting audio" and "Recap", never the meeting's name or transcript
  text (deliberate, PD: background playback). Play works at the first
  press.
  Covers: PLAY 1 (partial: Android 15/16), PLAY 5 (full).
- [ ] **P0-5c** Unlock and press Back to the home screen while it plays.
  **Expect:** the audio carries on; the shade's media controls (Android
  13+) have back 15 s and forward 15 s in the previous and next places and
  a **Stop**, and no track arrows. **Stop** ends the audio and the
  notification.
  Covers: PLAY 12 (partial), PLAY 17 (full).

### P0-6 Edit a line (1 min)

- [ ] On M1 tap a line's time label.
  **Expect:** "Who said this?" lists "▶ Play from here", "Someone else…",
  "Edit text…", "Split into two…", "Fix a word everywhere…", (where
  offered) "Re-time words", "Share clip…" and "Remove passage…".
  Tap **Edit text…** → "Edit transcript line" → change one word → **OK**.
  **Expect:** the line shows the change; tapping the words you left alone
  still seeks to them, and the changed word seeks close to its place. Its
  timing stays estimated in this plan (P1-6 re-times lines of "team sync
  long", not M1); W3 checks that the line still offers Re-time words.
  Covers: REPL 5 (full).

### P0-7 Search (2 min)

- [ ] **P0-7a** On home, type a distinctive word said in the middle of M1
  into "Search meetings…".
  **Expect:** M1's row shows a snippet "0:02:10 · …" with the word
  highlighted. Tap the row: the meeting opens on the Transcript tab at
  that line, outlined for about 2.5 s, with the find bar open on your word
  and its count on that line's match (for example "3 of 12").
  Covers: FIND 1 (partial: no 2-hour meeting with chapters).
- [ ] **P0-7b** Tap **Next match** and **Previous match** past both ends,
  then press Back. Then search home for a word that is only in the Standup
  summary (the one not shown), and for a word printed on the photo from
  P0-2b.
  **Expect:** the bar steps through every match, wraps at both ends and the
  count follows; Back closes it and clears the marks. The summary search's
  snippet starts "Other summary · …"; the photo search's starts "Photo text
  · …" (ML Kit read the photo on the phone).
  Covers: FIND 4 (full), SUMS 7 (partial: search), HSEARCH 1 (partial:
  one photo).

### P0-8 Export and share (3 min)

- [ ] **P0-8a** On M1, ⋮ → **Export**.
  **Expect:** "Markdown (.md)", "PDF (.pdf)", "Word document (.docx)",
  "Subtitles (.srt)", "Subtitles (.vtt)", "Action items → calendar/tasks
  file (.ics)" and "Action items → share as text". Export Markdown, PDF,
  Word and .srt into Download/RecapTest through Android's picker:
  "Exported" each time. `adb pull /sdcard/Download/RecapTest/ ~/recap-test/out/`
  and open them: title, the summary shown (only that one), action items,
  notes and the timed transcript with speakers. The .docx opens in Word or
  LibreOffice with no "unreadable content" prompt and no author in its
  properties. The .srt has timed cues of at most two lines.
  Covers: EXP 1 (partial: no Word or Google Docs import check), EXP 2
  (partial: text only, no VLC), SUMS 7 (partial: exports), A17 9 (partial:
  no .ics or library export).
- [ ] **P0-8b (+)** Rename M1 to "Встреча команды" (⋮ → Rename) and
  export it as Markdown. Do not record another meeting for this: P0-10
  expects M1 to be the only one. The new name can stay; the plan still
  calls it M1.
  **Expect:** the picker suggests a name with the Cyrillic letters kept,
  not "meeting".
  Covers: RF 12 (partial: the export half).
- [ ] **P0-8c** Tap the toolbar share icon (**Share meeting**) and share to
  a notes or mail app.
  **Expect:** text with the title, the summary shown, the notes and the
  transcript.
  Covers: A17 9 (partial), SUMS 7 (partial: share).

### P0-9 Encrypted backup (2 min)

- [ ] Settings → Data → **Export backup** → "Protect this backup?" →
  **Encrypt**. Android's save picker opens first, suggesting
  `recap-backup.recapbak`: save it in Download. Then type a 5-character
  passphrase twice and tap **OK**; then a 6-character one and a different
  repeat; then the same passphrase twice → **OK**.
  **Expect:** "Use at least 6 characters." on the field, then "Passphrases
  don't match.", the dialog staying open each time; then "Backup saved".
  Covers: RF 4 (partial: no Cancel or rotation; see P0-9 (+)), setup for
  P0-10.
- [ ] **P0-9 (+)** Start another encrypted export, rotate the phone while
  the passphrase dialog is up, then press Back.
  **Expect:** after the rotation the passphrase is asked for again; Back
  gives "Backup cancelled." and no empty `.recapbak` is left in Download.
  Covers: RF 4 (full, with the step above).

### P0-10 Delete, undo, Recently deleted and restore (5 min)

- [ ] **P0-10a** On home, long-press M1.
  **Expect:** the header becomes the selection bar ("1 selected", Select
  all, Delete, More actions); a long press no longer opens the options
  sheet (deliberate, PD: long press starts multi-select). Tap More actions
  → **Meeting options** → "Delete meeting?": a dialog "Delete meeting?"
  whose message is the meeting's name in quotes, then "moves to Recently
  deleted with its transcript, summary, notes, photos, attachments and
  audio. You can restore it from there for 30 days; after that it's erased
  for good." → **Delete**. A bar reads "Moved to Recently deleted" with
  **Undo**. Tap **Undo**: "Restored 1 meeting." and M1 is back.
  Covers: TRASH 1 (full).
- [ ] **P0-10b** Long-press M1 again and tap the selection bar's Delete:
  "Delete 1 meeting?" with "It moves to Recently deleted with its
  transcript, summary, notes, photos, attachments and audio. …"
  (3.22.0-beta1: the singular) → **Delete**; let the bar time out. Then
  Settings → Data → **Restore** → the `.recapbak`.
  **Expect:** "Reading backup…" right after the file is picked, then
  "Encrypted backup" with "This backup is passphrase-protected." Enter a
  wrong passphrase: "Restore failed: Wrong passphrase for this backup."
  Restore again with the right one: a dialog "Restored 0 meeting(s)" and
  "1 meeting in the backup is in Recently deleted on this phone and was
  left there. Restore it from Recently deleted to get it back." with
  **Open Recently deleted**. No "Meetings already on this phone" question
  appears: M1, the only meeting so far, is in Recently deleted, not in the
  library.
  Covers: STOR 6 (partial: bulk delete of one), TRASH 10 (full).
- [ ] **P0-10c** Tap **Open Recently deleted**.
  **Expect:** M1 with "Deleted … · erased in 30 days · …", **Restore** and
  **Delete permanently**. Tap **Restore**: the message is "Restored" and
  the meeting's name in quotes. Open M1: the transcript, both summary
  pills, the stars, the photo and the audio are all there and it plays.
  Covers: TRASH 3 (full).
- [ ] **P0-10d** On M1 tap **Edit notes**, add a line, save. Restore the
  same `.recapbak` again.
  **Expect:** after the passphrase, "Meetings already on this phone" with
  "1 meeting in this backup is already on this phone. Keep this phone's
  copy, or replace it with the backup's? …", **Keep this phone's**,
  **Replace** and **Cancel**. Tap **Keep this phone's**: "Restored 0
  meeting(s)" and "1 meeting already on this phone was kept as it is
  here."; M1 still has the new notes line, its audio and its photo
  (3.22.0-beta1: this used to be a silent replace).
  Covers: RF 1 (partial: Keep; Replace is the (+)).
- [ ] **P0-10e (+)** Restore once more and tap **Replace**.
  **Expect:** M1 comes back as it was in the backup, without the notes
  line; its audio and photo play and show.
  Covers: RF 1 (full, with P0-10d).

### P0-11 App lock (3 min)

- [ ] **P0-11a** Settings → Security → "Require unlock to open" on →
  **Save settings**.
  **Expect:** "Settings saved" and Settings closes. (With no device screen
  lock: "Set up a device screen lock (PIN, pattern, or biometric) first.")
- [ ] **P0-11b** Press Home, wait 35 s, open Recap. On the lock screen
  press Back once (it cancels Android's prompt), then Back again. Open
  Recap again and unlock. Then leave and come back within 30 s.
  **Expect:** "Recap is locked", "Unlock to open your meetings",
  **Unlock** and Android's fingerprint, face or PIN prompt, covering the
  app from its first frame with no slide-in animation and no meeting text
  visible before it (3.22.0-beta1). The first Back closes only the prompt
  and leaves the lock screen with its Unlock button; the second leaves
  for the launcher. After unlocking, the home list. Within 30 s, no lock.
  Covers: RF 10 (partial: from the launcher only; Recents and a
  notification are P1-5c), T36 2 (partial: the lock screen's Back, Android
  16 only).
- [ ] **P0-11c** Away for more than 30 s, share `short_call.mp3` to Recap
  from the Files app. On Recap's lock screen press Back twice.
  **Expect:** you are back in the Files app, not on Recap's home screen,
  and reopening Files does not show Recap's lock (3.22.0-beta1). (If the
  import went ahead behind the lock, a "short call" meeting appears: keep
  it, or delete it before W1.) Then
  Android Settings → Apps → Recap → Storage → **Manage space** (names vary
  by phone): Recap's lock first; unlock: Recap's "Storage" screen
  ("Measuring storage…", then the totals), not Android's clear-storage
  dialog (deliberate, PD: Manage space).
  Covers: RF 9 (full), STOR 1 (full).
  Keep the lock on for P1-5c; turn it off at the end of P1.

## 3. P1 riskiest paths (35 min core)

Run these in order: the long import transcribes in the background while
you do Clear voice and Remove a passage, and Re-time words waits until no
transcription or summary is running.

### P1-1 Import video, several files and a transcript (5 min, Batch 5)

- [ ] **P1-1a** In the Files app, Download → RecapTest, share
  `talk_video.mp4` to Recap.
  **Expect:** "Import recording" with "Extracting the sound from the
  video…", then "Transcribing… N%" (you can leave). The meeting is named
  "talk video". Settings → Data → **Storage and clean-up** → "Largest
  recordings": its audio is a few MB, not the size of the video.
  Covers: IMP 1 (full), IMP 10 (partial).
- [ ] **P1-1b** While "talk video" is still transcribing, Home → **Import a
  recording**.
  **Expect:** Android's picker opens (3.22.0-beta1; it used to open the
  running import's progress screen). Long-press to select
  `team_sync_long.m4a`, `stereo_44k.wav`, `silent_video.mp4`,
  `zoom_call.transcript.vtt` and `notes.txt`, and press Back right after
  picking ("Adding 1 of 5: …" lasts only a moment for small files).
  **Expect:** Back keeps you there with "Still adding files to the queue.
  Stay on this screen until it is done, or tap Cancel to stop adding the
  rest." Then an "Import" summary: 2 recordings queued and transcribed one
  after another behind "talk video", "1 transcript was imported as a
  meeting without audio.", "Not added, because there is no sound to
  transcribe: silent_video.mp4." and "Not added, because it is not audio,
  video or a WebVTT or SRT transcript: notes.txt." Tap **OK**. Home shows
  "File 1 of 2 · …" once "talk video" is done. (No model sheet appears
  with only an English-only model; with a multilingual one, "Transcribe 2
  recordings with which model?".)
  Covers: IMP 5 (partial: no 21-file cap, .srt or PDF), IMP 6 (partial:
  Back, no Cancel), IMP 10 (partial), RF 24 (partial: behind an import,
  not a recording's transcript).
- [ ] **P1-1c** Open "zoom call". In its toolbar open **Find in meeting**
  and type "cafe".
  **Expect:** three lines by Ana Lopez and Ben Okafor at about 0:00:01,
  0:00:04 and 0:00:09. No Audio tab and no player. ⋮ has no "Check
  transcript accuracy…", "Remove audio (keep transcript)", "Share audio…"
  or "Re-time edited lines". A line's menu has no "▶ Play from here",
  "Share clip…" or "Re-time words" ("Remove passage…" is there; without
  audio it removes lines only). Find marks Ben's line and reads "1 of 1"
  ("cafe" finds "café"). Home search for "cafe" finding nothing is
  current behaviour (home search does not fold accents): note it as an
  enhancement at most.
  Covers: IMP 7 (full), RETIME 8 (partial: caption part), FIND 5 (full).
- [ ] **P1-1d (+)** Share `short_call.mp3`, `stereo_44k.wav` and
  `talk_video.mp4` together from Files (a new batch of 3). When file 1
  runs, tap **Cancel all** in its notification. Share the three again,
  open the import progress screen and tap **Cancel**.
  **Expect:** after Cancel all: "Import stopped" with "2 more files of
  this batch were not imported. Share or pick them again to import them."
  and nothing more starts. The progress screen's Cancel asks "Stop the
  other files too?" ("File 1 of 3 is being transcribed. The files after
  it have not started yet.") with **Stop all** and **Only this file**; Only
  this file lets file 2 start. Delete the meetings these made (or keep
  them out of W1's count).
  Covers: RF 22 (partial: not while files are being added).

### P1-2 Continue an interrupted transcription (4 min, Batch 5)

- [ ] **P1-2a** When "team sync long" is at about 30 %, open it and tap a
  line's time label → **Edit text…**, and long-press a line. Then tap
  **Cancel** on its notification. On home long-press it → More actions →
  **Meeting options**.
  **Expect:** while it runs, both edits are refused with "This meeting is
  still being recorded or transcribed. Lines can be edited once that
  finishes." (3.22.0-beta1). After Cancel the meeting keeps the words so
  far and shows "This transcript is unfinished. Transcription can carry on
  from 7:40." with **Continue transcription** (the same item is in ⋮). The
  options sheet has "Continue transcription" with "From 7:40". Leave the
  selection with Back.
  Covers: CONT 3 (partial), RF 36 (full).
- [ ] **P1-2b** Tap **Continue transcription**.
  **Expect:** if lines run past the point, a "Continue transcription"
  dialog first: "N lines from 7:40 on are transcribed again and replaced as
  the transcription reaches them, including any edits made to them." →
  **Continue transcription**. Then "Continuing the transcript from 7:40 in
  the background." (or "Queued — the transcript will continue from 7:40
  when the current job finishes."), and progress "Continuing from 7:40 ·
  …" ("… matching voices so far" first with speaker detection on). On
  Android 16 it is a Live Update.
  Covers: CONT 3 (full, with P1-2a), LU 7 (partial: Continue).
- [ ] **P1-2c** A few minutes on, `adb shell am force-stop com.recap.mobile`
  and reopen Recap. Rotate, press Home and come back, then tap **Resume**.
  **Expect:** "Resume interrupted work?" with "“team sync long” was still
  being transcribed when Recap closed. Its transcript can carry on from
  …." and **Resume** / **Discard**; it is shown once, not stacked, through
  the rotation and the return. Resume carries on from that point.
  Covers: CONT 2 (partial), START 5 (partial: resume part).
- [ ] **P1-2d** Once it finishes (carry on with P1-3 meanwhile), look at the
  transcript around both carry-on points.
  **Expect:** no repeated and no missing lines; tapping lines just before
  and after each point plays the matching audio.
  Covers: CONT 1 (partial: the seam), CONT 2 (full, with P1-2c).

### P1-3 Clear voice (3 min, Batch 5, on M1)

**Video** (mediaProcessing, Clear voice): record P1-3a.

- [ ] **P1-3a** In M1's player tap **Clear voice**.
  **Expect:** "Make a clear-voice copy?" with "… saves the result beside
  the original recording, which is not changed. Nothing leaves the phone."
  and "The copy takes about … MB." Tap **Make copy**: the player shows
  "Preparing clear voice… N%", and a "Preparing clear voice" notification
  has Stop (a Live Update on Android 16). Rotate, leave the meeting and
  come back: the progress is still shown and it finishes with "Clear voice
  is ready. Tap Clear voice in the player to switch."
  Covers: CV 3 (full), LU 8 (partial: Clear voice), CV 2 (partial: no
  leftover-file check, which needs a debug build).
- [ ] **P1-3b** Play, and tap **Clear voice** on and off while playing (in
  the middle of a word) and while paused. Turn on follow-along and play the
  copy.
  **Expect:** each switch keeps the position, with no audio repeated or
  skipped (3.22.0-beta1: the copy is on the recording's timeline), and the
  chip shows which version is playing. Follow-along on the copy highlights
  each word as it is heard.
  Covers: CV 4 (full), RF 41 (partial: no accuracy check of the copy).
- [ ] **P1-3c (+)** Settings → Data → Storage and clean-up.
  **Expect:** "Meeting audio" includes the copy, and M1 under "Largest
  recordings" counts its copy with it.
  Covers: CV 7 (full).

### P1-4 Remove a passage (4 min, Batch 6, on M1)

**Video** (mediaProcessing, Remove a passage): record P1-4a to P1-4c.

- [ ] **P1-4a** Tap the time label of a line about a minute in → **Remove
  passage…**.
  **Expect:** "Remove a passage" with "From the line", "Starting at the
  word", "To the line" and "Ending after the word", and a status line such
  as "1:10 – 1:25 (15 s). 2 lines go." Choose a span of two or three lines
  and tap **Next**.
- [ ] **P1-4b** Read the confirmation.
  **Expect:** "Remove 1:10 – 1:25?" with "This removes 15 s of the
  meeting:", "• 2 lines of the transcript are deleted", "• the audio,
  which becomes silence. …", "The clear-voice copy is deleted, because it
  holds the passage. You can make it again from the player." (only when
  P1-3 made a copy), "Every summary of this meeting is marked out of date.
  …", "The title, notes, action items, answers under Ask this meeting and
  chapter titles are kept as they are, …" and, last, "This cannot be
  undone." Tap **Remove passage**.
  Covers: PASS 6 (partial), PASS 7 (partial), PASS 8 (full: the
  confirmation).
- [ ] **P1-4c** Let it run, then play across the span.
  **Expect:** "Removing the passage… N%" with **Hide** and **Stop** (a Live
  Update on Android 16), then "Removed 1:10 – 1:25." The span plays as
  silence with soft edges and the meeting keeps its length; tapping a word
  after the span seeks to that word. Both summary pills are out of date
  and the card shows "Transcript changed since this summary was written".
  The Clear voice chip offers to make the copy again: make it now (W3 needs
  a copy). Deliberate: the copy is deleted, not silenced (PD: Remove a
  passage).
  Covers: PASS 1 (full), PASS 6 (full), PASS 7 (full), SUMS 6 (full), LU 8
  (partial: Remove a passage).
- [ ] **P1-4d (+)** When "stereo 44k" has its transcript, remove a passage
  from it.
  **Expect:** the confirmation adds "The whole recording, not only the
  passage, is saved again as compressed audio (AAC), and the original file
  is deleted. A lossless original, such as WAV or FLAC, loses some
  quality." Afterwards it plays in stereo with the silence in place.
  Covers: PASS 9 (full).
- [ ] **P1-4e (+)** On "stereo 44k" (not M1, whose clear-voice copy W1 and
  W3 need), start another removal and run
  `adb shell am force-stop com.recap.mobile` mid-run; reopen.
  **Expect:** the meeting is unchanged or fully changed, never half. If a
  transcription was running, "Resume interrupted work?" appears too:
  **Resume**.
  Covers: PASS 15 (full).

### P1-5 Background playback controls (2 min, Batch 6)

- [ ] **P1-5a** Play "team sync long", press Back to home, then start music
  or a video in another app.
  **Expect:** Recap pauses and does not resume by itself.
  Covers: PLAY 4 (full).
- [ ] **P1-5b** Play a meeting, then start a recording (the orb, or the
  Quick Settings tile). Press Back (Recap goes to the background with the
  recording running), open a meeting with audio from a notification that
  opens one ("Import complete — tap to open" or "Summary ready") and tap
  **Play**. Return through the recording notification, then use the
  recording screen's toolbar **Discard** → "Discard this recording?" →
  **Discard**.
  **Expect:** playback stops the moment the recording starts. **Play**
  during the recording shows "Playback is off while a recording is
  running." and nothing plays. (Back on the recording screen sends the
  whole app to the background, so the home list cannot be reached during
  a recording.)
  Covers: PLAY 8 (full).
- [ ] **P1-5c** Play, then leave Recap entirely (Home, or Back to the
  launcher), wait more than 30 s and tap the media notification. Then
  pause, and swipe Recap away in Recents. Play again, leave, and swipe it
  away while it plays. Last, away for more than 30 s again, return from
  Recents.
  **Expect:** Recap's lock comes first (app lock is on), then the meeting,
  each time without a slide-in animation. Swiped away while paused, the
  notification goes; while playing, the audio carries on. With "Hide
  content from screenshots" off, the Recents card shows the last screen
  (PD: app lock and the Recents preview).
  Covers: PLAY 12 (full, with P0-5c), PLAY 13 (partial: swipe part), RF
  10 (partial: Recents and a notification; Hide content on is the (+)
  in P2-5).
- [ ] **P1-5d (+)** Call the phone while it plays; with a Bluetooth headset,
  press next and previous.
  **Expect:** it pauses for the call and resumes after it; next and
  previous skip 15 s.
  Covers: PLAY 3 (full), PLAY 6 (full).

### P1-6 Re-time words (6 min, Batch 7: never run on a device yet)

First let every import and summary finish (no progress card on home).

**Video** (mediaProcessing, Re-time words): record P1-6b to P1-6e.

- [ ] **P1-6b** In "team sync long", **Edit text…** one word of a line,
  then tap that line's time → **Re-time words**.
  **Expect:** "Is this meeting in English?" comes first only if the
  meeting's language was never recorded (with English-only Small Q it is
  recorded as English, so it should not appear; if it does, **It's
  English, re-time**). Then "Download the word re-timing model?" with
  "Re-timing words needs an English pronunciation model: a 49 MB download
  from GitHub (the sherpa-onnx project), 57 MB once unpacked. …" Tap
  **Download**: "Downloading the word re-timing model. Re-time the words
  again once it has finished." Re-timing does not start by itself when it
  lands (deliberate, PD: Re-time words).
  Covers: RETIME 1 (partial: download offer).
- [ ] **P1-6c** When the notification says "English MFA v3.0.0 (95,000
  words) model ready", tap the line → **Re-time words** again.
  **Expect:** a "Re-timing words" dialog shows "Loading the word re-timing
  model…", then "Re-timing words: 0 of 1 line", with **Hide** and
  **Stop**; then "1 line re-timed." Tapping the edited word starts
  playback at it, and the follow highlight is in step with the audio. The
  line's menu no longer offers "Re-time words".
  Covers: RETIME 1 (full, with P1-6b), RETIME 5 (partial: the just
  re-timed line).
- [ ] **P1-6d** ⋮ → **Find & replace…**: a common word said on 20 or more
  lines, with "Whole word only" on → **Replace**.
  **Expect:** before you tap, "Replace makes N changes. Word timings are
  kept, and you can undo it."; after, "Replaced N matches" with **Undo**
  (and maybe "Add “…” to custom vocabulary?": tap **Not now**).
  Covers: REPL 1 (partial: counts and message).
- [ ] **P1-6e** ⋮ → **Re-time edited lines** → "Re-time N lines?" →
  **Re-time** → **Hide**, then press Back to home and lock the phone for a
  moment.
  **Expect:** a "Re-timing words" notification with Stop and no meeting
  name (also on the lock screen); an ordinary notification even on Android
  16 (deliberate, PD: Re-time words). Then "Words re-timed". Reopen the
  meeting: tapping a replaced word seeks to it, and none of those lines
  offers "Re-time words".
  Covers: RETIME 2 (full), RETIME 12 (full).
- [ ] **P1-6f (+)** Replace another common word (20 or more lines, so the
  run lasts long enough), run **Re-time edited lines** again, and during
  the run try ⋮ → **Remove audio (keep transcript)** and deleting the
  meeting from home; then tap **Stop** in the notification.
  **Expect:** both are refused with a message and nothing is removed;
  "Stopped. N lines were re-timed before that; the rest keep their
  timings."
  Covers: RETIME 8 (partial: busy part), RETIME 9 (full).
- [ ] **P1-6g (+)** Start a summary of another meeting during a **Re-time
  edited lines** run; then start a recording and, from a notification
  that opens a meeting, try **Re-time words** on a line.
  **Expect:** the summary waits and starts when the run ends; during the
  recording: "Words can't be re-timed while a recording is running…".
  Discard the recording.
  Covers: RETIME 13 (partial: no pending accuracy check).
- [ ] **P1-6h (+)** Settings → Transcription → "Word re-timing (English)",
  then the Storage screen.
  **Expect:** "English MFA v3.0.0 (95,000 words), downloaded (… MB)." with
  **Delete model**; Storage lists "Word re-timing model"; **Delete unused
  models** does not offer it.
  Covers: RETIME 6 (full).

### P1-7 Live Updates (2 min, Batch 6, Android 16 QPR1 or later)

On Android 15 instead: start a recording, lock with hidden content, and
check the ordinary notification and that Mark moment works on the lock
screen (LU 10 full, MARK 2 full).

- [ ] **P1-7a** Set the lock screen to hide sensitive notification content
  (Android Settings → Notifications; the wording varies). Start a
  recording and lock the phone.
  **Expect:** the lock-screen card reads "Recording in progress" and the
  time, never the meeting's name, and has no Mark moment (deliberate, PD:
  Live Updates lock-screen trade-off). Finish it from the app.
  Covers: LU 3 (full).
- [ ] **P1-7b** Import `short_call.mp3` and lock the phone while it runs.
  **Expect:** a Live Update with a bar and a percentage chip; on the
  hidden lock screen "Transcribing a recording" with only minutes, no file
  name.
  Covers: LU 6 (partial: one file), LU 9 (full).
- [ ] **P1-7c (+)** Turn off Live Updates for Recap (Android Settings →
  Apps → Recap → Notifications; the wording varies) and start a recording.
  **Expect:** an ordinary notification, and the lock screen shows Mark
  moment again. Discard the recording; turn Live Updates back on.
  Covers: LU 4 (full).

### P1-8 Automatic clean-up (2 min, Batches 4 and 6)

This fresh install has nothing old enough, so it checks the rule's set-up
and the daily job; the runs that really remove audio or hold a batch for
review are in extra A.

- [ ] **P1-8a** Settings → Data → "Automatic clean-up" → **Change** →
  **Delete old meetings completely** → "Clean up meetings older than" →
  **30 days**.
  **Expect:** "Checking which meetings this reaches…", then "Turn on
  automatic clean-up?" with "Now: nothing is old enough yet.", then "After
  that, Recap checks once a day and deletes meetings as they pass 30 days.
  … Deleted meetings can be restored from Recently deleted for 30 days;
  after that they're erased for good." and "Never touched: starred
  meetings, …". Tap **Cancel**: the row still reads "Off".
  Covers: STOR 4 (partial: preview and Cancel), TRASH 5 (partial: preview
  text).
- [ ] **P1-8b (+)** **Change** → **Remove old audio, keep transcripts** →
  **30 days** → **Turn on**. Then run
  `adb shell cmd jobscheduler run -f com.recap.mobile 7302` and
  `adb shell dumpsys jobscheduler com.recap.mobile | grep 'JOB #.*/7302'`.
  Then **Change** → **Off**, and the same dumpsys.
  **Expect:** "Automatic clean-up is on." and the row "Remove the audio of
  meetings older than 30 days". The forced run posts nothing (nothing is
  old enough) and does not crash; the job is listed. After Off:
  "Automatic clean-up is off."; job 7302 stays listed while Recently
  deleted holds a meeting (by design), and goes once it is empty.
  Covers: STOR 5 (partial: forced run and Off).

### P1-9 Crash recovery of a recording (3 min; Batch 7 moved it off the main thread)

- [ ] **P1-9a** Start a recording, speak for 30 s, tap Mark moment, wait at
  least 5 s (marks reach disk through a 2.5 s autosave), then run
  `adb shell am crash com.recap.mobile` (where `am crash` is unknown, use
  `adb shell am force-stop com.recap.mobile`, which skips the crash
  prompt).
  **Expect:** the recording notification goes; logcat shows the crash
  (here that is expected).
- [ ] **P1-9b** Open Recap. Tap **Share report…** and read the text, then
  go back.
  **Expect:** "Recap crashed last time" with **Share report…** and **Not
  now**; the report names "App: Recap 3.22.0-beta1 (117)", the Android
  version, the phone and the stack trace. A toast "Recovered a recording
  that was interrupted". The meeting is in the list and its audio plays up
  to about the moment of the crash.
  Covers: START 5 (full, with P1-2c), EXIT 4 (partial: plain Java crash).
- [ ] **P1-9c** Watch the shade, then open its Transcript tab. Then
  `adb shell dumpsys activity exit-info com.recap.mobile | head -40`.
  **Expect:** its transcript is queued on its own ("Checking …", then
  lines; 3.22.0-beta1: no manual accuracy check needed), and the Mark
  moment star lands on the line at that moment. exit-info lists the crash.
  Covers: CONT 14 (full, with P0-3a), EXIT 4 (full, with P1-9b), RF 15
  (full).

### P1-10 (+) Live transcription (2 min, Batches 3 and 5, the review fixes)

- [ ] Settings → Transcription → "Transcribe live" on → **Save settings**.
  Start a recording and speak from the very first second, including a
  quiet phrase; record for a minute.
  **Expect:** words appear under "Live transcript" as you speak, quiet
  speech included, and the opening words are there (3.22.0-beta1: the
  microphone opens before the model has loaded); Finish keeps them. Turn
  "Transcribe live" off again and save.
  Covers: NATLIB 3 (full), VAD 2 (full, with P0-3a), RF 16 (partial: no
  failing model).

### P1-11 Short recordings and "Keep meeting audio" off (3 min, the review fixes)

- [ ] **P1-11a** Record 5 seconds of speech and Finish.
  **Expect:** it gets a transcript too: the work that reads the recording
  is queued once its file is closed (3.22.0-beta1; a recording this short
  used to get none).
  Covers: RF 14 (full).
- [ ] **P1-11b** Settings → Transcription → "Keep meeting audio" off → **Save
  settings**. Record 30 seconds and Finish; wait for its transcript.
  **Expect:** once the transcript is made, the meeting has no player and
  no Audio tab, and Storage lists no recording for it (deliberate, PD:
  "Keep meeting audio" off; before 3.22.0-beta1 the audio was kept). Turn
  "Keep meeting audio" back on and save.
  Covers: RF 20 (partial: no cancelled transcription, and the switch not
  turned back on before a transcript waiting for the charger).

### P1-12 AI safety and Report AI output (3 min)

Play's AI-generated content policy relies on Report AI output, and the
Health apps declaration on the fixed clinical notice.

- [ ] **P1-12a** On M1 tap **+** → **Clinical visit note (SOAP)**.
  **Expect:** the summary card starts with "AI-generated draft for review
  by a qualified clinician. … not a medical record, a diagnosis or medical
  advice. …", and the same notice is in **Share meeting**'s text and at the
  top of a Markdown export. Tap the General minutes pill: the card shows
  no clinical notice, but a notice above the action items does (the SOAP
  run wrote them).
  Covers: AISAFE 1 (partial: no legal summary, Copy or PDF), SUMS 8
  (partial: no Follow-ups or exports after the switch).
- [ ] **P1-12b** On that summary tap **Report AI output**.
  **Expect:** "Report AI output" with a reason and an optional comment and
  a box for including the text, off by default; then an email draft in
  your email app addressed to the support address (`support@example.com`
  until A5 is done). Do not send it.
  Covers: no Device checks item (Play's AI-generated content declaration,
  RELEASE_BLOCKERS.md A11).
- [ ] **P1-12c (+)** Disable every email app (Android Settings → Apps →
  … → Disable) and report again.
  **Expect:** "No email app found. The report was copied to the clipboard:
  paste it into a message to …". Re-enable the apps.
  Covers: AISAFE 1 (partial: the clipboard fallback).

### P1-13 Leaving while a recording finishes (3 min, the review fixes)

The work that follows a recording (here the automatic summary) is started
by the recording's own foreground service, which now stays until it has
started that work; and swiping Recap away mid-recording is a Finish, last
words included.

- [ ] **P1-13a** Settings → Transcription → "Transcribe live" on → **Save
  settings**; then "Summarise after the meeting" on, set to When the
  meeting ends (it saves at once). Stay on the charger or above 40 %
  battery. Record about a minute, tap **Finish** and press Home at once.
  **Expect:** a "Summarizing “…”" notification within seconds, not at the
  next app open; the recording notification goes only once that work has
  started (3.22.0-beta1).
  Covers: RF 48 (partial, with P1-13b).
- [ ] **P1-13b** Record again, press Home, and about a minute later, while
  still speaking, swipe Recap away in Recents.
  **Expect:** the recording notification stays while the last words are
  transcribed (about 15 s at most), and the summary notification comes
  only after it. Open the meeting: the sentence you were saying at the
  swipe is in its transcript. Turn "Summarise after the meeting" and
  "Transcribe live" off again (save), and let the summary finish before
  P2.
  Covers: RF 48 (partial: no accuracy check, and no swipe while a tapped
  Finish still reads "Finishing transcription…").

End of P1: turn app lock off (Settings → Security → "Require unlock to
open" off → **Save settings**) and put the lock-screen notification
setting back.

## 4. P2 platform specifics (8 min core)

### P2-1 Foreground-service types, Android 15 and 16 (3 min)

While each job below runs, run
`adb shell dumpsys activity services com.recap.mobile | grep -E 'ServiceRecord|isForeground|types='`
(the field names vary a little between builds; read the hex type mask).

- [ ] During a recording.
  **Expect:** RecordingService in the foreground, type microphone (`0x80`;
  a device-audio recording adds mediaProjection, `0x20`).
- [ ] During an import or a transcript (import `short_call.mp3` again if
  nothing is running).
  **Expect:** ImportService, type mediaProcessing (`0x2000`).
  Covers: MPROC 1 (full, with P0-3a).
- [ ] During background playback.
  **Expect:** PlaybackService, type mediaPlayback (`0x2`).
  Covers: PLAY 19 (full).
- [ ] **(+)** During Clear voice, Remove a passage and Re-time words.
  **Expect:** ClearVoiceService, PassageService and AlignService, each
  mediaProcessing (`0x2000`); during a model download or a summary,
  ModelDownloadService or SummaryService, dataSync (`0x1`).
  Covers: CV 11 (partial: the type part), PASS 21 (partial), RETIME 15
  (partial).

### P2-2 (+) The daily media-processing limit, Android 15+ (5 min)

- [ ] `adb shell device_config put activity_manager media_processing_fgs_timeout_duration 60000`
  (if it does not stick, run
  `adb shell device_config set_sync_disabled_for_tests until_reboot`
  first). Import `team_sync_long.m4a` again.
  **Expect:** after about a minute it stops with "Import incomplete" and
  "Android stopped this after its daily background time limit. The part
  that finished has been kept — start it again to continue."; the partial
  transcript is kept, with the "This transcript is unfinished" card and
  Continue; no crash and no `ForegroundServiceDidNotStopInTimeException`
  in logcat.
  Covers: MPROC 4 (full).
- [ ] Bring Recap to the foreground (that resets the limit), then on "team
  sync long" ⋮ → **Check transcript accuracy…** → run it.
  **Expect:** after about a minute "Transcript check incomplete" with
  "Android stopped this after its daily background time limit. Your
  transcript is unchanged — run the check again later." (3.22.0-beta1), and
  the transcript is unchanged.
  Covers: RF 25 (partial: no failed check).
- [ ] In the foreground again, edit 15 or more lines spread across the
  meeting so the run outlasts a minute, and run **Re-time edited lines**.
  **Expect:** "Re-timing stopped: Android's daily limit for background
  media work was reached. Lines already re-timed keep their new timings.
  Try the rest again later."
  Covers: RETIME 15 (partial: limit part).
- [ ] Reset: `adb shell device_config delete activity_manager media_processing_fgs_timeout_duration`.

### P2-3 Notification permission refused, Android 13+ (3 min)

- [ ] `adb shell pm revoke com.recap.mobile android.permission.POST_NOTIFICATIONS`
  and
  `adb shell pm clear-permission-flags com.recap.mobile android.permission.POST_NOTIFICATIONS user-set user-fixed`,
  then start a recording and answer **Don't allow**; record 20 s and
  Finish.
  **Expect:** Android asks; the recording starts anyway with no
  notification; Finish saves the meeting and its transcript is still
  made. (Where `clear-permission-flags` is missing, turn Recap's
  notifications off in Android Settings → Apps → Recap → Notifications
  instead: there is no prompt then, but the recording must still work the
  same.)
  Covers: no item in §11 (worth adding).
- [ ] Still without notifications: Settings → Transcription → **Manage
  models…** → pick a large model you do not have (Whisper Small, say).
  Tap that model again while it downloads, then tap **Cancel download**
  under its progress bar in Settings. Pick it again, and let it finish.
  **Expect:** the second tap shows its progress with no "Queued" toast;
  Cancel reads "Stopping the download…", then the row returns to its old
  state; the new pick resumes from the kept part, and once it finishes it
  is the selected model (3.22.0-beta1). Switch the selection back to Small
  Q, delete the new model, and run
  `adb shell pm grant com.recap.mobile android.permission.POST_NOTIFICATIONS`.
  Covers: RF 42 (full), RF 44 (full), RF 45 (full).

### P2-4 Exact alarms and reminders (2 min, then a few minutes' wait)

- [ ] Settings → Meetings → **Calendar check…**.
  **Expect** (fresh install, Android 14+): "Exact alarms" "Not allowed",
  with "Without this, Android may deliver a nudge a few minutes late. …"
  and **Allow exact alarms**.
- [ ] On M1 long-press an action item → **Remind me…** → a time 15 minutes
  ahead; lock the phone and carry on with P2-5.
  **Expect:** "Reminder set for …". The reminder arrives within a few
  minutes of its time with "From: …" (3.22.0-beta1 re-arms an inexact
  reminder in steps as its time approaches; it used to be able to land up
  to an hour late); tapping it opens the meeting.
  Covers: OPEN 3 (full), RF 18 (partial: no nudge).
- [ ] **Allow exact alarms** → allow Recap under "Alarms & reminders" →
  back; set another reminder 3 minutes ahead. Then revoke Alarms &
  reminders (Android closes Recap), reopen Recap, and wait.
  **Expect:** after allowing, the row reads "Granted". After the revoke the
  row reads "Not allowed" again, and the reminder still arrives within a
  few minutes of its time.
  Covers: RF 19 (partial: no re-grant).

### P2-5 Edge-to-edge, navigation modes and predictive back (3 min)

- [ ] With 3-button navigation, in light and in dark (the sun/moon "Switch
  theme" on home): the home list, a meeting with audio, Settings down to
  **Save settings**, Recently deleted and Storage.
  **Expect:** nothing important under the status or navigation bar; lists
  scroll under the navigation bar and their last item scrolls clear of it;
  bar icons readable in both themes.
  Covers: E2E 1 (partial: this phone).
- [ ] Open the keyboard in the recording notes ("Type your notes here…")
  and in a meeting's notes (**Edit notes**).
  **Expect:** the field stays above the keyboard; on a meeting with audio
  the player bar sits on the keyboard with no gap.
  Covers: E2E 1 (partial: keyboard), E2E 4 (full, with P0-5a).
- [ ] Settings → Transcription → **Manage models…**; drag the sheet to full
  height.
  **Expect:** its background runs under the navigation bar with readable
  icons, and its top clears the status bar.
  Covers: E2E 2 (partial: one sheet).
- [ ] With gesture navigation on Android 16, start a recording and swipe
  back from the recording screen.
  **Expect:** no system back-to-home preview animation (the screen handles
  Back itself); Recap goes to the background and the recording carries
  on. Finish it.
  Covers: T36 2 (partial: Android 16 only; tick it only there).
- [ ] **(+)** Turn on "Hide content from screenshots" and app lock, leave
  Recap for more than 30 s, and look at its Recents card, then return
  from it.
  **Expect:** the Recents card is blank; the lock screen appears with no
  meeting text before it. Turn both off again.
  Covers: RF 10 (full, with P0-11b and P1-5c).

### P2-6 (+) Large screen, approximated on the phone (Android 16 only, 3 min)

- [ ] `adb shell wm size` gives the width W in pixels; set
  `adb shell wm density D` with D at most W × 160 / 600 (280 for 1080 px).
  Rotate during a recording, right after tapping **Finish**, and during
  "Adding 2 of 5" of a five-file import. Then `adb shell wm density reset`.
  **Expect:** on Android 16 the portrait-only recording and import screens
  now rotate (Android 16 ignores `screenOrientation` at 600dp and wider for
  apps targeting 36; on Android 15 they stay portrait, as expected); one
  session, no second recording, nothing lost, the import keeps adding.
  Covers: T36 1 (partial: a real tablet or unfolded foldable is the real
  check), IMP 6 (partial: rotation).

### P2-7 (+) Device audio, Android 15 QPR1+ or 16 (3 min)

**Video** (mediaProjection): record this step.

- [ ] Long-press the orb → "Record from" → **Device audio — webinars &
  videos**.
  **Expect:** "Keep the screen unlocked" with "Don't show this again",
  **Continue** and **Use microphone**, before Android's screen-capture
  consent. Continue, consent, and play a video.
  Covers: DEVAUD 1 (full).
- [ ] Lock the phone for 10 s and unlock.
  **Expect:** the notification reads "Device audio stopped — tap Finish to
  save" with no Pause, no Mark moment and a frozen time; the recording
  screen reads "Device audio stopped — the screen locked or sharing ended.
  Tap Finish to keep what was recorded."; Finish saves the audio up to the
  lock.
  Covers: DEVAUD 2 (full), MARK 3 (full, with P0-2d).

### P2-8 Per-app language (1 min)

- [ ] Android 13+: Android Settings → Apps → Recap → Language.
  **Expect:** only "English (United States)" and the system default.
- [ ] Android 15: set the phone's system languages to German only (or
  another language Recap lacks), open Recap, then put your languages back.
  **Expect:** Recap opens normally (the launch crash of issuetracker
  b/399131926 does not happen).
  Covers: A17 8 (full).

## W. Wrap-up: clean-install restore (5 min)

- [ ] **W1** Delete "zoom call" (it goes to Recently deleted). Settings →
  Data → **Export backup** → **Plain zip** → in the picker rename it
  `test-library.zip` → save, then:

  ```bash
  adb pull /sdcard/Download/test-library.zip ~/recap-test/
  unzip -l ~/recap-test/test-library.zip | grep -c 'clear.m4a'      # expect 0
  unzip -p ~/recap-test/test-library.zip 'meetings/*.json' | grep -c 'zoom call'   # expect 0
  unzip -l ~/recap-test/test-library.zip | grep -c 'meetings/.*\.json$'           # the library's count
  ```

  **Expect:** no `*.clear.m4a`, nothing of "zoom call" (entries are named
  by meeting id, so the grep reads the JSON), and as many
  `meetings/*.json` entries as the home list shows meetings.
  Covers: CV 8 (partial: backup part), TRASH 9 (full).
- [ ] **W2** `adb uninstall com.recap.mobile && adb install meetily-android.apk`,
  onboarding → **Choose later**, then record for 20 s and open it.
  **Expect:** no "Import failed" or "Transcription failed" notification;
  the Transcript tab reads "No transcript yet. The audio is saved:
  download a transcription model in Settings → Transcription, then choose
  ⋮ → Check transcript accuracy to transcribe it on this phone.";
  Settings → Transcription still has "Transcribe with on-device Whisper"
  on.
  Covers: PRIV 1 (partial: no model download and check afterwards),
  SETUP 6 (partial: Choose later), START 6 (full).
- [ ] **W3** Settings → Data → **Restore** → `test-library.zip`.
  **Expect:** no "Meetings already on this phone" question (the 20-second
  meeting is not in the backup), then "Restored N meeting(s)". M1 has its
  summary pills and plays its recording, and its Clear voice chip offers
  to make the copy again. In "team sync long" re-timed words still seek
  correctly, and M1's line edited in P0-6 still offers "Re-time words".
  Covers: SUMS 10 (full), RETIME 16 (full), CV 8 (full, with W1).
- [ ] **W4** Put the phone back: `adb uninstall com.recap.mobile &&
  adb install meetily-android.apk`, restore `real-library.recapbak` from
  S1.1 if you made one, re-enter the settings you noted, download your
  models again, then `adb shell rm -r /sdcard/Download/RecapTest` and
  delete the test backups and any plain backup from the phone's Download
  folder and from `~/recap-test`.

## R. Final-build re-smoke (5 min, on the build you upload)

The placeholders are compiled into the app, so the build that goes to Play
is a later one than the build this plan tested. On that build (after A5,
A6 and the version bump, from the commit you tag):

- [ ] Install over this pass's build (`adb install -r`), open Recap: no
  crash; the library and settings are kept.
- [ ] Settings → Privacy policy: the effective date is filled in, and the
  Contact line names the real support address; Settings → Terms of use
  opens.
- [ ] Settings → **Report a bug** and a summary's **Report AI output**
  address the real support address, not `support@example.com`.
- [ ] Record 20 s, Finish: its transcript is made. Play it.
- [ ] `adb shell dumpsys package com.recap.mobile | grep -E 'versionName|versionCode'`
  shows the version you are uploading.

## 5. Optional extras

### A. A second, older phone: Android 8 to 12 (30 min)

The APK is arm64 only: a 32-bit-only phone refuses it with
`INSTALL_FAILED_NO_MATCHING_ABIS`.

- [ ] Clean install and first run. On a phone without dot-product cores
  (Cortex-A53/A73 class) the step suggests "Base (English)" with Gemma 3
  1B (4 GB or more) or Qwen 2.5 0.5B (3 GB); logcat `loaded
  libmeetily_native.so` and no SIGILL. Covers: SETUP 1 (partial), NATLIB 1
  (partial).
- [ ] P0-2 to P0-5 quickly (record, transcript, summary, playback). Then
  P1-10 (live transcription). Covers: NATLIB 2 (partial), NATLIB 3
  (partial), NATLIB 4 (partial).
- [ ] Android 8: background playback shows the older media notification
  with four actions, still "Meeting audio" / "Recap". Covers: PLAY 1
  (full, with P0-5b).
- [ ] Android 14 or lower: P2-1's dumpsys shows ImportService,
  ClearVoiceService, PassageService and AlignService as dataSync (`0x1`).
  Import `team_sync_long.m4a` and turn the screen off until it finishes;
  then import it again and Cancel half-way: the partial transcript is
  kept. Covers: MPROC 2 (full), CV 11 (full, with P2-1), PASS 21 (full,
  with P2-1), RETIME 15 (partial).
- [ ] A lock screen that hides contents: Mark moment works without
  unlocking, and the card shows only the generic line, Mark moment and
  "Marked at …". Covers: MARK 2 (full), LU 10 (full).
- [ ] Edge-to-edge as in P2-5 on API 26 or 27, or 29 or 30, with 3-button
  navigation. Covers: E2E 1 (partial).
- [ ] Android 8 or 9: share a WebM with Opus sound
  (`ffmpeg -ss 900 -t 60 -i "$SRC" -vn -c:a libopus clip.webm`): the whole
  file is kept and it transcribes. Covers: IMP 4 (partial).
- [ ] Android 10 or lower: `adb shell am crash com.recap.mobile`, reopen:
  "Recap crashed last time" (there is no ApplicationExitInfo before 11).
  Covers: EXIT 4 (partial).
- [ ] Settings → Transcription → "Transcribe with on-device Whisper" off
  shows a confirmation, and Cancel turns it back on. Covers: PRIV 5 (full).
- [ ] Open "team sync long" on this slower phone: title and date at once,
  "Opening meeting…" only if the read takes a moment. Covers: OPEN 1
  (partial).
- [ ] **Clock-forward checks (destructive: only on a phone holding test
  data).**
  - Delete one test meeting; star another; turn on clean-up **Remove old
    audio, keep transcripts**, 30 days. Turn automatic date off and set the
    date 40 days ahead. Open Recap: the deleted meeting has gone from
    Recently deleted and from Storage (TRASH 6, full). Run
    `adb shell cmd jobscheduler run -f com.recap.mobile 7302`: a quiet
    "Storage clean-up" notification, "Removed the audio of N meetings and
    freed …" with "Meetings older than 30 days. Their transcripts are
    kept."; the starred meeting keeps its audio (STOR 4 full, STOR 5 full).
  - Held-back review with notifications off: deny Recap's notifications,
    switch the rule to **Delete old meetings completely**, 30 days, while
    nothing is due. Restore a backup holding more than 10 meetings older
    than 30 days (from a real library), set the date 8 more days ahead (a
    restore counts as a change for a week), and force the run again:
    nothing is deleted, the Storage screen's clean-up card shows "N old
    meetings are waiting for your review…" with **Review**, which opens
    the review, and Settings → Data shows the same line under the rule.
    Switch the rule to Remove old audio: the line and the card go (RF 5,
    full).
  - Set the date back to automatic and delete a meeting: Recently deleted
    shows "erased in 30 days" and it is not erased before 30 days after
    the date the clock had reached (TRASH 7, full). Finish with
    `adb shell pm clear com.recap.mobile`.

### B. Android 17 beta (15 min)

- [ ] Clean install, then P0-2 to P0-5 quickly, P1-10 and P1-7a: Android 17
  may change foreground-service, audio and Live Update rules.
  **Expect:** the same results as on Android 15 or 16.
  Covers: NATLIB 2, 3 and 4 and LU 3 on Android 17 (no separate item).
- [ ] System sounds under Android 17's audio hardening: Settings →
  Transcription → "Transcribe with on-device Whisper" off (confirm) and
  "Mute recognizer chime while recording" on → **Save settings**. Run
  `adb shell cmd audio set-enable-hardening throw` (copied from
  PLAY_RELEASE.md; not verified). Record, leave the app, Finish from the
  notification.
  **Expect:** no crash; media and notification sounds come straight back.
  Covers: A17 5 (full).
- [ ] Before recording, mute one stream yourself (notifications, say) and
  set the ringer to vibrate. Record again on that engine, `adb shell am
  force-stop com.recap.mobile`, reopen Recap.
  **Expect:** the streams Recap muted come back; the one you muted stays
  muted and the ringer stays on vibrate. Turn the on-device engine back
  on. Covers: A17 6 (full).
- [ ] Run `adb logcat | grep "Please set the grant explicitly in the app"`
  while taking a photo with **Camera** on the recording screen and on a
  meeting's Photos.
  **Expect:** no line, and both photos are saved. Covers: A17 7 (full).
- [ ] If you run Ollama: Settings → Summaries → "Use an LLM for summaries"
  on → **Private endpoint** → `http://<computer IP>:11434/v1` (the field
  starts empty) and a model → **Save settings**, then summarise.
  **Expect:** no permission prompt (the app targets 36) and a summary.
  Covers: A17 3 (full), RF 31 (partial: the fresh-install half).
- [ ] `adb shell pidof com.recap.mobile`; while a summary model loads,
  `adb shell am memory-limiter manual <pid> 30` (copied from
  PLAY_RELEASE.md; not verified); reopen.
  **Expect:** "Recap stopped unexpectedly last time". Covers: EXIT 3 (full).
- Not testable with this build: the Nearby devices (local network) prompt,
  which needs a targetSdk 37 test build (A17 4). The RESTRICT_LOCAL_NETWORK
  checks (A17 1, A17 2) run on Android 16 and need two reboots each.

### C. The Play build: the AAB (15 min; once per release, before its first Play upload)

The upload is an AAB built with `-PuncompressedNativeLibs`, whose native
libraries are loaded straight from the APK and need 16 KB zip alignment;
every APK tested above compresses and extracts them.

- [ ] From the release workflow's `meetily-android-release` artifact (or
  the internal testing track):
  `bundletool build-apks --bundle=app-release.aab --output=recap.apks --connected-device --ks=<upload keystore> --ks-key-alias=recap-upload`,
  then `adb uninstall com.recap.mobile && bundletool install-apks --apks=recap.apks`.
- [ ] Repeat P0-1, P0-3 (with a model download), P0-4 and one Clear voice
  or Re-time words run, so whisper, llama and sherpa-onnx with onnxruntime
  all load, and P0-7b's photo search (ML Kit).
  **Expect:** the same results as above; logcat shows no
  `UnsatisfiedLinkError` or `did not load`, and on a dot-product phone
  `loaded libmeetily_native_v82.so`.
- [ ] If you have a second phone on Android 8 to 14, install there too and
  repeat P0-1 and P0-3.

### D. Accessibility (30 to 45 min; before promoting past internal testing)

PLAY_RELEASE.md §7 requires an Accessibility Scanner run and a short
TalkBack pass before a build goes past internal testing, so this extra
blocks the closed test and production, not the internal upload. Follow §11
"Accessibility" (A11Y 1 to 4) and the TalkBack sub-items of FIND 7, REPL 7,
CV 10, PASS 18, TRASH 12, EXP 9, LANG 10 and PLAY 16. Do not claim
screen-reader support in the listing until it is done.

### E. More review-fix checks (20 min)

Each checks a fix of 3.22.0-beta1 that the core does not reach; the RF
codes are the "Pre-release review fixes (3.22.0-beta1)" group in §11.

- [ ] Export with audio, then Remove audio on another meeting while it
  runs: the backup completes and restores without that file (RF 2, full).
  Back up to a USB stick and unplug it mid-export: no partial
  `recap-backup` file, or "…The file it started is incomplete — delete it…"
  (RF 3, partial).
- [ ] Cancel a transcription half-way, change the Settings model, then
  Storage → **Delete unused models**: the unfinished transcript's model is
  not offered (RF 6, full).
- [ ] With app lock on, set Android's screen lock to None while Recap's
  lock screen is up (cancel the prompt first), return to Recap: the lock
  screen closes and the library shows; set the screen lock again (RF 8,
  full).
- [ ] Share a meeting over 100,000 characters (a long imported meeting
  with its summaries) whose title is in Cyrillic: it goes as a .txt with
  the letters kept in its name (RF 12, full with P0-8b; RF 39, partial).
- [ ] With exact alarms denied, a calendar nudge for a meeting an hour
  away arrives within a few minutes of its start (RF 18, full with P2-4).
- [ ] Share a file from a slow cloud provider (Drive) and press Home at
  once: no crash, the "queued" toast, and it starts when Recap is next
  opened (RF 21, full).
- [ ] Queue a two-file batch with model X, delete X while file 1 runs: file
  2 runs with the Settings model. Queue another, then delete every
  transcription model: "N imports are waiting for a transcription model";
  download one, open Recap: they run (RF 23, full).
- [ ] Open a line's Edit text, let a Remove a passage started from another
  line finish behind it, then tap OK: "This line changed while the dialog
  was open, so nothing was changed. Open it again." if that line went;
  start a summary, open an action item's **Remove** while it runs, confirm
  after it ends: the right task or "This task changed while the dialog was
  open, so nothing was changed." (RF 37, full).
- [ ] Tick a notes checkbox while **Enhance notes** runs: "Notes are being
  enhanced — editing unlocks when it finishes." (RF 38, full).
- [ ] On the accuracy-check review of "team sync long", play a clip during
  a call (refused or paused) and while music plays in another app (it
  stops); rotate while the review is being read (RF 40, full).
- [ ] With a clear-voice copy, run Check transcript accuracy with
  "Transcribe the clear-voice copy instead" ticked: the reviewed lines'
  times match the original's, and the Audio tab's waveform is unchanged
  (RF 41, full with P1-3b).
- [ ] Two untitled recordings ("Meeting <date, time>"): no series chip on
  home and no brief match; again with the phone's language set to
  Portuguese or French, where the date has words ("Meeting 25 de nov. de
  2026 10:30"), then set it back (RF 33, full). "Summarise after the meeting"
  When the meeting ends, battery under 40 % off the charger: the summary
  waits for the charger (RF 32, full).
- [ ] Ask your library with the on-device model deleted: "Couldn't get a
  written answer (…). Showing the matching moments instead:"; rotate while
  an answer is being written: it arrives (RF 34, partial).

## 6. How to report a failure

- [ ] **Stop at the failing step.** Capture before retrying: a second try
  can change the state you need to show.
- [ ] **Screenshot:** `adb exec-out screencap -p > ~/recap-test/<step>.png`
  (a black image means "Hide content from screenshots" is on). For
  anything that moves: `adb shell screenrecord /sdcard/<step>.mp4` (Ctrl-C
  to stop, at most 3 minutes), then `adb pull /sdcard/<step>.mp4`.
- [ ] **In-app crash report:** after a crash, the next launch offers "Recap
  crashed last time" or "Recap stopped unexpectedly last time" → **Share
  report…** → send it to yourself. At any time, Settings → **Report a
  bug** shares a "Recap bug report" with the app version ("App: Recap
  3.22.0-beta1 (117)"), Android version, phone, ABI and the latest crash.
  Nothing leaves the phone unless you share it.
- [ ] **Logcat:** stop terminal 1 and keep its file. Take the lines around
  the failure:
  `grep -n -E 'FATAL EXCEPTION|ANR in com\.recap|DidNotStopInTime|com\.recap\.mobile|meetily_|SherpaEmbedder' ~/recap-test/logcat-*.txt`,
  then 20 lines either side of the hit. For a native crash, a freeze or a
  memory kill also save
  `adb shell dumpsys activity exit-info com.recap.mobile > exitinfo.txt`;
  `adb bugreport bugreport.zip` adds tombstones and ANR traces (large, and
  full of device data: keep it private).
- [ ] **Services and jobs**, while the problem is showing:
  `adb shell dumpsys activity services com.recap.mobile > services.txt` and
  `adb shell dumpsys jobscheduler com.recap.mobile > jobs.txt`.
- [ ] **Write it up** with `adb shell getprop ro.build.fingerprint` and
  `sha256sum meetily-android.apk`:

  ```text
  Step: P1-6c        Check: RETIME 1        Severity: blocker / major / minor
  Build: 3.22.0-beta1 (117), APK sha256 <first 12 characters>
  Phone: <model>, Android <version> (SDK <n>), <build fingerprint>
  Steps: 1. …  2. …  3. …
  Expected (from this plan): …
  Actual: …
  How often: 1 of 1 / 2 of 3
  Attached: screenshot, recording, crash report text, logcat lines, exit-info
  ```

- **Severity:** *blocker* = any P0 failure, a crash, lost or wrong meeting
  data, meeting content shown where the app says it is not (a hidden lock
  screen, media controls), or anything the privacy policy contradicts: do
  not upload. *Major* = a P1 path fails without data loss, or has a
  workaround: fix, or ship knowingly. *Minor* = wording or layout.
- **Privacy:** test with your own or licensed audio. Crash reports and bug
  reports can hold meeting names and fragments of text; read them before
  sending them to anyone else.

## Sign-off

- [ ] Every P0 step passed on the Android 15 or 16 phone.
- [ ] Every P1 and P2 failure has a decision: fix before upload, or ship.
- [ ] The logcat file and notes are kept with the APK's SHA-256.
- [ ] In PLAY_RELEASE.md §11 "Device checks", the items covered in
  **full** are ticked, and the partial ones carry a dated note instead.
- [ ] The demo videos are saved for the Play Console declarations.
- [ ] Section R passed on the build being uploaded.
- [ ] The phone is back to normal (W4).

## Legend: "Covers" codes

Codes name the groups under PLAY_RELEASE.md §11 → "Device checks"; the
number is the position of the checkbox in that group. **PD** = §11 →
"Product decisions".

| Code | Device checks group |
|---|---|
| T36 | Target API 36, large screens and back |
| E2E | Edge-to-edge |
| PRIV | Privacy and the on-device default |
| DEVAUD | Device audio (Android 15 QPR1+ or Android 16) |
| AISAFE | AI safety |
| SETUP | First-run setup and device fit (3.16.0-beta1) |
| MARK | Mark moment (3.16.0-beta1) |
| MPROC | Background transcription as mediaProcessing (Android 15+) |
| HSEARCH | Home search and lighter loads (3.16.0-beta1) |
| EXIT | Exit-record crash reports (Android 11+) |
| JNI | JNI-only native exports (3.16.0-beta1) |
| ENG | Engines, prompts and KleidiAI (3.17.0-beta1) |
| VAD | Speech detection (3.17.0-beta1) |
| CTX | Longer window for small models and the leaner Gemma cache (3.17.0-beta1) |
| OPEN | Opening a meeting off the main thread (3.18.0-beta1) |
| STOR | Storage, Remove audio, automatic clean-up and multi-select (3.18.0-beta1) |
| FIND | Search that lands on the moment, and Find in meeting (3.18.0-beta1) |
| REPL | Find & replace (3.18.0-beta1) |
| SHERPA | sherpa-onnx v2.0.3 and speaker models (3.19.0-beta1) |
| CONT | Continuing interrupted transcriptions (3.19.0-beta1) |
| EXP | Exports (3.19.0-beta1) |
| NATLIB | One native library per CPU level (3.19.0-beta1) |
| CV | Clear voice (3.19.0-beta1) |
| IMP | Importing video, several files and transcripts (3.19.0-beta1) |
| LU | Live Updates (3.20.0-beta1) |
| PLAY | Background playback (3.20.0-beta1) |
| TRASH | Recently deleted (3.20.0-beta1) |
| SUMS | Several summaries per meeting (3.20.0-beta1) |
| PASS | Remove a passage (3.20.0-beta1) |
| RETIME | Re-time words (3.21.0-beta1) |
| START | Start-up and the Baseline Profile (3.21.0-beta1) |
| A17 | Android 17 and 18 readiness (3.21.0-beta1) |
| RF | Pre-release review fixes (3.22.0-beta1) |
| A11Y | Accessibility |

## What this plan leaves out

On purpose, and why; the §11 items stay open for another pass.

1. Debug-build-only checks: native kill -11 and ANR injection (EXIT 1, 2),
   truncated model files (SHERPA 5), a corrupted meeting JSON (OPEN 7),
   leftover `.part` files (CV 2 tail, PASS 11), a planted passage swap
   marker (PASS 17), the old CAM++ file (SHERPA 4 tail), the share file
   in `cache/shares` and `clearvoice-probe.m4a` (RF 39, RF 41 tails), and a
   live model that fails to load (RF 16 tail). `run-as` does not work on
   the release APK.
2. Hardware matrices: 4/6/12 GB phones, Cortex-A53/A73, i8mm/SVE/SME2, 16
   KB pages, the x86_64 emulator, vendor AAC encoders (SETUP 1, ENG 3, VAD
   10, SHERPA 6, NATLIB, PASS), and the 6 GB on-device AI check (RF 29).
   Extra A covers only one older phone.
3. Model sweeps and measurements (hours of work and GBs of downloads, and
   they measure rather than pass or fail): all 15 LLMs' chat templates and
   prompt injection (ENG 1, 2), the opt-in models (OPTIN 1-6), KleidiAI and
   performance A/B (ENG 3, 4), 8K window quality and memory (CTX 1-3), VAD
   timing (VAD 6), 2-hour timing and battery runs (CV 1, PASS 2, RETIME
   14), cold-start and Baseline Profile timing (START 1-4). P0-4 keeps one
   model's load and prompt log lines as a smoke signal.
4. Languages and other engines: LANG 1-10, Parakeet/Nemotron/Ultra and
   speaker models (SHERPA 1-3), Whisper accuracy against 3.16 (ENG 5, 6),
   VAD comparisons (VAD 3-5, 7-9), Re-time words refusing non-English
   (RETIME 4), the start-over language and Parakeet spacing fixes (RF 27,
   RF 28). Each needs about 630 MB of models and non-English audio.
5. Long or wait-heavy checks: the charger queue and unplug/replug (MPROC 3,
   CONT 1 apart from the seam, CONT 12, CV 9 tail), playback paused for 10
   minutes (PLAY 13 first half), the 200-meeting library export, SD card,
   Word/Google Docs and VLC (EXP 1-8 in full), "Don't keep activities"
   (OPEN 5), low storage (PASS 12), 96 kHz and 5.1 imports (PASS 10), MP3
   continue (CONT 13), a long VBR MP3 voice window (RF 26), the Android 15
   dataSync timeouts (RF 30, RF 47), a stalled download (RF 43) and the
   Word re-timing space check with a queue (RF 46).
6. Narrower edge cases: CONT 4-11, REPL 2-4/6/8, SUMS 3/5/9/11, LU 5 and
   LU 6 batch segments, TRASH 2/4/8/11, RETIME 3/7/10/11, the
   RESTRICT_LOCAL_NETWORK reboot checks (A17 1, 2), anything needing a
   targetSdk 37 build (A17 4), a microphone unplugged mid-recording (RF
   17), quick starring during an import (RF 7) and ticks during Enhance
   notes (RF 35).
7. Outside the risk list: calendar nudges and pre-fill, the pre-meeting
   brief, the digest, Ask your library beyond extra E, the widget,
   shortcuts and home search by tags and attachments (HSEARCH beyond the
   photo search).

Also known and minor, from reading the code: the first transcript after a
recording is posted by ImportService as a check, so its notification reads
"Checking <name>" while the home card says "Importing <name>"; and
Follow-ups' .ics export with nothing open says "No open action items." but
leaves the empty file the picker created.
