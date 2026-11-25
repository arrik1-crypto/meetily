# Release blockers: Recap 3.22.0-beta1

Dated 2026-11-25. This file sorts the owner checklist in
[PLAY_RELEASE.md](PLAY_RELEASE.md) section 11 by what blocks the first
Google Play submission. It reflects **3.22.0-beta1** (versionCode 117), the
pre-release review fixes on `r1/integration`: five merged fix chains
(merge tip `d3ddc47`), the docs commit that adds this file (`6891a73`),
the fix commits after it up to `db0c8c8`, the last code commit (all
listed under D), and a docs-only sync on top of them that brings this
file and PLAY_RELEASE.md up to date. Until the version bump, which the
maintainer makes in its own commit on top of this series, the build
still reads 3.21.0-beta1 (versionCode 116). It is a snapshot:
PLAY_RELEASE.md remains the source of truth.

**Where the items come from.** Sections 1 to 10 of PLAY_RELEASE.md have no
checkboxes, so every open item is in section 11. Some prose steps in
sections 1 to 10 also block the first upload; they are added here and
marked *prose*. Items are named by their PLAY_RELEASE.md section and first
words, not by line number: the file grew by about 700 lines in this
release, and line numbers move with every edit.

**How each item was checked.** Against:
- the code at `db0c8c8`, the last code commit (3.21.0-beta1 at `818d523`
  plus every review fix);
- the APK that `818d523` published (`android/dist/meetily-android.apk`,
  arm64-v8a only, libraries compressed);
- the log of the CI run that built it (Build Android APK, run 45216817663,
  on `9476ee2`).

No CI run has built the review fixes yet, and no AAB has ever been built
by CI.

**What the classes mean**

- **A. Blocks the first submission, and only the owner can do it.**
- **B. Blocks the first submission, and Claude (the maintainer) can do it
  in the repository.**
- **C. Can wait.** It has two parts:
  - **C1:** do it before the production (paid) rollout. It does not have
    to be done before the internal or closed test upload. If you go
    straight to production (an organization account skips the closed
    test), treat C1 as A. With a personal account, the health scope (A1)
    can make one C1 item blocking.
  - **C2:** after the first upload, or later.
- **D. Stale or already done**, with the evidence. Items the review fixes
  resolved are at the top of D, with their commit.

## Counts

| Class | Open checklist items | Prose steps (sections 1–10) | Not in the checklist |
|---|---|---|---|
| A. Blocks, owner only | 7 | 7 | 1, plus the device pass (A14) |
| B. Blocks, Claude can do | 4 | 0 | 0 |
| C. Can wait | 65 (C1: 20, C2: 45) | 0 | 5 known leftovers, plus the Accessibility checks in C1 |
| D. Stale or done | 12 | 4 | 0 |
| **Total** | **88** | **11** | |

D also lists two parts of items that stay open (the nlohmann/json half of
a licence item, the backup-name half of a product decision).

Not triaged here:
- The 316 open **Device checks** under section 11, 49 of them new for
  3.22.0-beta1. Run [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md) instead
  (A14): it condenses them into one 60 to 90 minute pass, and says which
  ones a step covers in full.
- The 11 items already ticked in section 11.

## A. Owner only, in the order to do them

1. **Developer account type and Play Console state** (not in the
   checklist; PLAY_RELEASE.md leaves it open)
   - Do: check whether the Play developer account is personal or an
     organization, and that `com.recap.mobile` does not exist in Play
     Console yet (or, if it does, that it is Paid).
   - Why: a personal account created after November 2023 needs the
     12-tester, 14-day closed test before production (A15). Play Console
     Help, "Choose a developer account type" (answer 13634885), requires an
     organization account for "Health apps, such as medical apps and human
     subjects research apps", so on a personal account the Medical answer
     in A9 can bring an "Organization account required" rejection. Either
     get an organization account (it needs a D-U-N-S number, which takes
     time) or settle the health scope first, which makes the C1 item on
     distributing Gemma 3, MedGemma and Llama (and HAI-DEF terms 3.1.3 and
     3.2) blocking: whether MedGemma and the clinical templates stay in
     the paid app.
2. **Payments profile** (§9 step 1, *prose*)
   - Do: Play Console → Settings → Payments profile. Enter your business
     or individual details, tax forms and payout bank account.
   - Why: verification can take days, and nothing paid can ship until it
     clears, so start it early.
3. **Create the app as Paid** (§3, *prose*)
   - Do: Play Console → Create app. Name it Recap, choose App, and choose
     **Paid**. Keep Play App Signing, which is the default.
   - Why: a free app can never become paid, and every App content page
     below (A7 to A12) exists only once the app does.
4. **Upload key backup** (§1, *prose*)
   - Do: confirm that a private, offline copy of `recap-upload.keystore`
     and its password exists (a password manager, say).
   - Why: CI and the APK prove the secrets work (D), but not that a backup
     exists. Losing the key breaks in-place sideload updates, and resetting
     the Play upload key needs Play support.
5. **Support email** (§11 Placeholders, "Replace `support@example.com`")
   - Do: create the address you will put on the Play listing. Send it to
     Claude, or replace it yourself in two places:
     - `support_email` in `android/app/src/main/res/values/strings_support.xml`
       (still `support@example.com`);
     - the Contact placeholder at the end of `docs/privacy-policy.md`.
   - Why: the in-app privacy policy, the Report AI output emails and the
     listing's required contact email all use it. It is compiled into the
     app, so it has to be in the build that is uploaded (A15).
6. **Effective date** (§11 Placeholders, "Set the effective date")
   - Do: choose the date. It goes in `privacy_policy_effective_date` in
     `strings_privacy.xml` (still `[effective date]`) and at the top of
     `docs/privacy-policy.md`. Claude can make both edits once you send the
     date.
   - Why: otherwise the published policy and Settings → Privacy policy
     both show a placeholder. 3.22.0-beta1 changed the policy text (two
     sentences, the same in both copies), so pick the date after this
     release's text.
7. **Host the privacy policy and Terms of Use** (§11 Placeholders, "Host
   the privacy policy"; after A5 and A6)
   - Do: follow §3: GitHub Pages from the Android branch,
     `claude/meeting-summarizer-android-apk-41ewdo`, folder `/docs`. Check
     `…/privacy-policy` and `…/terms-of-use` in a private window, then
     enter the policy URL under Policy → App content → Privacy policy and
     link the terms from the listing's website or description.
   - Why: Play Console requires a privacy policy URL. The repository side
     is done (D: `docs/terms-of-use.md` and the corrected §3); only the
     Pages setting, which needs the repository owner, is left.
8. **App content basics** (§3, *prose*). Play needs these answers before
   it reviews a release.
   - Do, in Play Console → Policy → App content:
     - App access: "All functionality is available without special access".
     - Ads: No.
     - Content rating questionnaire: Utility/Productivity. Answer each
       question on its merits (including any about AI-generated content);
       expect an "Everyone" rating, as §3 says.
     - Target audience: 18+ (or 13+), not children.
     - News: No. COVID-19: No. Government: No.
     - Financial features: no financial features. This form is required
       for every app, test tracks included, and §3 does not list it.
     - Advertising ID: No. The manifest declares no
       `com.google.android.gms.permission.AD_ID`, and the 818d523 APK's
       merged manifest has none either; 3.22.0-beta1 adds no permission.
9. **Health apps declaration** (§11 Play Console declarations; see also §3)
   - Do: App content → Health apps → Medical → Healthcare Services and
     Management. Do not choose Clinical Decision Support or Medical Device.
     Describe the fixed, non-AI disclaimers. Settle A1 first.
   - Why: every app must complete this form, test tracks included. With
     the clinical templates and MedGemma in the app, "no health features"
     is hard to defend.
10. **Data safety** (§11 Play Console declarations; the answers are in §4)
    - Do, in App content → Data safety:
      - Does the app collect or share user data: Yes.
      - Declare App info and performance → Diagnostics, and Device or other
        IDs: collected, not ephemeral, required, purpose Analytics.
      - Decide "shared". §4 suggests the cautious Yes, for Analytics.
      - Encrypted in transit: Yes.
      - Deletion request: most likely No.
    - Why: ML Kit text recognition sends Google diagnostics, so "No data
      collected" would be false.
    - Checked: the 818d523 APK's permission list matches §5, with no
      `ACCESS_LOCAL_NETWORK`. §4 was re-checked for 3.22.0-beta1: no
      permission, data type or network path was added.
11. **AI-generated content: the reporting flow** (§11 Play Console
    declarations)
    - Do: decide whether Report AI output meets Play's requirement that
      users can report "without needing to exit the app". The reason and
      comment are collected in an in-app dialog, but the report is sent
      from the user's own email app: `AiReport.kt` opens a `mailto:` draft,
      or copies the report to the clipboard if there is no email app.
    - Why: if Play decides this counts as leaving the app, review fails,
      and an in-app reporting channel has to be built first.
12. **Foreground service declarations and demo videos** (§11 Play Console
    declarations; the wording is in §5)
    - Do, in App content → Foreground service permissions. Nothing exists
      in Play Console yet, so every declaration is a first one:
      - mediaProcessing (ImportService, ClearVoiceService, PassageService
        and AlignService) and mediaPlayback (PlaybackService): §5 has ready
        wording and a video plan for each.
      - microphone, mediaProjection and dataSync: §5 has one-line
        justifications only, so write full declarations. dataSync covers
        model downloads, summaries, enhanced notes and speaker suggestions
        (ModelDownloadService and SummaryService are dataSync only), plus
        import, transcription, Clear voice, Remove a passage and Re-time
        words on Android 14 and lower (the four mediaProcessing services
        are declared `dataSync|mediaProcessing`).
      - The microphone declaration should also cover the end of a
        recording (3.22.0-beta1): after Finish or a swipe from Recents
        (now a Finish), RecordingService stays in the foreground,
        capturing nothing, with a "Finishing transcription…"
        notification. It stays while the last live words are
        transcribed: 15 s at most, as in 3.21.0-beta1, or up to 60 s
        when Finish came while the live model was still loading. It
        then stays for the moment it takes to save the meeting, close
        its audio file (3 s at most) and start or queue its transcript,
        check or summary. In device-audio mode the media projection
        (and Android's capture indicator) also stays until then;
        capture itself stops at Finish. Say so in the mediaProjection
        declaration too.
    - Then record the videos (unlisted YouTube is fine). Only the
      mediaProcessing videos need an Android 15+ phone; plan the
      mediaProjection and dataSync ones too. DEVICE_TEST_PLAN.md walks
      every one of these paths and says where to capture them with
      `adb shell screenrecord`.
    - Why: Play requires a declaration for every foreground service type an
      app targeting Android 14+ uses.
13. **Store listing** (§6, *prose*). Required before a release can be
    reviewed.
    - Do, in Main store listing:
      - name and short description (§6);
      - full description from `PLAY_LISTING.txt` (3,993 characters, under
        the 4,000 limit; unchanged by 3.22.0-beta1);
      - 512×512 icon and 1024×500 feature graphic: §6 says they are
        "delivered separately", and neither `play-icon-512.png` nor
        `play-feature-1024x500.png` is in the repository, so supply or
        locate them;
      - at least 2 phone screenshots from this build;
      - category Productivity;
      - contact email from A5.
    - Also decide what backs the listing's last line, "Recap is open
      source.": the repository is a public fork of Zackriya-Solutions/meetily,
      whose root `LICENSE.md` is "MIT License, Copyright (c) 2024 Zackriya
      Solutions", and `android/` has no licence or copyright statement of
      its own. For a paid app, decide (with the lawyer of the C1 terms
      review) which licence the Android code is under, or drop the line.
14. **Device pass** (§11 Device checks, through DEVICE_TEST_PLAN.md)
    - Do: run DEVICE_TEST_PLAN.md on the 3.22.0-beta1 build on one arm64
      Android 15 or 16 phone (60 to 90 minutes for the core), and decide on
      every failure. Then, on the build with A5 and A6 filled in, the
      plan's short final-build re-smoke.
    - Why: nothing in 3.22.0-beta1 has run on a device; every expectation
      comes from the code.
15. **First upload** (§2 and §7, *prose*; after A5, A6, B1 and B2)
    - Do:
      1. Push the `android-v<versionName>` tag on the commit that carries
         A5, A6 and the version bump, once CI is green on it (§2). B2's
         dry run used its own tag, so this name is still free; if a tag
         of this name exists anyway, delete it first (§2).
      2. Upload `app-release.aab` to Internal testing, downloaded from
         the run of that tag whose commit is the A5/A6 commit (the run
         page shows it), never from B2's dry run, whose bundle still has
         the placeholders compiled in.
      3. Install from the internal track (or with bundletool from the AAB)
         on an Android 8 to 14 phone and an Android 15 or 16 phone, and
         smoke-test the build Play delivers: the engines load (including
         the `libmeetily_native_v82` fallback), sherpa-onnx with
         onnxruntime, ML Kit OCR, one model download and one recording.
         The AAB stores native libraries uncompressed, while every APK
         tested so far compressed and extracted them.
      4. Read the pre-launch report, including tablets and its
         accessibility findings.
      5. Add your testers under Settings → License testing.
      6. Before promoting past internal testing, run Accessibility Scanner
         and a short TalkBack pass (§7, §8 step 6).
      7. With a personal account, run a closed test with at least 12
         testers for 14 days.
      8. Archive the release artifact's `native-debug-symbols.zip`.
    - Why: this is the submission itself. Personal accounts get production
      access only after the closed test.
16. **Pricing** (§9 steps 2–3, *prose*)
    - Do: Monetization setup → join the 15% service-fee tier. Then
      Monetization → App pricing → $7.99. Leave the $4.99 sale until
      production.
    - Why: a Paid app needs a price, and the price can only be set once the
      payments profile clears.

## B. Claude can do (the maintainer, in the repository)

1. **Version bump to 3.22.0-beta1 (117) and a green CI** (§11 Version and
   build, "Bump `versionCode` to 117" and "Run CI on the release commit")
   - Do: bump `versionCode` and `versionName` in
     `android/app/build.gradle.kts` in its own commit on top of this
     series (after `db0c8c8`, the last code commit, and the docs-only
     sync that follows it), and push the review-fix merge and the bump to
     the Android branch together, in one push (or the bump first). CI
     publishes every Android-branch build to the release named after its
     versionName, replacing that release's APK, so a push of the merge
     alone would overwrite the real 3.21.0-beta1 download with unbumped
     review-fix code. Then get `Build Android APK` green: unit tests,
     `assembleRelease` with `lintVital`, the export and baseline profile
     checks. The item lists the calls the local check could not verify.
   - Why: Play rejects a versionCode it has seen, and none of the review
     fixes has been through Gradle, lint or R8.
2. **Dry-run the release workflow and inspect the first AAB** (§11 Version
   and build, "Dry-run the release workflow", and the 16 KB item)
   - Do: push a dry-run tag, `android-v3.22.0-beta1-dryrun`, on the
     bumped commit, once the owner agrees to a signed build with the
     stored key. Not `android-v3.22.0-beta1`: that is A15's tag, on the
     later A5/A6 commit, and both runs upload an artifact of the same
     name. The run warns that the tag does not match versionName, which
     is expected here. Its bundle still has the placeholder support
     address and effective date compiled in and must never be uploaded
     to Play; delete the tag when done (`git push --delete origin
     android-v3.22.0-beta1-dryrun` and `git tag -d
     android-v3.22.0-beta1-dryrun`). Read the whole log, then
     check: both ABIs pass `check_16kb_alignment.py`; the uncompressed
     `.so` entries of the APKs generated from the AAB (bundletool
     `build-apks`) pass `zipalign -c -P 16 -v 4`; the native debug symbols
     in BUNDLE-METADATA include `libmeetily_native_v82.so`; keep the
     `check_jni_exports.py` output for the `.aab` and record the AAB size
     under Notes and gotchas.
   - Why: `bundleRelease -PuncompressedNativeLibs` and the `.aab` checks
     have never run, and the 16 KB evidence so far covers only the arm64
     sideload APK, whose libraries are compressed (so zip alignment did
     not apply to it).
3. **Fill the placeholders once the owner sends them** (A5, A6)
   - Do: put the support address and the effective date into
     `strings_support.xml`, `strings_privacy.xml` and
     `docs/privacy-policy.md` (both places for each), run the local checks
     and CI. That commit, not an earlier one, is the one to tag for A15.
   - Why: both values are compiled into the app.

## C. Can wait

### C1. Before the production (paid) rollout (20, plus the Accessibility checks)

| Item | Section | Why it can wait until production |
|---|---|---|
| Triage process for Report AI output emails | Placeholders | Reports arrive only once people use the app; have it working before paying users do. |
| Lawyer review of the Terms of Use and licence-acceptance wording | Legal | The item itself says "before the paid launch"; the terms name no legal entity. Include the licence of Recap's own code (A13). |
| Qualified review of the clinical and legal disclaimers | Legal | Needed per market before sale; the disclaimers already ship and are quoted in the Health form (A9). |
| Advice on "distributing" Gemma 3, MedGemma and Llama, and HAI-DEF terms 3.1.3 and 3.2 | Legal | Decides whether MedGemma and the clinical templates stay in the paid app. Blocking on a personal account (A1). |
| Confirm the "see model page" licences (Nemotron 3.5, Nemotron EN, TitaNet, Mistral 7B) | Legal | The labels are honest placeholders, not wrong; Claude can read the publisher pages and update `ModelLicences.kt` and `build_notices.py`. |
| Re-check the Batch 3 licence labels (Gemma 4, Qwen 3.5, Parakeet Ultra, Silero, KleidiAI) | Legal | Checks labels already in the code; Claude can check the publisher pages. |
| Approve the terms' Silero wording | Legal | Part of the terms review. |
| Check the Batch 5 licence and notices changes | Legal | Publisher pages, plus the Open-source licences screen on a phone. |
| Approve the terms' GTCRN wording | Legal | Part of the terms review; set "Last updated" in both copies of the terms (`terms_of_use.txt` and `docs/terms-of-use.md`) when it is approved. |
| Batch 7 licences: the MFA CC BY 4.0 credit, and a decision on the eSpeak NG phrase readings | Legal | The lexicon reaches the phone from sherpa-onnx's GitHub release, not from Recap; decide before paying users get Re-time words. |
| Pin the engine sources (`URL_HASH` for llama.cpp v1.0.0 and whisper.cpp b5907) | Version and build | Supply-chain hardening of build inputs, and the only fix for the keystore being on disk while that unpinned CMake runs. Needs a machine that can reach GitHub's archive downloads; the review environment could not. |
| *Decision*: keep ML Kit or replace it | Product decisions | The build and the §4 answers assume it stays; decide before production if the public listing should say "No data collected". |
| *Decision*: "Meetily app" in the Markdown export | Product decisions | An old product name visible to users. The backup file names, the other place it showed, are fixed (D). Claude can change it together with ExportTextTest. |
| *Decision*: "Keep meeting audio" off now removes an audio-only recording's audio after its first complete transcript | Product decisions | Deletes user data automatically; the privacy policy now says so. Confirm before paying users rely on it. |
| *Decision*: app lock and the Recents preview | Product decisions | With Hide content off, the Recents card shows meeting text until the lock covers it, and with it off or on, the screen being reopened can show for a moment first. Privacy-relevant, not a Play requirement. |
| *Decision*: on-device AI beside transcription | Product decisions | Needs the 6 GB device check first; an ANR or low-memory kill there would need a gate before production. |
| *Decision*: restore asks first, "Keep this phone's" by default | Product decisions | The safe default ships; confirm the wording. |
| Review the Batch 7 strings on a phone | Product decisions | Wording only. |
| Review the Batch 6 strings on a phone | Product decisions | Wording only; LiveUpdatesTest already enforces the 6-character chip limit. |
| Review the Batch 5 strings on a phone | Product decisions | Wording only. |
| Accessibility Scanner and TalkBack pass (Device checks, Accessibility) | Device checks | Blocks promotion past internal testing (§7), so before the closed test and production; also before claiming screen-reader support. |

### C2. After the first upload, or later (45)

| Item | Section | Why it can wait |
|---|---|---|
| Watch sherpa-onnx v2.0.3 for regressions | Version and build | Ongoing; the fallback steps are written down. |
| Measure what KleidiAI costs in size | Version and build | Only feeds `ACCELERATION_FINDINGS.md`. `gh` cannot authenticate in the review environment, but the public REST API (`api.github.com/repos/<owner>/<repo>/actions/jobs/<job_id>/logs`) returns job logs without it. Compare APK sizes only: no AAB has been built. |
| Keep each release's native debug symbols | Version and build | A habit for every upload, starting with the first AAB (the artifact is kept 30 days); A15 step 8 covers the first. |
| Target API 36: pre-launch report on tablets | Play Console declarations | targetSdk is already 36 (`app/build.gradle.kts`); what remains is reading the report after the first upload (A15). |
| Target API 37 | Play Console declarations | Only when moving to targetSdk 37, which needs AGP 9.1.1+. |
| POST_PROMOTED_NOTIFICATIONS | Play Console declarations | A normal permission; check the first upload's permission list; no declaration expected. |
| Android developer verification and upload-key registration | Developer verification | Not a Play submission step. It keeps sideloaded GitHub APKs installable (select regions now, everywhere in 2027). |
| Pin `mit_ort` to the v1.28.2 tag | Legal | Optional; matters only at the next notices regeneration. |
| cpp-httplib notice (the rest of the "Pre-existing licence gap" item) | Legal | nlohmann/json is done (D). No cpp-httplib strings were found in the shipped libraries; confirm, and add it only if any survives. |
| Fix the ONNX Runtime licence URL (404) in `build_notices.py` | Legal | The committed `notices.txt` already has the text; needed before the next regeneration; Claude can do it. |
| *Decision*: ask again for licence acceptance on downloads made before the gate | Legal | Only sideloaded installs can have such downloads; every Play install downloads through the gate. |
| *Decision*: larger first-run summary model on phones with a lot of RAM | Product decisions | The current default works; this is tuning. |
| *Decision*: Parakeet Ultra's pre-#4179 int8 files | Product decisions | An opt-in model that is never suggested; wait for the upstream re-export. |
| *Decision*: keep KleidiAI on, restrict it, or turn it off | Product decisions | Needs the KleidiAI device checks first. |
| *Decision*: suggest the opt-in models on first run | Product decisions | None is suggested today; needs quality and speed checks first. |
| *Decision*: long press starts multi-select | Product decisions | Changes habits only for existing sideload users; Play users start fresh. |
| *Decision*: clean-up safety stop at 10 meetings | Product decisions | A conservative default against data loss; the held-back batch is now also shown on the Storage screen. |
| *Decision*: Manage space replaces Clear storage | Product decisions | The privacy policy already says uninstalling removes everything. |
| *Decision*: vocabulary term goes first after Find & replace | Product decisions | Ordering and wording nicety. |
| *Decision*: German summaries drifting into English in the condense prompt | Product decisions | Depends on a device check. |
| *Decision*: nudge CAM++ users towards the re-keyed entry | Product decisions | Affects only existing installs. |
| *Decision*: offer the clear-voice copy from Re-transcribe as… | Product decisions | A feature extension. |
| *Decision*: library export runs as plain background work | Product decisions | Confirm after the 200-meeting device check. |
| *Decision*: Clear voice is never queued for a charger | Product decisions | Confirm after the 2-hour battery check. |
| *Decision*: Live Updates versus the lock-screen Mark button | Product decisions | A trade-off; users can turn Live Updates off. |
| *Decision*: make the automatic transcription a Live Update | Product decisions | The current choice follows Android's user-initiated rule. |
| *Decision*: give the summary notification a public version | Product decisions | Default private redaction already hides it on lock screens set to hide contents. |
| Optional: Settings entry for the Live Updates toggle | Product decisions | Optional. |
| *Decision*: model downloads promoted as Live Updates | Product decisions | Android's guide questions it. Undoing it is a small change: `LiveUpdates.kt`, `LiveUpdatesTest` (MODEL_DOWNLOAD is in its `promotable` list), the README bullets and the device check. |
| *Decision*: Media3 not adopted | Product decisions | No stable Media3 1.12 yet; the current design works. |
| *Decision*: clean-up moves meetings to Recently deleted | Product decisions | A safe default: the meetings can be restored. |
| *Decision*: several summaries share one action-item list | Product decisions | Matches how regenerating has always behaved; a summary without its list now keeps the items. |
| *Decision*: Remove a passage side effects | Product decisions | Behaviour to confirm; nothing leaves the phone. |
| *Decision*: Re-time words never moves a line's start | Product decisions | Protects chapters, highlights and resume points. |
| *Decision*: Re-time offered on lines whose timings' origin is unknown | Product decisions | UX scope. |
| *Decision*: Re-time has no auto-start, no Live Update and no fit badge | Product decisions | UX scope. |
| *Decision*: recovered recordings (home screen only; live lines kept, nothing queued for their end) | Product decisions | 3.21.0-beta1 behaviour, documented; the audio-only transcript is F19's fix. Finish still replaces live lines that fell behind, as 3.21.0-beta1 did. |
| *Decision*: the end of a live recording (Finish waits up to 60 s for a loading model) | Product decisions | The wait keeps the meeting's words or audio; lines added after a Finish during the load and the 3 s close fallback are new, the rest is 3.21.0-beta1 behaviour, documented. |
| *Decision*: a measured Baseline Profile (Macrobenchmark) | Product decisions | The hand-written profile ships and passes CI's check. |
| Optional: defer `MlKitInitProvider` | Product decisions | Needs a device, and it changes when ML Kit sends diagnostics. |
| Amber find colours | Product decisions | A visual tweak. |
| Optional: Safer Intents | Product decisions | Android 16 does not require it. |
| *Decision*: the import changes (Cancel all deletes staged copies, a deleted model falls back to Settings, start-over keeps the stored language, the Import button always opens the picker) | Product decisions | Each is the safer of the two behaviours; confirm. |
| *Decision*: line edits refused while a meeting is recorded or transcribed | Product decisions | Avoids edits a transcription's next save would undo; confirm. |
| *Decision*: automatic summary waits for the charger on low battery | Product decisions | Avoids a meeting never getting its summary; confirm the Settings wording. |

**Known leftovers from the pre-release review** (not in the checklist,
none blocking; C2):

- ImportService's own `startForeground` is still unguarded: the review
  guarded SummaryService (ce39d39) and the import screen's start (39bcffb),
  not this one. The drain starts it from the foreground, so the risk is
  low.
- Not done, as more than a trivial change: N8 (the manifest's
  PowerConnectedReceiver gets no broadcast at targetSdk 26+; harmless),
  N10 ("On-device summary failed" wording, a literal to move into a string
  resource), N12 (talk-time stats not refreshed after a local line edit),
  N14 (photo text read from a down-scaled decode), N16 (three counts
  without singular forms), N20 (one restore message's wording) and N22 (an
  empty-library backup reads like a non-zip file).
- Settings does not warn when a model a queued import names is deleted
  (the import then falls back, so nothing is lost).
- Follow-ups' .ics export with nothing open says so and writes nothing,
  but leaves the empty file the picker created.
- Clear voice's timeline fix is covered by JVM tests of the trimming only;
  the project has no on-device test, so the device check is the proof.

## D. Stale or already done

### Resolved by the pre-release review fixes (3.22.0-beta1)

| Item | Evidence |
|---|---|
| *Was B1*: make the signed-AAB workflow runnable (§2 and §8 step 4, *prose*) | 3e9c9c8: `release-android.yml` runs on `push: tags: ["android-v*"]` (a tag push uses the tagged commit's workflow, so it works from the Android branch), warns when the tag does not match `versionName`, decodes the keystore only around the signing step, and gained the 16 KB check (`check_16kb_alignment.py`) and the baseline profile check that only `build-android.yml` had. §2 and §8 step 4 now say how to start it (the docs commit). Its first run is B2. |
| *Was B2*: make the privacy policy and terms hostable (§3, *prose*) | The docs commit that adds this file: `docs/terms-of-use.md`, word for word the in-app `terms_of_use.txt`, beside `docs/privacy-policy.md`, and §3's hosting steps rewritten for GitHub Pages from the Android branch's `/docs` (`main` has no policy). The Pages setting is A7. |
| *Was B3*: credit nlohmann/json in the open-source notices (§11 Legal, "Pre-existing licence gap") | 3e9c9c8: nlohmann/json 3.12.0 (MIT) has its entry and licence text in `notices.txt` and `build_notices.py`, produced by the script with the committed ONNX Runtime text substituted. cpp-httplib stays in C2. |
| The Markdown "Meetily" item's backup-name half (§11 Product decisions) | 83c44b0 (N24): the default backup names are `recap-backup.zip` and `recap-backup.recapbak`. The Markdown export line stays in C1. |
| The AAB size in Notes and gotchas (*prose*) | The docs commit: the untraceable "~26 MB at 3.16.0-beta1" is gone, replaced by recording the size from the first release run (B2). |

The fix commits, for reference, all on `r1/integration`: the five merged
fix chains (merge tip `d3ddc47`), then, after the docs commit `6891a73`,
the post-fix review rounds and the settle rounds (`8397802`, between
`d4d1816` and `fe3bcc5`, only synced the docs). Then `3ae6dd0` simplified
the end-of-recording and recovery work of those earlier rounds: it took
out most of what they had added there and went back to 3.21.0-beta1's
shape, keeping every originally confirmed fix. Three review rounds of
that simplification follow, each changing code and docs together;
`db0c8c8` is the last code commit. A docs-only sync of this file and
PLAY_RELEASE.md sits on top, and the version bump comes after it.

| Commit | Area |
|---|---|
| 83c44b0 | Backup, restore, clean-up and storage |
| 54b98ea | App lock and home screen |
| 42c9ef9 | Recording, reminders and nudges |
| 39bcffb | Import |
| 858a22a | Engines (LLM stops, detected language, Parakeet spacing) |
| ce39d39 | AI (summaries, endpoint, Ask, brief, Follow-ups) |
| 758a5d1 | Meeting screen and accuracy check |
| d2bf812 | Clear voice timeline and daily-limit outcomes |
| 3e9c9c8 | Model downloads, CI and release workflow, notices |
| d3ddc47 | Share-file names in any script (cross-chain fix) |
| 2d1f101 | Post-fix review round 1: the recording service stays in the foreground until the work after Finish is started; audio held while the live model loads; share-file clean-up |
| 6da959f | Post-fix review round 2: untitled recordings recognised in any language; swiping away mid-recording is a Finish; "Keep meeting audio" read when the transcript is made |
| 5e46c6b | A recording's delayed stop no longer cancels a start on its way (kept by 3ae6dd0 in a simpler form: such a stop uses its own start id); the app-lock checks match the Product decision |
| c539b08 | Leftover review items: late chunks keep their session's audio and names; a star tapped during the live model load; Ask your library and the brief without waiter threads; the series list off the main thread |
| 94c5801 | Review round 1: Finish during a live model load keeps the meeting's audio (since 3ae6dd0 by waiting for the load, up to 60 s); "Finishing transcription…" notification; sticky restarts and discard stop cleanly |
| 07266d7 | Review round 2: the recording notification's tap never starts a new recording; stars and Mark during the live backlog |
| d4d1816 | Review round 3: batch work waits until a finished recording's live model is freed; recovery transcribes live transcripts that stopped short (the hold and live-short recovery removed by 3ae6dd0) |
| fe3bcc5 | Settle-2 round 1: finished work queued at audio close, recovered live lines kept (superseded by 3ae6dd0) |
| 8e9aecf | Settle-2 round 2: recovered check keeps its marks, recovery from the tile, late lines before the drain (superseded by 3ae6dd0) |
| 3ae6dd0 | Simplified the end-of-recording and recovery work of the rounds above: the audio file is closed before Finish's work, in the same step; Finish waits up to 60 s for a loading live model; the hand-off, the live-model gate and live-short recovery removed; recovery from the home screen only |
| efe8127 | Simplification review round 1, mostly docs: how a Finish during a live-model load behaves, and that the media projection stays until the meeting is saved; the audio-close wait's guard against an endless join moved into its unit-tested helper |
| 31f014f | Simplification review round 2: a failed live load's audio file is saved to the meeting at once, so recovery after a process death finds and transcribes it; the final save keeps a language learnt during Finish |
| db0c8c8 | Simplification review round 3: the screen keeps naming a live model that failed to load; the recording screen disables Finish, Pause and the star during a Finish from the notification or a swipe |

### Done or stale before this release (12 checklist items, 1 prose step)

| Item | Evidence |
|---|---|
| §1 upload key and GitHub secrets (*prose*) | The 818d523 APK is signed with `83:97:D1:…:0C:9B:F0:34` (`scripts/apk_cert.py`), and the CI log says "Signed with the stored keystore: updates install in place." That proves the secrets work; the offline backup is A4. |
| Run CI on the merged Batch 7 branch | Run 45216817663 passed every step: `testReleaseUnitTest`, `assembleRelease` with `lintVitalRelease` and R8, "Check native library exports" and "Check baseline profile". It then published 818d523. |
| Read the "Check baseline profile" log | Both APKs report "ok": 606 classes (225 of them the app's) and 2,123 hot methods (688 the app's). |
| Per-app language list on the built APK | The APK manifest has `localeConfig`, and the compiled config (`res/YN.xml` after AGP's resource path shortening, `isShrinkResources = true`) lists only `en-US`, with no `defaultLocale`. `generateLocaleConfig` is not used, and LocaleConfigTest passed. The Settings → Language step is in the device test plan. |
| Run CI on `b6/integration` | Superseded: the Batch 6 merges (3dcdad8, 7bd5515, 9f74c6d, 8be70f2, aa04669) are in 818d523, which built green, as did 3.20.0-beta1 (run 45155279474). |
| Run CI on `b4/integration` | Superseded: every build from 3.18.0-beta1 to 3.21.0-beta1 contains Batch 4 and built green. |
| Watch Batch 3's first KleidiAI CI run | Run 45216817663 restored KleidiAI v1.32.0 from the cache, its MD5 check printed "OK", and neither configure downloaded it. |
| The 5 new JNI exports pass NativeExportsTest and `check_jni_exports.py` | All five are in `cpp/jni_exports.txt`; the check printed "OK" with 18 `Java_` exports per library. |
| Run CI on the branch you merge | Done for 818d523. For 3.22.0-beta1 it is B1. |
| Record the export after-figures | Superseded, not recorded: Batch 5 merged those libraries, and `ACCELERATION_FINDINGS.md` records the merged library's file sizes only, not the `.dynsym`, `.dynstr` or relocation figures. Run 45216817663 has them if wanted (arm64: 18 exports; `.dynsym` 4,824; `.dynstr` 2,807 or 2,811; `.gnu.hash` 136). |
| Network state permission | Nothing to do: the network-state line is in both copies of the policy (`docs/privacy-policy.md` and `privacy_policy_body`). |
| Republish the privacy policy with a new date | Does not apply yet: the policy has never been published (the GitHub API reports `has_pages=false`, and the placeholders are still there). Both copies carry every change from 3.16 to 3.22.0-beta1, checked word by word (PrivacyPolicyDriftTest compares only the section headings and the two placeholders). |
| Fill the model pins (B55) | Stale by the owner's decision: model downloads stay unpinned. Not a Play requirement; the item can be closed in PLAY_RELEASE.md. |

**Moved out of D since the triage draft:** "Check 16 KB alignment of every
`.so`". The arm64 ELF result stands (every library in the 818d523 APK has
LOAD `p_align` 0x4000), but it covers neither the x86_64 libraries the ABI
splits and the AAB ship nor the zip alignment of the uncompressed
libraries in APKs generated from the AAB. It is now part of B2.
