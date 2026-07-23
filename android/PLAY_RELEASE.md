# Recap — Google Play release runbook

Everything needed to take Recap from this repo to the Play Store, in order.
App ID: **com.recap.mobile** (permanent). Current release: v3.0.0-rc1
(versionCode 48), targetSdk 35.

## 1. One-time: add the signing secrets

You received four values with the upload keystore (SECRETS.txt). In GitHub:
repo → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | contents of `recap-upload.keystore.base64` (one long line) |
| `ANDROID_KEYSTORE_PASSWORD` | from SECRETS.txt |
| `ANDROID_KEY_ALIAS` | `recap-upload` |
| `ANDROID_KEY_PASSWORD` | from SECRETS.txt |

Keep `recap-upload.keystore` + SECRETS.txt somewhere safe and private (a
password manager). This is the **upload key** — with Play App Signing
(enroll when creating the app; it is the default) Google holds the actual
app signing key, so a lost upload key can be reset via Play support.
**Never commit the keystore or passwords to the repo.**

## 2. Build the signed bundle

GitHub → Actions → **Release Android (signed AAB)** → Run workflow (on the
default branch or this branch). Download the `meetily-android-release`
artifact; it contains `app-release.aab` (upload this to Play) and signed
APKs (for sideload checks).

## 3. Create the app in Play Console

Play Console → Create app: name **Recap**, default language, App (not
game), **Free**. Then complete "Set up your app":

- **Privacy policy URL**: publish `docs/privacy-policy.md` publicly first.
  Easiest: repo → Settings → Pages → deploy from branch → `main` `/docs`,
  then use `https://<user>.github.io/<repo>/privacy-policy` — or simply use
  the raw GitHub URL to the file. The URL must be reachable without login.
- **App access**: all functionality is available without credentials —
  select "All functionality is available without special access".
- **Ads**: No, the app contains no ads.
- **Content rating questionnaire**: category Utility/Productivity; answer
  No to violence/sexuality/etc. Expect an "Everyone" rating.
- **Target audience**: 18+ (or 13+); do NOT target children.
- **News app**: No. **COVID-19 app**: No.
- **Data safety** (see section 4).
- **Government app**: No.

## 4. Data safety form (the important one)

Recap's honest answers, given the on-device architecture:

- **Does your app collect or share any of the required user data types?**
  → **No.** Rationale: all recordings, transcripts, and notes stay in app
  private storage; the developer operates no servers and receives nothing.
  Play's definition of "collect" is transmission off the device **to the
  developer or third parties by the app's design**. The two user-configured
  egress paths are worth double-checking against current policy wording:
  - User-configured AI endpoints: user-initiated, user-chosen destination,
    off by default. If you prefer maximum caution, declare "data shared:
    audio/other in-app messages → service providers, optional, user-enabled"
    — but "No collection" is defensible and matches comparable local-first
    apps.
  - System speech recognition: OS service, not the app transmitting.
- **Encryption in transit**: model downloads and optional endpoints use
  HTTPS (plain HTTP is refused outside private networks).
- **Deletion**: users delete data in-app; uninstall removes everything.

## 5. Sensitive permission declarations

The console will ask about these (all have in-app justifications):

- **RECORD_AUDIO / FOREGROUND_SERVICE_MICROPHONE**: core feature — meeting
  recording, explicitly user-initiated, with ongoing notification. Provide a
  short screen-recording video of: tap orb → consent dialog → recording
  screen with live transcript → notification. (Console asks for a video
  link for microphone foreground-service use; unlisted YouTube works.)
- **FOREGROUND_SERVICE_MEDIA_PROJECTION**: "record device audio" mode for
  webinars — user grants via the system screen-capture dialog each time;
  audio only.
- **FOREGROUND_SERVICE_DATA_SYNC**: file import transcription, model
  downloads, summary generation — long-running user-initiated work.
- **SCHEDULE_EXACT_ALARM**: user-set action-item reminders and calendar
  nudges (falls back to inexact windows when not permitted). This is an
  allowed use case (user-facing alarms/reminders).
- **READ_CALENDAR**: optional pre-fill/nudges, read-only, declared in the
  privacy policy.

## 6. Store listing

- **App name (30)**: `Recap — On-device AI Meeting Notes` (or just `Recap`)
- **Short description (80)**:
  `Record, transcribe & summarize meetings 100% on your phone. No cloud, no account.`
- **Full description**: see `PLAY_LISTING.txt` alongside this file.
- **App icon**: `play-icon-512.png` (512×512, delivered separately).
- **Feature graphic**: `play-feature-1024x500.png` (1024×500).
- **Screenshots**: at least 2 phone screenshots (take from your device:
  home with meetings list, recording screen with live transcript, a meeting
  page with summary + insights, model manager). PNG/JPG, 16:9–9:16.
- **Category**: Productivity. **Tags**: note-taking, transcription.
- **Contact email**: required — your developer contact email.

## 7. Testing track first (required for personal accounts)

Personal developer accounts created after Nov 2023 must run a **closed test
with at least 12 testers continuously for 14 days** before production
access is granted. Plan: upload the AAB to a Closed testing track, add a
tester email list (friends/colleagues), have them opt in via the test link,
and keep the test live 14 days. Organization accounts skip this.

Recommended ladder regardless of account type:
1. **Internal testing** (instant, up to 100 testers) — sanity-check install,
   permissions, model downloads over Play delivery.
2. **Closed testing** — the 14-day/12-tester run if required.
3. **Production** — staged rollout at 20% → 100%.

## 8. Per-release loop (after setup)

1. Bump `versionCode` (+ `versionName`) in `android/app/build.gradle.kts`.
2. Run the **Release Android (signed AAB)** workflow; download the AAB.
3. Play Console → the track → Create new release → upload AAB → release
   notes → roll out.

## Notes and gotchas

- **App size**: the AAB is ~26 MB; Play splits per-device. All speech/AI
  models are downloaded in-app at runtime, so Play's size limits are a
  non-issue — but the listing should mention large optional downloads.
- **targetSdk deadline**: Play requires new apps to target an API level
  within one year of the latest Android release. We target 35; when Google
  enforces 36 (expected Aug 31, 2026), bump `compileSdk`/`targetSdk` and
  re-test (edge-to-edge opt-out currently in the theme will need a real
  insets pass when the opt-out attribute is removed in a future API).
- **Package identity**: com.recap.mobile is now permanent; the Kotlin
  namespace remains com.meetily.mobile internally, which is fine and
  invisible to users.
- **Existing sideloads**: the beta installed from this session's APKs uses
  the old ID and will remain as a separate app. Migrate meetings with
  Settings → Backup on the old app → Restore on the new one, then uninstall
  the old.
