# Recap — Google Play release runbook

Everything needed to take Recap from this repo to the Play Store, in order.
App ID: **com.recap.mobile** (permanent). Current release: 3.15.0-beta1
(versionCode 110), targetSdk 36 (Android 16).

Before the first submission, work through the **owner checklist in
section 11**: the Play Console declarations, placeholders, legal reviews
and device checks that the code cannot settle on its own.

## 1. One-time: create the upload key and add the secrets

Generate the key **on your own machine**, never on a build runner or in a
container. It is the app's identity: anyone holding it can build an APK that
installs over yours as an update. You need a JDK for `keytool` (Android
Studio bundles one).

```bash
bash android/scripts/make_upload_key.sh ~/recap-signing
```

That writes `recap-upload.keystore` and a one-line `.base64` of it, generates
a random password, and prints the four values to paste into GitHub: repo →
Settings → Secrets and variables → Actions → New repository secret.

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the whole one-line contents of `recap-upload.keystore.base64` |
| `ANDROID_KEYSTORE_PASSWORD` | printed by the script |
| `ANDROID_KEY_ALIAS` | `recap-upload` |
| `ANDROID_KEY_PASSWORD` | same as the store password (PKCS12 cannot differ) |

Back the keystore and password up somewhere private (a password manager)
before going any further. This is the **upload key** — with Play App Signing
(enroll when creating the app; it is the default) Google holds the actual app
signing key, so a lost upload key can be reset via Play support. A lost key
still means sideload updates can never install in place again.
**Never commit the keystore or passwords to the repo** — `android/.gitignore`
already excludes `*.keystore`, `*.jks` and `keystore.properties`.

To prove it took, run **Build Android APK** twice and check both APKs carry
the same certificate:

```bash
python3 android/scripts/apk_cert.py android/dist/meetily-android.apk
```

Recap's upload certificate SHA-256 — every signed build must print exactly
this, and it is safe to publish (it is the public half):

```
83:97:D1:AF:6A:14:CE:D8:6D:2B:19:2D:C4:14:65:92:21:D4:69:AE:30:B1:F8:DC:23:6C:B2:9D:0C:9B:F0:34
```

Anything else means the secrets are not being read and the runner fell back
to a throwaway debug key. For reference, two consecutive builds *without* the
secrets produced `54:3A:38:A0:…:D7:4E` and `93:58:24:64:…:69:04` — a
different identity each time, which is precisely why sideloaded updates used
to fail with a bare "App not installed".

### Why this matters before you even reach Play

Adding these secrets also fixes sideloading. Without them, `assembleRelease`
falls back to a debug key, and because CI runners are wiped between jobs
Android Gradle generates a **brand-new debug key on every build**. Each APK
then carries a different signing certificate, and Android refuses to install
an update whose certificate differs from the installed app — the phone shows
only a generic "App not installed" error. The sole workaround is to uninstall
first, which erases the app's data.

With the secrets in place, `Build Android APK` signs every APK with the same
key, so each new build installs straight over the previous one and keeps its
meetings, models, and settings. The workflow prints a warning in its log
whenever it had to fall back to a throwaway key.

**Migrating an already-installed sideload build:** the old install was signed
with a random key, so the first switch still needs an uninstall. Export a
backup first (Settings → Data → Export), uninstall, install the new APK, then
import the backup. After that, updates install in place.

## 2. Build the signed bundle

GitHub → Actions → **Release Android (signed AAB)** → Run workflow (on the
default branch or this branch). Download the `meetily-android-release`
artifact; it contains `app-release.aab` (upload this to Play) and signed
APKs (for sideload checks).

## 3. Create the app in Play Console

Play Console → Create app: name **Recap**, default language, App (not
game), **Paid** — this is the one-way door: a free app can never become
paid, so it must be Paid from creation (see section 9 for price setup).
Then complete "Set up your app":

- **Privacy policy URL**: publish `docs/privacy-policy.md` publicly first.
  It has been rewritten to match the in-app policy (PrivacyPolicyDriftTest
  keeps their sections in step); set its effective date and contact before
  publishing (section 11).
  Easiest: repo → Settings → Pages → deploy from branch → `main` `/docs`,
  then use `https://<user>.github.io/<repo>/privacy-policy` — or simply use
  the raw GitHub URL to the file. The URL must be reachable without login.
  Publish the Terms of Use (`android/app/src/main/assets/terms_of_use.txt`)
  the same way, e.g. as a `terms` page beside the policy. Play Console only
  asks for the privacy policy URL, so link the terms from the website in
  the listing's contact details or from the description.
- **App access**: all functionality is available without credentials —
  select "All functionality is available without special access".
- **Ads**: No, the app contains no ads.
- **Content rating questionnaire**: category Utility/Productivity; answer
  No to violence/sexuality/etc. Expect an "Everyone" rating.
- **Target audience**: 18+ (or 13+); do NOT target children.
- **News app**: No. **COVID-19 app**: No.
- **Health apps** (Policy → App content → Health apps): every app on Play
  must complete this, test tracks included. Recap's clinical templates
  (SOAP note, patient recap) draft clinical documentation from a recording
  for a qualified clinician to review, and MedGemma is a medical-vocabulary
  model; nothing in the app diagnoses, recommends treatment or calculates
  doses. The form has no "clinical documentation" option. The closest fit
  is **Medical → Healthcare Services and Management** (it covers managing
  health records). Do **not** select Clinical Decision Support or Medical
  Device Apps, and "My app doesn't provide any health features" is hard to
  defend with the clinical templates and MedGemma in the app. Where the
  form asks for detail, say that clinical and legal summaries carry a
  fixed, non-AI notice above the summary and in every share, copy and
  export: "AI-generated draft for review by a qualified clinician … not a
  medical record, a diagnosis or medical advice" (legal: "… a qualified
  lawyer … not legal advice"). Owner to confirm the category against
  https://support.google.com/googleplay/android-developer/answer/14738291
  (read 2026-11-22).
- **Data safety** (see section 4).
- **Government app**: No.

## 4. Data safety form (the important one)

Recap has no servers, accounts, ads or analytics of its own, and the
developer receives nothing from the app. "No data collected" is still not
the right answer: the bundled Google ML Kit text-recognition SDK, which
reads text in photos, sends Google diagnostics. The answers below match the
code on this branch; re-check them whenever an SDK or a network path
changes.

**Does your app collect or share any of the required user data types?
→ Yes**, for ML Kit's diagnostics. Every other path is either not
collection or a user-initiated transfer:

| Path | What leaves the phone | Planned answer |
|---|---|---|
| ML Kit text recognition (`com.google.mlkit:text-recognition`, bundled Latin model) | Device, app and performance diagnostics and per-installation identifiers, to Google over HTTPS. ML Kit initialises with the app, so this happens even if no photo is ever attached. Photos and their text are not sent. | Declare (below) |
| On-device transcription (the default for new installs) and the on-device AI engine | Nothing | Not collected |
| Android speech service engine (opt-in; switching to it asks for confirmation; installs upgraded from earlier versions keep it until they switch) | Meeting audio, captured by the system recognition service itself (Google's on most phones), which Android documents as likely to send it to remote servers. Recap hands it no audio and gets text back. | Not collected by Recap (note below) |
| User-configured AI endpoint (off by default; Local-only AI on by default) | Transcript, notes, title, attendee names, photo text, the user's name if entered and the template instructions, to the address the user typed, with their API key | User-initiated, not shared; collection: note below |
| Model downloads (only when the user picks a model) | An ordinary HTTPS request to Hugging Face or GitHub (IP address, file requested, user agent); no meeting data | Not collected |
| Crash reports | Saved on the phone; a report leaves it only if the user shares it through the share sheet | Not collected (user-initiated) |
| Report AI output | An email draft in the user's own email app; the generated text only if the user ticks the box; sent only if the user sends it | Not collected (user-initiated) |
| Share, export, backup | Wherever the user sends them | Not collected (user-initiated) |
| Calendar | Read on the phone; event title and attendee names leave only inside a meeting the user sends to an AI endpoint | Not collected |

**ML Kit, mapped to Play's data types** — owner to confirm against
https://developers.google.com/ml-kit/android-data-disclosure (read
2026-11-22; Google last updated it 2026-07-15). For every ML Kit feature
Google lists device information (manufacturer, model, OS version and
build, ML accelerators), application information (package name, app
version), per-installation identifiers "not intended to uniquely identify a
user or physical device" (for bundled features), performance metrics such
as latency, API configuration, input and output size, feature version,
event types and error codes, all "used for diagnostics and usage
analytics", encrypted in transit with HTTPS and not transferred to third
parties. Text recognition collects nothing feature-specific on top.

- **App info and performance → Diagnostics**: the device and app
  information, performance metrics, API configuration, input/output size,
  feature version, event types and error codes. Tick "Other app
  performance data" as well if any of these reads closer to it. "Crash
  logs" does not apply: ML Kit lists none, and Recap's own crash reports
  are user-shared.
- **Device or other IDs**: the per-installation identifiers.
- For both: **collected: Yes; processed ephemerally: No; required**
  (users cannot turn it off); **purpose: Analytics**.
- **Shared: owner to decide.** Play counts a transfer "directly to a third
  party via libraries and/or SDKs included in your app" as sharing, unless
  the recipient is a service provider processing the data on the
  developer's behalf and instructions. Google says ML Kit passes the data
  to no third parties, but it uses it for its own diagnostics and usage
  analytics, which reads less like processing on Recap's behalf. The
  cautious answer is **shared: Yes, for Analytics**; "shared: No" relies on
  treating Google as ML Kit's service provider.
- Optionally capture the phone's traffic at cold start and after one OCR
  run before filing, to see what actually goes out.

**The other paths:**

- *AI endpoint.* Play defines "collect" as transmitting data off the
  device, and exempts from "sharing" a transfer "based on a specific
  user-initiated action, where the user reasonably expects the data to be
  shared". The endpoint is off by default, its address is typed by the
  user, Local-only AI keeps it on the user's own network unless they turn
  that off, and the privacy policy says exactly what is sent, so "not
  collected, not shared" remains defensible and matches comparable
  local-first apps. For maximum caution, declare App activity → Other
  user-generated content (transcripts, notes, summaries, photo text) and
  Personal info → Name (attendee names, the user's name) as collected,
  optional, for App functionality, not shared. If you do, "encrypted in
  transit" below no longer holds for plain http to a LAN Ollama, which is
  one more reason to treat the endpoint as user-initiated.
- *Android speech service.* The opt-in, the confirmation and the
  on-device default make "not collected" defensible: the system service
  captures and sends the audio, not Recap. If review disagrees, declare
  Audio → Voice or sound recordings as optional.

**Security practices:**

- **Is all of the user data collected by your app encrypted in transit?
  → Yes.** ML Kit uses HTTPS (per Google). Model downloads are https-only,
  enforced in code on every redirect (ResumableDownload) and by
  `network_security_config.xml` for the Hugging Face and GitHub hosts. AI
  endpoints use https, except plain http to private-network addresses,
  which EndpointGuard allows (and refuses for any public address).
- **Do you provide a way for users to request that their data is
  deleted?** Everything Recap keeps is on the phone and is deleted in the
  app (meetings, voice profiles, models) or by uninstalling. ML Kit's
  diagnostics are held by Google and Recap cannot delete them, so for the
  declared data the answer is most likely **No**; owner to confirm.

## 5. Sensitive permission declarations

The console will ask about these (all have in-app justifications):

- **RECORD_AUDIO / FOREGROUND_SERVICE_MICROPHONE**: core feature — meeting
  recording, explicitly user-initiated, with ongoing notification. Provide a
  short screen-recording video of: tap orb → consent dialog → recording
  screen → notification. (The default transcribes after the meeting; turn
  on Settings → Transcribe live first if the video should show words
  appearing. Console asks for a video link for microphone
  foreground-service use; unlisted YouTube works.)
- **FOREGROUND_SERVICE_MEDIA_PROJECTION**: "record device audio" mode for
  webinars — user grants via the system screen-capture dialog each time;
  audio only. On Android 15+, Recap first shows a warning that capture
  stops when the screen locks, then the system screen-capture dialog; if
  Android stops the projection mid-session, the notification and recording
  screen say so and only Finish remains.
- **FOREGROUND_SERVICE_DATA_SYNC**: file import transcription, model
  downloads, summary generation — long-running user-initiated work.
- **SCHEDULE_EXACT_ALARM**: user-set action-item reminders and calendar
  nudges (falls back to inexact windows when not permitted). This is an
  allowed use case (user-facing alarms/reminders).
- **READ_CALENDAR**: optional pre-fill/nudges, read-only, declared in the
  privacy policy.

## 6. Store listing

- **App name (30)**: `Recap: On-device Meeting Notes` (30 characters; the
  earlier `Recap — On-device AI Meeting Notes` is 34, over the limit), or
  just `Recap`
- **Short description (80)**:
  `Record, transcribe & summarize meetings on your phone. Offline, no account.`
  (75 characters; the earlier "100% on your phone. No cloud, no account."
  line was 81, and "100%" overstated it once ML Kit's diagnostics count)
- **Full description**: see `PLAY_LISTING.txt` alongside this file.
- **App icon**: `play-icon-512.png` (512×512, delivered separately).
- **Feature graphic**: `play-feature-1024x500.png` (1024×500).
- **Screenshots**: at least 2 phone screenshots (take from your device:
  home with meetings list, recording screen, a meeting page with summary +
  insights, model manager). PNG/JPG, 16:9–9:16. Take new ones from this
  build: every screen is now edge-to-edge, with the status and navigation
  bars transparent over the app background (a translucent scrim on
  3-button navigation). Older screenshots with solid bars look nearly the
  same. The recording screen shows live words only with Transcribe live on.
- **Category**: Productivity. **Tags**: note-taking, transcription.
- **Contact email**: required — the support address. Use the same address
  as `support_email` in the app (section 11): in-app AI output reports and
  privacy questions go there.

## 7. Testing track first (required for personal accounts)

Personal developer accounts created after Nov 2023 must run a **closed test
with at least 12 testers continuously for 14 days** before production
access is granted. Plan: upload the AAB to a Closed testing track, add a
tester email list (friends/colleagues), have them opt in via the test link,
and keep the test live 14 days. Organization accounts skip this.

Recommended ladder regardless of account type:
1. **Internal testing** (instant, up to 100 testers) — sanity-check install,
   permissions, model downloads over Play delivery. Read the pre-launch
   report, including its tablet devices (the first targetSdk 36 upload is
   the first one that rotates the portrait-locked screens there) and its
   accessibility findings.
2. **Closed testing** — the 14-day/12-tester run if required.
3. **Production** — staged rollout at 20% → 100%.

Before promoting a build past internal testing, run Accessibility Scanner
and a short TalkBack pass (record, import, summary, transcript line
actions), and review the pre-launch report's accessibility findings.

Since the app is paid: add every tester's Google account email under
**Play Console → Settings → License testing** so they get the app free
(internal-track testers always install free; license testing covers the
closed track and production checks).

## 8. Per-release loop (after setup)

1. Bump `versionCode` (+ `versionName`) in `android/app/build.gradle.kts`.
2. If a native pin, the sherpa-onnx version or a model catalogue changed,
   regenerate the licence notices: run
   `python3 android/scripts/build_notices.py` on a machine with network
   access to raw.githubusercontent.com (it fetches licence texts from
   meta-llama/llama-models, aboutcode-org/scancode-toolkit,
   microsoft/onnxruntime, k2-fsa/sherpa-onnx, ggml-org/whisper.cpp and
   openai/whisper) and commit `app/src/main/assets/notices.txt`.
3. When adding a model to any catalogue (WhisperModels, NemoModels,
   DiarizationModels, LocalLlmModels), add its licence to
   `licences/ModelLicences.kt` and to the MODELS text in
   `scripts/build_notices.py`; `ModelLicencesTest` fails otherwise. Name a
   licence only after reading the publisher's page; until then use "see
   model page". When a publisher materially changes its terms, raise that
   `TermsFamily`'s version in `ModelLicences.kt` so everyone is asked to
   accept again.
4. Run the **Release Android (signed AAB)** workflow; download the AAB.
5. Play Console → the track → Create new release → upload AAB → release
   notes → roll out (after the accessibility pass in section 7 for
   anything beyond internal testing).

## 9. Pricing (paid up-front)

Decision (July 2026, backed by market research): **list price $7.99,
launch sale $4.99**.

Why: the one-time on-device comparables sit at $4.99 (Viska, thin
feature set) and $6.99 (Whisper Notes, transcription only); Recap adds
the meeting-intelligence layer that cloud tools (Otter $100-204/yr,
Fireflies $120-216/yr) charge subscriptions for, so it prices at the
top of the one-time class, with an intro sale for impulse-buy momentum
and early reviews. Marginal cost per user is zero (no servers), so
price is pure positioning.

Setup order (the free→paid lock makes ordering matter):
1. **Payments profile first**: Play Console → Settings → Payments
   profile — business/individual details, tax forms, payout bank
   account. Verification can take a couple of days; nothing paid can
   ship until it clears.
2. **Enroll in the 15% service-fee tier**: Console → Monetization setup
   → join the 15% tier (15% fee on the first $1M/yr instead of 30%).
3. **Set the price**: Monetization → App pricing → Paid → **$7.99**
   USD; let Play auto-convert other currencies initially (round pricing
   templates can come later).
4. **Launch sale**: once live (or at production rollout), create a
   promotional price / sale at **$4.99** — Play shows the strikethrough
   ($7.99 → $4.99). Let it lapse to list price once a review base
   exists (~50+ reviews is a reasonable trigger).
5. Paid-price changes are allowed anytime in both directions; only
   free→paid is impossible. Refunds are not automatic: within 48 hours of
   purchase a buyer can request one through Google Play, and Google
   decides each request ("you may be able to get a refund depending on
   the details of the purchase"; a refunded app is uninstalled, and an
   app can be refunded only once). After 48 hours Play sends buyers to the
   developer, and you can refund any order yourself from Play Console
   (Order management). Source: Google Play Help, "Apps, games, & in-app
   purchases (including subscriptions) refund policies"
   (support.google.com/googleplay/answer/15574908, read 2026-11-22).

The listing copy sells the model explicitly ("PAY ONCE, OWN IT" section
in PLAY_LISTING.txt) — the $100+/yr subscription contrast is the
strongest conversion line this app has.

## 10. Crashes and bug reports

- **Android Vitals** (Console → Quality → Android Vitals → Crashes & ANRs)
  is the aggregate crash feed — automatic for Play installs, no SDK. The
  release build packs native symbol tables into the AAB
  (`debugSymbolLevel = SYMBOL_TABLE`), so native crashes in the
  whisper/llama/sherpa libraries arrive symbolicated.
- **No third-party crash SDK on purpose**: Crashlytics/Sentry would add
  crash logs and more identifiers to what the Data safety form declares,
  which today is only ML Kit's diagnostics (section 4). Instead the app
  captures crashes to a local file and, on next launch, offers the user a
  share-sheet report ("Recap crashed last time — share the report?"); a
  "Report a bug" entry in Settings does the same on demand. Reports move
  only when the user sends them, so the data-safety form is unaffected.
- **Bundled SDKs that send data to Google**: only ML Kit text recognition
  (diagnostics, as declared in section 4). AndroidX emoji2, which
  appcompat pulls in, asks Google Play services on the phone for an emoji
  font at startup; that is a request to another app on the device and
  carries no user data. The on-device engines (whisper.cpp, llama.cpp
  built without curl, sherpa-onnx/onnxruntime) make no network requests of
  their own.
- Check **reviews** weekly early on; reply to bug reports there and point
  users at Settings → Report a bug.

## 11. Owner checklist before submission

Everything here needs the owner: a Play Console answer, a placeholder only
you can fill, a legal or clinical review, or a check on a real device.
Work through it before the first targetSdk 36 upload to any track.

### Version and build

- [ ] Bump `versionCode` to 110 and `versionName` to `3.15.0-beta1` in
  `android/app/build.gradle.kts`. On lb/integration they still read 109 and
  `3.14.0-beta5`.
- [ ] Run CI on the branch you merge: `testReleaseUnitTest`, then
  `assembleRelease` with `lintVital`. CI needs SDK Platform 36 and
  Build-Tools 35.0.0; AGP downloads them when the SDK licences are
  accepted, as they are on GitHub's ubuntu runners.
- [ ] Check 16 KB alignment of every `.so` in the release AAB/APK,
  including the sherpa-onnx/onnxruntime prebuilts and ML Kit's bundled OCR
  library: Android Studio's APK Analyzer, or `zipalign -c -P 16 -v 4`. On
  Android 16 a 4 KB-aligned app runs in compatibility mode and shows the
  user a dialog. AGP 8.10 adds an Aligned16KB lint warning for
  dependencies.
- [ ] Fill the model pins (roadmap item B55, not part of this batch):
  every `sha256` and `revision` in WhisperModels, NemoModels,
  DiarizationModels and LocalLlmModels is still null. Run
  `android/scripts/model_hashes.py` or the model-hashes workflow on a
  machine with network access, and check that every catalogue URL still
  resolves.

### Play Console declarations

- [ ] **Target API 36**: the bundle must target 36 to be accepted (see
  Notes and gotchas). After the first upload, check the pre-launch report
  on tablet devices.
- [ ] **Data safety**: file the answers in section 4. "No data collected"
  is no longer accurate. Re-read Google's ML Kit Android data disclosure
  (https://developers.google.com/ml-kit/android-data-disclosure) and
  declare what it lists for the bundled text-recognition SDK: App info and
  performance → Diagnostics, and Device or other IDs, collected through a
  third-party SDK and encrypted in transit. Decide the "shared" answer
  (section 4). Optionally capture the phone's traffic at cold start and
  after one OCR run first.
- [ ] **Health apps declaration**: as in section 3. Recap drafts clinical
  documentation (SOAP note, patient recap) for professional review and is
  not clinical decision support or a medical device; clinical and legal
  summaries carry a fixed, non-AI disclaimer. The form has no "clinical
  documentation" option, so choose the closest category (expected:
  Medical → Healthcare Services and Management).
- [ ] **AI-generated content**: Play requires "in-app user reporting or
  flagging features that allow users to report or flag offensive content
  to developers without needing to exit the app"
  (support.google.com/googleplay/android-developer/answer/13985936). Recap
  has a Report AI output action on every screen that shows generated text
  (meeting summary, each Ask this meeting answer, the meeting ⋮ menu, Ask
  your library, the written weekly digest, the pre-meeting brief, the live
  Catch me up sheet, Follow-ups): an in-app dialog for a reason and an
  optional comment, then an email draft to `support_email`, with the
  generated text left out unless the user ticks the box. Confirm that a
  dialog followed by a user-sent email draft meets "without needing to
  exit the app"; sending happens in the email app. If it does not, a
  server endpoint would be needed, which this privacy-first app avoids
  today.
- [ ] **Foreground service declarations** (section 5). If the
  FOREGROUND_SERVICE_MEDIA_PROJECTION video shows the device-audio flow,
  consider re-recording it so it includes the new lock-screen warning.

### Placeholders and published documents

- [ ] Replace `support@example.com` in
  `android/app/src/main/res/values/strings_support.xml` (`support_email`)
  with the Play listing support address. AI output reports are emailed
  there, and the privacy policy names it. Fill the matching Contact
  placeholder in `docs/privacy-policy.md`.
- [ ] Set the effective date in `privacy_policy_effective_date`
  (`android/app/src/main/res/values/strings_privacy.xml`, currently
  `[effective date]`) and the same date at the top of
  `docs/privacy-policy.md`.
- [ ] Host the privacy policy (`docs/privacy-policy.md`) and the Terms of
  Use (`android/app/src/main/assets/terms_of_use.txt`) at public URLs
  reachable without login, then put the privacy policy URL in Play Console
  and link the terms from the listing (section 3).
- [ ] Set up a way to triage the "Recap AI output report" emails that
  arrive at the support address, and decide on a response time.

### Android developer verification

- [ ] In Play Console, check that `com.recap.mobile` is registered for
  Android developer verification (new Play apps are registered when
  created), and register the upload key as an additional signing key,
  identified by its certificate SHA-256 fingerprint (the public value in
  section 1). Play App Signing registers only Play's own app-signing key,
  and sideloaded APKs from GitHub are signed with the upload key, so they
  need that key registered to stay installable on certified devices as
  verification rolls out. Since Sept 30, 2026 it protects users installing
  from participating stores in select regions (Brazil, Indonesia,
  Singapore and Thailand), and Google rolls it out globally in 2027
  (developer.android.com/developer-verification). Google's FAQ names the
  Android Developer Console for adding extra keys, so check which console
  applies.

### Legal and licences

- [ ] Have a lawyer review `android/app/src/main/assets/terms_of_use.txt`
  and the licence acceptance wording (`licences_accept_body` and
  `licences_accept_hai_def_note` in `strings_licences.xml`) before the
  paid launch. The terms refer to "Recap's developer" without naming a
  legal entity, carry a "Last updated: 21 November 2026" date, and point
  to the Play listing support address for contact.
- [ ] Have a qualified reviewer approve the clinical and legal disclaimer
  wording (`aisafety_disclaimer_clinical`, `aisafety_disclaimer_legal` in
  `strings_aisafety.xml`) for the markets you ship in.
- [ ] Get advice on whether Recap counts as "distributing" the Gemma,
  MedGemma and Llama models: the app downloads them from third-party
  repositories, not from its own servers. Also on Health AI Developer
  Foundations terms 3.1.3 (seek health regulatory authorisation where
  applicable) and 3.2 (no use that could make Google a medical-device
  "manufacturer"), given MedGemma and the clinical templates in a paid
  app. This ties in with the Health apps declaration.
- [ ] Confirm, from the publishers' pages, the licences the app labels
  "see model page": Nemotron 3.5, Nemotron EN, TitaNet large/small, Gemma
  4 E2B/E4B, Qwen 3.5 9B and Mistral 7B v0.3. Update `ModelLicences.kt`
  and the MODELS text in `build_notices.py` together (ModelLicencesTest
  keeps them in step) and regenerate. Gemma 4 matters most: if it is under
  the Gemma Terms, setting `Licence.GEMMA` brings it under the acceptance
  gate. Do not describe Gemma 4 as Apache-licensed until then.
- [ ] Re-run `python3 android/scripts/build_notices.py` on a machine with
  unrestricted network access, check that the ONNX Runtime LICENSE URL
  resolves (fix it if not), and commit any diff.
- [ ] *Decision*: should installs that downloaded Gemma 3, MedGemma or
  Llama before this release be asked to accept the licence on their next
  use? The gate only fires at download time.

### Product decisions

- [ ] *Decision*: keep ML Kit with the disclosure, or replace it (for
  example Tesseract, after an A/B on real whiteboard photos) to drop the
  Google diagnostics altogether.
- [ ] *Decision*: new installs get audio-only meetings until they download
  a transcription model. Consider shipping guided model setup at first run
  (the roadmap's device-fit model setup) with this release.
- [ ] Plan for Local Network Protection: opt-in on Android 16 and to be
  enforced in a later release, it will gate LAN Ollama on private IPs.
  Test it now with
  `adb shell am compat enable RESTRICT_LOCAL_NETWORK com.recap.mobile`.
- [ ] Optional: consider opting into Safer Intents
  (`android:intentMatchingFlags="enforceIntentFilter"`) after testing the
  share, Quick Settings tile, widget, boot and calendar receivers. Android
  16 does not require it.

### Device checks

Target API 36, large screens and back:

- [ ] On an Android 16 tablet, or a sw600dp emulator / unfolded foldable,
  rotate during each of these: the recording-consent dialog, the
  microphone and notification permission prompts, the device-audio
  (MediaProjection) consent dialog, a live recording, "Finishing..." after
  tapping Finish, a camera capture, and the import permission prompt,
  model picker and progress screen. There must never be a second consent
  dialog, a second session or a lost photo, and a session finished
  mid-rotation must not start a new one.
- [ ] Smoke-test predictive back on Android 16, with gesture navigation and
  with 3-button long-press: RecordingActivity back sends the app to the
  background, TranscriptCheck holds back while applying, and LockActivity
  sends the task to the background.
- [ ] Look at non-Latin transcripts (Thai, Arabic, Tamil and others) on
  Android 16: `elegantTextHeight` is ignored at target 36, so line height
  can change in transcript lists and exports.

Edge-to-edge:

- [ ] Check each screen in day and night, portrait and landscape, with
  both 3-button and gesture navigation, at least on API 26 or 27, API 29
  or 30, and Android 15/16. Look for content clear of the bars, lists
  scrolling under the nav bar, bar icon contrast, and keyboard behaviour on
  Recording notes, Ask, Settings fields, Main search and Detail edits.
- [ ] Open each bottom sheet (actions, model picker, catch-up during a
  recording) and check its background runs under the nav bar with
  readable nav icons. Expand the model picker to full height and check the
  top under the status bar.
- [ ] When editing `activity_privacy.xml` or `activity_licenses.xml`, keep
  `android:id="@+id/edgeScroll"` on the scroller that runs to the bottom
  edge (without it the end of the list sits under the nav bar), keep the
  `EdgeToEdge.setUp(...)` line right after `setContentView` in each
  activity, and keep `EdgeToEdge.padRoot` in RecordingActivity's keyboard
  listener. All three are in place on lb/integration.

Privacy and the on-device default:

- [ ] Fresh install: the engine defaults to on-device. The first meeting
  with no model records audio only, shows no "Import failed"
  notification, and the Transcript tab shows the download hint. After
  downloading a model, ⋮ → Check transcript accuracy transcribes it.
- [ ] Upgrade from 3.14.0-beta5 on the system engine: the engine stays
  "system" and the one-time notice appears once.
- [ ] Plain-http Ollama on a LAN IP and on a `.lan` hostname still work.
- [ ] Every model download (Hugging Face Whisper/NeMo/GGUF, GitHub
  speaker models) still works now that downloads are https-only.
- [ ] Turning off the on-device switch in Settings shows the
  confirmation, and Cancel turns it back on.

Device audio (Android 15 QPR1+ or Android 16):

- [ ] Start a Device audio recording: the lock warning appears before the
  system consent; "Don't show this again" keeps it hidden next time; "Use
  microphone" starts a microphone session.
- [ ] During a device-audio recording, lock the screen, then unlock. The
  lock-screen notification reads "Device audio stopped"; the shade
  notification reads "Device audio stopped — tap Finish to save" with no
  Pause/Resume button and a frozen time; the recording screen shows the
  new status with Pause disabled; Finish saves a meeting whose audio ends
  at the lock. Repeat by stopping sharing from the status bar chip.

AI safety:

- [ ] Generate a SOAP summary and a `legal_consult` summary: the banner
  shows, and Share, Copy, Markdown and PDF export each carry the notice
  once. Send one report with no email app installed to check the
  clipboard fallback.

Accessibility:

- [ ] Run Accessibility Scanner on the home, recording, meeting detail
  (Summary, Transcript and Audio tabs), settings, import and
  accuracy-check screens. Check touch targets, missing labels and
  contrast (contrast was not part of this change).
- [ ] TalkBack pass on a real device: start/pause/resume/stop a recording
  and listen for the announcements; import a file; generate a summary; on
  a transcript line open the actions menu and try Play from here / Edit
  text / Split / Share clip / Highlight; expand and collapse chapters; on
  the Audio tab use the speaker strip's next/previous turn; and check the
  action-item reminder actions. Do not claim screen-reader support in the
  listing until this pass is done.
- [ ] Look over the result on a small phone (360dp wide) and a typical
  one: home header and tagline, detail tabs, chips, the speaker strip on
  the Audio tab, the notes toolbar, and the home-screen widget on your
  launcher.
- [ ] Check the pre-launch report's accessibility section after the next
  internal-track upload.

## Notes and gotchas

- **App size**: the AAB is ~26 MB; Play splits per-device. All speech/AI
  models are downloaded in-app at runtime, so Play's size limits are a
  non-issue — but the listing should mention large optional downloads.
- **Target API level**: Play has required new apps and app updates to
  target Android 16 (API 36) since Aug 31, 2026; extensions ran only to
  Nov 1, 2026, so a targetSdk 35 bundle can no longer be uploaded. Recap
  targets 36 (compileSdk 36, AGP 8.10.1, Gradle 8.14.3, androidx.core
  1.17.0, Kotlin 2.0.21, JDK 17). What changes at 36 on Android 16:
  - Edge-to-edge is handled in code (`EdgeToEdge.setUp` in every
    activity, with system-bar, cutout and keyboard insets), so the API 36
    removal of `windowOptOutEdgeToEdgeEnforcement` needs no further work.
  - Predictive back is on. The app uses only `OnBackPressedDispatcher`, so
    `onBackPressed`/`KEYCODE_BACK` no longer being called changes nothing.
  - `screenOrientation` is ignored on displays 600dp and wider, so the
    portrait-locked recording and import screens rotate there; both keep
    their state across the re-creation.
  - `elegantTextHeight` is ignored, which can change line height for some
    non-Latin scripts in transcript lists and exports.
  - Next steps up: 36.1 needs AGP 8.13+ (and is what androidx.core 1.18
    needs); 37 needs AGP 9.1.1+, and apps targeting Android 17 (API 37)
    need the `ACCESS_LOCAL_NETWORK` runtime permission to reach LAN
    devices such as an Ollama server.
- **Package identity**: com.recap.mobile is now permanent; the Kotlin
  namespace remains com.meetily.mobile internally, which is fine and
  invisible to users.
- **Existing sideloads**: the beta installed from this session's APKs uses
  the old ID and will remain as a separate app. Migrate meetings with
  Settings → Backup on the old app → Restore on the new one, then uninstall
  the old.
