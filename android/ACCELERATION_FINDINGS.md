# Hardware acceleration — findings and decision

Backlog items #68 (GPU for Whisper / local LLM) and #69 (Gemini Nano via
AICore). Research done 2026-07-25 against primary sources. **Nothing here
has been measured on hardware**, which is itself the main conclusion: both
paths hinge on numbers we do not have and cannot get from a build server.

Baseline, verified in this repo:

- `app/src/main/cpp/CMakeLists.txt` enables no GPU backend. `ggml` builds
  CPU-only, and whisper.cpp and llama.cpp share that one target. Since
  Batch 3 (2026-11) the pair is llama.cpp v1.0.0 and whisper.cpp b5907,
  which embed the same released ggml, 1.0.0; before that it was b10089
  (ggml 0.17.0) and v1.8.6 (ggml 0.13.1).
- `llama_jni.c` never sets `mparams.n_gpu_layers`.
- sherpa-onnx runs `provider = "cpu"` (`SherpaConfigs.kt`, which builds the
  configs `NemoEngine.kt`, `SherpaEmbedder.kt` and, since Batch 5, the Clear
  voice denoiser use). Since Batch 5 it is v2.0.3, bundling ONNX Runtime
  1.28.2 (v1.13.4 with 1.27.0 before).
- The only acceleration in the app today is CPU-side: arm64 NEON, 2–6
  threads, quantized weights, the q8_0 KV cache + flash attention added
  in v2.9.0-beta3, and, since Batch 3, Arm KleidiAI's runtime-dispatched
  kernels for some weight types (below).
- **Less work rather than faster kernels (2026-11, Batch 3; neither
  measured on a phone):** the Silero speech gate (`SpeechGate.kt`,
  `SileroVad.kt`) drops chunks that pass the loudness check but hold no
  speech before any recogniser sees them, which saves Whisper, Parakeet
  and Nemotron passes over steady noise and typing, at an estimated
  minute or so per hour of audio for the detector itself. And the small
  LLMs (files up to `DeviceFit.SMALL_LLM_MAX_MB`) get an 8K window where
  the phone holds it comfortably, with map sections scaled to the window
  (`PromptShaping.mapChunkChars`), so a long meeting takes about half as
  many condensing passes; each pass's prompt is longer, so the net time
  saved is unmeasured.
- **Correction (2026-09):** this file used to say "NEON/dotprod". The build
  never produced dotprod. `GGML_NATIVE` is off and nothing set
  `GGML_CPU_ARM_ARCH` or `GGML_CPU_ALL_VARIANTS`, so ggml compiled for plain
  `armv8-a`: emulated SDOT for every quantized dot product, F16 maths
  converted through f32, and the dotprod/i8mm repack GEMMs compiled out
  (ggml checks these features at compile time only). There are now two
  builds of the JNI code: until Batch 5 a whisper/llama pair per level,
  since Batch 5 one library per level (below). The baseline stays
  `armv8-a`, because minSdk 26 still admits Cortex-A53/A73 phones, which
  would SIGILL on anything newer. `:native-v82` builds
  `armv8.2-a+dotprod+fp16`, and `NativeEngines.kt` loads that build only
  when `CpuFeatures.kt` finds that every core lists `asimddp`, `asimdhp`
  and `fphp` in `/proc/cpuinfo`, falling back to the baseline if it does
  not load. In ggml's own kernels, i8mm/SVE (armv8.6+) still take
  the dotprod path. Getting those means runtime variants (`GGML_BACKEND_DL`
  + `GGML_CPU_ALL_VARIANTS`), which needs ggml, whisper and llama built as
  shared libraries and the variant MODULE libraries packaged. Not
  attempted without a device to verify on. The speed-up has not been
  measured.
- **KleidiAI (2026-11, Batch 3):** `GGML_CPU_KLEIDIAI` is on for arm64-v8a
  in both builds (ggml-cpu refuses it for x86_64). ggml fetches Arm's
  KleidiAI v1.32.0 at configure time (URL and MD5 pinned in
  `ggml/src/ggml-cpu/CMakeLists.txt` at v1.0.0) and builds it with
  KleidiAI's own CMake, which gives each kernel file its own `-march`
  (+dotprod, +i8mm, +sve, +sme...) and nothing else. ggml's glue keeps
  ggml's flags, reads `getauxval(AT_HWCAP/AT_HWCAP2)` once, and only calls
  a kernel whose features the CPU has, so the armv8-a baseline cannot
  SIGILL because of it; on a Cortex-A53/A73 no kernel qualifies and
  everything stays on ggml's path. It is a runtime-dispatched
  dotprod/i8mm route for Q4_0, Q8_0 and Q6_K weight matmuls, without a
  third CPU tier. Its SME/SME2 kernels (the only ones it has for Q4_K) are
  kept off, for memory (below), and its SVE kernels need 256-bit vectors,
  which phone cores (128-bit SVE2) do not have. That makes it a smaller fix
  for the gap in the line above than it sounds: on every phone, the Q4_K
  bulk of the catalogue's Q4_K_M models stays where it was (`CPU_REPACK`
  on the v82 build, the plain mmap'd CPU buffer on the baseline), and only
  their Q6_K tensors move. The exception is the opt-in Gemma 4 E2B and E4B
  QAT files (Google's own builds), which are Q4_0: on a phone with dotprod
  their bulk is the one catalogue bulk KleidiAI's dotprod/i8mm kernels
  take. `repack()` packs Q4_0 straight from the file's blocks, at about
  their own size and with no temporary copy, into the anonymous
  `CPU_KLEIDIAI` buffer. Not measured on a phone, against the unsloth
  Q4_K_M files or otherwise.
  - **What it costs (2026-11 reviews, read from v1.0.0's
    `ggml-cpu/kleidiai/kleidiai.cpp`, `repack()` and `get_alloc_size()`;
    nothing measured):** on any phone with dotprod (the whole v82 tier, and
    baseline-tier phones that have it), KleidiAI takes every Q6_K matmul
    weight: the output head and about half the `ffn_down`/`attn_v`
    tensors. A tied head counts: llama.cpp loads a second copy of
    `token_embd` for the output matmul, and that copy goes to KleidiAI. It
    dequantises Q6_K (and Q8_0) and requantises it to int8 with one scale
    per row, which is lossy: the v82 build ran these through
    `CPU_REPACK`'s exact Q6_K kernels before. Memory, three ways:
    - *Steady state:* those tensors grow 8/6.5625 = 1.22x, as anonymous
      memory in a `CPU_KLEIDIAI` buffer, while their file pages stop being
      read: about +270 MB for Llama 3.1 8B (1.5B Q6_K parameters), up to
      about 9% of the file across the catalogue (estimated from tensor
      shapes). DeviceFit's 10% weight slack (`llmWorkingSetMb`) has to
      absorb that; it was not raised for it.
    - *Load peak:* each such weight is requantised through a temporary
      int8 buffer of rows x cols bytes, freed before the next, on top of
      the `CPU_KLEIDIAI` buffer written so far. The largest is the output
      head: 128256 x 4096 = 501 MiB for Llama 3.1 8B, 262144 x 2560 =
      640 MiB for Gemma 3 4B and MedGemma. It comes before the KV cache and
      compute buffers are allocated, so it competes with DeviceFit's
      `LLM_RUNTIME_MB` and 10% for room rather than adding to them: well
      inside them for Llama 3.1 8B, about their size for Gemma 3 4B. A
      `dumpsys meminfo` taken only during generation misses it.
    - *SME/SME2 phones (Arm C1-class flagships, 2025 on), if the SME-family
      kernels were on:* every Q6_K/Q8_0 (and Q4_0) weight is packed twice,
      once for the SME kernel and once for the NEON i8mm/dotprod kernel the
      threads past the SME thread cap run. The cap is the SMCU count from
      sysfs, or 1, so any multi-threaded run packs both (setting
      `GGML_TOTAL_THREADS` only drops the second copy at or below the cap).
      That is about 2.44x the Q6_K size, and Q4_K moves out of the
      evictable mmap into the anonymous buffer as well: for Llama 3.1 8B the
      Q6_K copies alone are about +1.8 GB over the file, against 492 MB of
      slack, which on a 12 GB phone is past what DeviceFit lets a model
      take. So the build keeps them off: `kleidiai_env.h` sets
      `GGML_KLEIDIAI_SME=0` before either engine loads a model (ggml has no
      build option for it), and SME phones get the same i8mm/dotprod kernels
      as other phones. Configure with `-DMEETILY_KLEIDIAI_SME=ON` to measure
      them; turn it on for good only with DeviceFit's allowance raised to
      what that shows.
  - whisper.cpp offers its weights to the same buffer types, but the
    shipped Whisper models are F16, q5_0 and q5_1. KleidiAI has no q5
    kernels and runs F16 only on SME2, which is off, so transcription stays
    on ggml's own kernels.
  - `GGML_LLAMAFILE` keeps its default. Not measured: no device numbers,
    and APK growth is unknown until the first CI build. llama.cpp's
    warnings, errors and load-time buffer lines now reach logcat under the
    tag `meetily_ggml` (they went to stderr, which an app discards), so a
    phone shows whether it engaged: `load_tensors: CPU_KLEIDIAI model
    buffer size = ...`, and once per process `kleidiai: primary q8 kernel
    feature DOTPROD` (or `I8MM`) and `kleidiai: SME disabled`. Since
    Batch 5 whisper.cpp installs the same forwarder before every model and
    speech-gate load (they share one ggml, whose log callback is
    process-wide), so whisper.cpp's warnings reach `meetily_ggml` too, and
    the kernel line comes at whichever engine's first load is first: it
    may come with a Whisper or speech-gate load, not the LLM's. The q4_k
    line reads `kleidiai: no compatible q4_k kernels found for CPU features
    mask ...`, which is expected, not a failure.
  - **Device check before keeping it on:** on one dotprod phone, summarise
    the same meeting with the same Q4_K_M model (Llama 3.1 8B shows the
    steady-state cost best, Gemma 3 4B the load peak) in this build and in
    one configured with `-DGGML_CPU_KLEIDIAI=OFF`. Compare the summaries,
    the Ask answers, first-token latency, tokens/s and peak PSS over the
    whole run, model load included (sample `dumpsys meminfo <package>`
    every second or so from tapping Summarise until the reply ends, and
    keep the highest TOTAL PSS; the load peak lasts only seconds). If an
    SME2 phone is available, repeat there with `-DMEETILY_KLEIDIAI_SME=ON`
    as a third build; logcat should then read `kleidiai: SME enabled`. If
    quality or memory is worse, set `GGML_CPU_KLEIDIAI` OFF in the arm64
    branch of `CMakeLists.txt` too: with the SME kernels off there is no
    Q4_K speed-up to trade against it. If the load peak exceeds
    `llmWorkingSetMb`, raise its slack or turn KleidiAI off.
- **Exports (2026-11, 3.16.0-beta1):** both JNI libraries, and their
  `_v82` builds, are now linked with `app/src/main/cpp/jni_exports.map`,
  which exports only `Java_*` (plus `JNI_OnLoad*`/`JNI_OnUnload*`, not
  defined today). Before, in the published 3.15.0-beta1 arm64 APK,
  `libmeetily_llama.so` exported 9,465 symbols and
  `libmeetily_whisper.so` 3,654 (all of ggml, whisper, llama,
  llama-common and the static libc++). That meant 11,849 and 3,515
  relocations against the libraries' own symbols resolved by name at
  every BIND_NOW load (12,083 and 3,721 symbolic relocations in all,
  counting imports), about 1.43 MB and 0.44 MB of dynamic-link metadata
  (`.dynsym`, `.dynstr`, `.gnu.hash`, `.gnu.version` and the relocation
  tables, measured with `readelf` and `scripts/check_jni_exports.py`),
  and nothing `--gc-sections` could drop. CPU flags are unchanged, so the
  SIGILL tiering is unaffected. After-sizes: see the "Check native
  library exports" step of the first CI run, which prints the previous
  published APK beside the new build. Step 2 of the roadmap's export item,
  merging the two libraries into one per CPU level (E27), was done in
  Batch 5 (next bullet), so these two libraries no longer exist; the
  9,465 and 3,654 figures stay as the "before" history.
- **One native library per CPU level (2026-11, Batch 5, E27):**
  `libmeetily_native.so` (plain `armv8-a` on arm64, and x86_64) and
  `libmeetily_native_v82.so` (`armv8.2-a+dotprod+fp16`, arm64 only) replace
  `libmeetily_whisper`/`libmeetily_llama` and their `_v82` builds. Each
  holds whisper.cpp, llama.cpp, llama-common, the Silero speech gate, one
  ggml (with KleidiAI on arm64) and one static libc++, and exports 18
  `Java_` entry points. llama.cpp v1.0.0 and whisper.cpp b5907 carry
  byte-identical ggml (all 22 public headers and the CMake files match),
  and CMake now fails the configure if ggml comes from anywhere else.
  Compile flags per level are unchanged, so the SIGILL tiering is the same.
  `engine_log.c` holds the log forwarder both engines install.
  `NativeEngines.kt` loads the library once per process (warmed off the
  main thread on the recording screen and the Settings switch, since the
  first load now maps llama.cpp too) and logs under `meetily_native`.
  *Size saving (measured in the first 3.19.0-beta1 CI build, "Check native
  library exports" step):* arm64-v8a engine code went from 4 libraries,
  18,052,704 bytes (7,346,325 zipped), to 2 libraries, 14,762,368 bytes
  (6,030,199 zipped). That is -3,290,336 bytes (-18.2%) on disk and
  -1,316,126 bytes (-17.9%) in the APK, about 1.6 MB uncompressed per CPU
  level, more than the 1 MB expected. `libmeetily_native.so` is 7,378,312
  bytes (`_v82`: 7,384,056), against `libmeetily_llama.so` 7,144,736 plus
  `libmeetily_whisper.so` 1,876,496 in 3.18.0-beta1 (`_v82`: 7,150,464
  plus 1,881,008). The x86_64 `libmeetily_native.so` is 8,057,192 bytes.
  The whole arm64 APK went from 27,483,313 to 27,032,463 bytes, even with
  sherpa-onnx v2.0.3 and the bundled GTCRN model added.
- **sherpa-onnx v2.0.3 (2026-11, Batch 5):** from v1.13.4, with ONNX
  Runtime 1.28.2 (was 1.27.0). arm64 native code grows by about 1.2 MB
  uncompressed: `libsherpa-onnx-jni.so` 4,710,728 → 5,386,920 bytes and
  `libonnxruntime.so` 21,688,912 → 22,249,560; x86_64 is 5,913,864 +
  25,581,120. The fetch now checks a pinned SHA-256, and CI runs
  `scripts/check_sherpa_jni.py` after it (every native of the vendored
  Kotlin API exported on both ABIs, 16 KB alignment, NEEDED libraries,
  version stamp). The CPU provider and thread counts are unchanged.
  - *Robustness:* since v2.0.0 the recognizer constructors turn
    onnxruntime errors into Java exceptions, so a corrupt Parakeet or
    Nemotron file fails cleanly ("Could not load the … model" on import).
    The speaker-embedding extractor's constructor still does not (v2.0.3):
    a file onnxruntime rejects aborts the process, and one without the
    metadata its model class reads crashes on first use. So Recap checks a
    speaker model's ONNX structure and metadata first (`OnnxSanity.kt`) and
    skips speaker detection when it fails.
  - *Speech detection, decision: keep the whisper.cpp Silero v6.2 gate
    (`SileroVad`/`SpeechGate`); do not move to sherpa-onnx's own Silero
    VAD.* It was re-evaluated after the bump, since its long-stream
    SIGSEGV (#4079, fixed in 2.0.0) and its Flush sample drop (#4147,
    fixed in 2.0.2) are gone. (a) The current design's only cost to the
    NeMo paths (Parakeet and Nemotron, import and live) is loading the
    whisper.cpp code just for the gate: before the merge,
    `libmeetily_whisper.so`, 1,876,496 bytes on arm64 (1,881,008 for the
    v8.2 build) of file-backed code, plus an 885,098-byte model copied once
    to `noBackupFilesDir`. Summaries load the ggml stack in the same
    process anyway, and since the merge the gate and the engines share one
    library. (b) sherpa-onnx's `Vad` would fit the `SpeechDetector`
    interface (`Vad.compute` returns one probability per window, and all 12
    `Vad` natives are exported in v2.0.3), but it needs `Vad.kt` vendored,
    a new bundled model (the asr-models `silero_vad.onnx`, 643,854 bytes,
    or `silero_vad_v5.onnx`, 2,313,101 bytes; Silero is MIT), recalibrated
    thresholds and a new self-test, and it is a different Silero export
    from the v6.2 the gate was tuned on. (c) The shipped gate has no known
    field failures. Its known limits are by design: music and tones can
    score as speech and are kept, it fails open on any load or self-test
    failure, and it has not been measured on a phone. No concrete benefit
    was found, so nothing changed. Revisit if loading the merged library
    on NeMo-only paths shows up in startup or memory measurements.
- **Clear voice, GTCRN (2026-11, Batch 5):** the bundled `gtcrn_simple.onnx`
  (535,638 bytes, MIT) runs through sherpa-onnx's offline speech denoiser
  on one thread at background priority. On a host CPU it ran at a real-time
  factor of 0.042 with one thread and 0.048 with two or four, so more
  threads do not help and Recap uses one; not measured on a phone (the
  2-hour device check in PLAY_RELEASE.md records that). It works in 30 s
  blocks (`EnhanceBlocks.kt`) so a long meeting is never in memory whole;
  each block also runs a 2 s warm-up, a 0.25 s cross-fade and a 64 ms
  run-out it does not keep, which costs about 7.7% extra work: (2 s +
  0.25 s + 64 ms) / 30 s. On Android 15+ it runs as a mediaProcessing
  foreground service and shares that type's six-hour daily allowance with
  transcription.
- **Batch 6 (2026-11, 3.20.0-beta1): no size added, nothing new to
  accelerate.** No native code, library or bundled model changed.
  Background playback stays on the platform MediaPlayer, with the
  platform MediaSession and Notification.MediaStyle for its controls.
  Media3 was evaluated and not adopted (the newest stable was 1.11.1;
  1.12.0 was only alpha and beta (1.12.0-beta01, 11 November 2026), built
  with compileSdk 37),
  so media3-session and media3-exoplayer, and the Guava and
  androidx.media a Media3 session around MediaPlayer would also need, add
  nothing to the APK; their size was not measured. ExoPlayer was not an
  option for the player itself: its ADTS extractor at 1.11.1 seeks only
  by an average-bitrate estimate, while follow-along, tap-a-word and the
  waveform need MediaPlayer's exact seeks on Recap's raw ADTS recordings.
  MediaPlayer's wake mode holds a partial wake lock only while playing.
  Remove a passage decodes and re-encodes one recording with the
  platform's AAC decoder and encoder (MediaCodec) on one
  background-priority thread, after a short probe that measures the
  encoder's delay, and runs as mediaProcessing on Android 15+, sharing
  the six-hour allowance with transcription and Clear voice. Its time and
  battery cost on a phone have not been measured; the Remove a passage
  device checks in PLAY_RELEASE.md record that on a 2-hour meeting.
- **Startup (2026-11, Batch 7, 3.21.0-beta1): a Baseline Profile and a
  cold-start audit; nothing measured.**
  - *What the profile covers:* `app/src/main/baseline-prof.txt`, about 820
    rules, hand-curated from the code rather than recorded on a device,
    with exact method descriptors taken from the compiled bytecode. Three
    journeys: cold start (RecapApp.onCreate and what it runs, MainActivity
    up to its first frame, the library list's adapter and rows, the light
    listing read and parse, the search box and filter chips, the progress
    banners, the edge-to-edge and accessibility helpers), opening a meeting
    (the off-main-thread read and the first render) and transcript scroll
    (line binding, find and playback highlighting). Branches the common
    path does not take (dialogs, photo OCR, speaker suggestions, summary
    and job starts, player preparation, trash erase) are left out. It
    also covers the Material 1.12.0 views those screens use (Material
    ships no profile, and the 3.20.0-beta1 APK's merged profile had no
    Material rules), the Kotlin standard-library functions the code calls
    (the standard library ships none either) and ConcatAdapter, which
    RecyclerView's own profile leaves out. The Batch 7 integration added 39
    rules for the re-timing and system-sounds code on those journeys.
    BaselineProfileTest keeps every rule matching a class and method that
    exists; AGP 8.2+ has R8 rewrite the rules for the optimised build.
  - *Why the AndroidX libraries' rules are not repeated:* they ship
    profiles in their AARs and AGP merges them into the APK's
    `assets/dexopt/baseline.prof`. The merged profile in the 3.20.0-beta1
    APK held 261 classes and 838 hot methods, almost all of them library
    rules.
  - *profileinstaller 1.3.1 → 1.4.1:* AndroidX brought 1.3.1 in
    transitively, and installing profiles on API 35 and later arrived only
    in 1.4.0, so a sideloaded APK on Android 15 or 16 had its profile
    ignored. 1.4.1 is now declared directly. Play installs compile the
    profile at install either way.
  - *Main-thread findings, fixed:* MainActivity parsed a whole recovered
    meeting on the main thread in onCreate just to decide on the
    "recovered" toast; that now runs on its own thread. The "Resume
    interrupted work?" lookup parsed the interrupted meeting on the main
    thread on every return to the home screen while the prompt stayed
    unanswered; it now runs on one worker thread, and the prompt opens only
    if it still applies when the answer is back. Opening an audio-only
    meeting asked every model whether it was ready, which loaded
    `libmeetily_native` and sherpa-onnx with onnxruntime on the main thread
    even with no model on the phone; the files are now checked before the
    runtime (ModelReadyOrderTest), with the same answer.
  - *Left as is:* RecapApp.onCreate was already lean (the engine-default
    migration, night mode, AppLock.install, LocalLlm.init and one prefs
    read, in that order on purpose; Batch 7's SystemSoundsGuard.install
    only registers a callback and starts an asynchronous prefs load), the
    small job-queue and crash prefs reads in onResume, and ML Kit's
    `MlKitInitProvider` at process start (deferring it to the first OCR
    use needs a device to verify and changes when ML Kit initialises).
  - *Unmeasured: everything.* No device, no Macrobenchmark run, no
    before/after start-up time. No startup profile either: DEX layout
    optimisation would also need an obfuscated R8 release, and Recap ships
    `-dontobfuscate` so crash reports stay readable. The cold-start,
    meeting-open and scroll checks, and the steps to generate a measured
    profile, are in PLAY_RELEASE.md (device checks and Product decisions).
- **Re-time words, forced alignment (2026-11, Batch 7, 3.21.0-beta1): CPU
  only, nothing to accelerate.** sherpa-onnx's forced aligner (v2.0.3,
  already inside the shipped `libsherpa-onnx-jni.so`, so no size added) is
  a kaldi-style GMM aligner: feature extraction with an LDA transform, a GMM
  acoustic model (`final.alimdl`) and a lexicon graph, decoded on the CPU.
  It does not use onnxruntime, and it has no GPU or NPU path. Each run
  works on one background-priority thread.
  - *Memory, estimated:* about 200 MB of native memory while the model is
    loaded: `final.alimdl` (50,223,203 bytes) held as stored, and the
    5,530,360-byte `lexicon.txt` parsed into one string vector per
    pronunciation (about 204,000 lines, roughly 120 MB); the per-line
    decoding graphs and features are small. Read off the file sizes and
    sherpa-onnx's data structures, not measured. DeviceFit costs it at
    200 MB (`AlignerModels.WORKING_SET_MB`), which passes on any phone.
  - *Time, not measured on a phone:* the model loads once per run, then
    each line's clip (the line plus 300 ms either side; lines over 3
    minutes are skipped) is decoded with the platform decoder, one
    MediaExtractor and MediaCodec for the whole run, seeking per pass,
    with lines within 30 s of each other sharing a pass, and aligned. It
    runs as mediaProcessing on Android 15 and later, sharing the six-hour
    allowance with transcription, Clear voice and Remove a passage. The
    device checks in PLAY_RELEASE.md ask for the time and peak memory of a
    run over an hour-long meeting.
  - *Unpacking the download:* the model is published only as a
    `.tar.bz2`, and neither Android nor the app's libraries read bzip2, so
    Recap decompresses it in pure Kotlin (`Bzip2InputStream`, checked
    byte for byte against `bzip2 -dc` on the real archive). About 5 s for
    the archive's 56 MB on a desktop JVM; slower on a phone, not measured.
    It happens once per download.

---

## #69 — Gemini Nano / AICore

**Decision: prototype behind a build flag; do not ship it as an engine this
cycle.**

The supported API is the **ML Kit GenAI** family (`com.google.mlkit.genai:*`,
API 26+), sitting on AICore. The older `com.google.ai.edge.aicore` SDK from
the 2024 experimental programme is deprecated — do not build against it.
Everything in the family is still `beta` or `alpha`; there is no GA surface.

What makes it attractive for this app is real: no model download at all
(against our 0.5–5.7 GB GGUFs), inference on the neural accelerator, and
documented on-device processing with no server fallback.

What rules it out for now, in order of severity:

1. **Foreground-only.** Google documents that inference is permitted only
   while the app is the top foreground application, and that using it from a
   **foreground service** returns `BACKGROUND_USE_BLOCKED`. Every heavy thing
   this app does — summarising after a meeting, importing, the accuracy
   check — is a foreground-service job by design. That is not a detail we can
   work around; it is the opposite of our architecture.
2. **Undocumented battery quota.** `PER_APP_BATTERY_USE_QUOTA_EXCEEDED` is a
   documented error with no published budget. Against hour-long transcripts
   that is an unbounded risk we cannot design around or test exhaustively.
3. **Capability mismatch.** The task-specific Summarization API emits 1–3
   bullet points, wants input segmented into ~4000-token groups, and is
   documented for English, Japanese and Korean only. A meeting summary needs
   structure, attribution and action items. We would have to use the general
   Prompt API and rebuild the map-reduce orchestration we already have — for
   a model whose size, version and availability we do not control.
4. **Positioning.** ML Kit contacts Google for model updates and sends usage
   metrics. Meeting *content* stays on the device, which is the promise that
   matters, but "nothing leaves your phone" is harder to defend flatly than
   it is with llama.cpp, where we own the whole stack. (Update, 2026-11:
   the app no longer makes that flat claim anyway. The ML Kit text
   recognition it already bundles for photo OCR sends Google device, app
   and performance diagnostics, which the privacy policy and the Data
   safety answers in PLAY_RELEASE.md §4 now disclose. Adopting the GenAI
   APIs would add to that, not create it.)

Terms worth noting before any adoption: the ML Kit GenAI additional terms bar
use in services likely to be accessed by under-18s, and bar developing
competing models or replicating the service. We ship our own local LLM engine
in the same app, which is not the same thing, but is close enough to the line
to be worth a read before shipping. No cost or revenue share was found for a
paid app.

**Correction to an earlier assumption of ours.** We had recorded that the
app's "system" transcription engine (Android `SpeechRecognizer` with
`preferOfflineRecognition`) is Tensor-accelerated on Pixel. That is not
supportable. Google's Tensor/TPU ASR statements are about its **own** apps
and models (Recorder, Live Caption, Assistant); nothing documents that the
recognizer exposed to third-party apps takes that path, and the backing
`RecognitionService` is OEM-replaceable. We have never made this claim in
shipped copy — the README and settings text say "on-device", not
"accelerated" — so nothing needs correcting in the app. Do not add it.

Also relevant: the ML Kit **GenAI Speech Recognition** API requires audio to
be fed at a real-time rate; file-backed descriptors that read at full speed
are explicitly unsupported. That rules it out for faster-than-realtime batch
transcription, which is exactly what imports and the accuracy check do.

**If revisited:** Gemini Nano 4 / Gemma 4 land on flagships later in 2026 with
claimed 4× speed and 60% less battery, and the Prompt API is the designated
production path. Re-evaluate when that ships broadly **and** the foreground
restriction has either lifted or been confirmed permanent. If it is
permanent, this API can still serve a user-initiated "summarise this now" on
a visible screen — but never the background work that dominates this app.

---

## #68 — GPU acceleration for Whisper / the local LLM

**Decision: do not ship a GPU backend. Spend the effort on CPU prefill
instead.** If anyone wants to test the premise, run a correctness check, not
a benchmark — see the end of this section.

### The premise was wrong about the hardware

The target device is a Pixel 10 Pro XL, and its Tensor G5 does **not** have a
Mali/Immortalis GPU. Google dropped Arm here; it is an Imagination PowerVR
D-Series `DXT-48-1536`. That single fact undoes most of what is written about
llama.cpp on Android, because every published result — good or bad — is
Adreno or Mali.

### Why not

1. **The GPU is currently wrong, not slow, for the quantisation we ship.**
   Imagination's own developer forum carries a report that on
   `DXT-48-1536`, every k-quant matmul shader from Q2_K through Q6_K
   produces numerically incorrect results. Non-k-quants (Q4_0, Q5_0, Q8_0,
   F16) are reported fine. Separately, PyTorch ExecuTorch's Vulkan backend
   returns all-zero outputs on the same GPU. Two engines, two silent
   wrong-answer bugs, one part.
2. **Silent corruption is the worst possible failure for this app.** A crash
   we can catch and fall back from. Subtly wrong matmuls in a summarizer
   produce plausible, confidently wrong meeting summaries. Nobody files that
   as a bug; they just stop trusting the app. A toggle does not help, because
   there is nothing to detect at runtime.
3. **We could not fix it, and neither could Google.** Imagination keeps
   proprietary control of the DXT drivers. The Pixel 10 waited from launch
   until Android 16 QPR3 for its first driver bump.
4. **ggml has no PowerVR path at all.** `ggml-vulkan.cpp` defines vendor IDs
   for AMD, Apple, Intel, NVIDIA and Qualcomm only; its architecture enum has
   no Imagination entry, so PowerVR falls to `OTHER` — no shader-variant
   tuning, no driver workarounds. (Mali is `OTHER` too.)
5. **Upstream does not ship this configuration.** llama.cpp's own Android
   release artifact is CPU-only; so is whisper.cpp's Android example. The
   "how do I build llama.cpp with Vulkan for Android" issue has been open and
   unanswered since Feb 2025. We would own the integration alone.
6. **It costs about +12.5 MB of APK** (roughly +50% on our ~27 MB), paid once
   across both engines since they share ggml, and shipped to every device
   including the overwhelming majority that are not Pixel 10.

The OpenCL backend is confirmed Adreno/Qualcomm-only by its own
documentation, so it is not an alternative here.

### What the upside would have been, honestly

Prefill is genuinely the workload a mobile GPU wins, and prefill is exactly
what our map-reduce summarizer is bound by. The best-documented Android case
(Adreno 830 via OpenCL) shows ~23× on prefill for a 1.5B model and ~3.7× for
7B — while **token generation got slower** on the 7B, because per-token
sync overhead beats the gain once you are memory-bandwidth-bound. So even in
the good case the design would be GPU-for-prefill, CPU-for-decode, never a
blanket offload.

On power: the literature says GPU offload is probably more energy-efficient
per token for prefill-shaped work and a wash for decode, but sustained-load
studies find phones throttling ~44% within a couple of iterations regardless
of backend. Power efficiency is not a strong argument in either direction for
a multi-minute job on a phone.

### Two things worth doing instead

1. **Attack prefill on the CPU path.** Confirm we are actually getting the
   Armv9 i8mm/SVE kernels (`GGML_CPU_ALL_VARIANTS`, correct `-march`) — a
   missed path costs multiples on prefill GEMMs for free, with no correctness
   risk. (Batch 3 enabled KleidiAI instead, which reaches i8mm only for
   Q4_0, Q8_0 and Q6_K weights; for the Q4_K bulk of the catalogue's
   Q4_K_M models it does nothing, since its only Q4_K kernel is an SME2 one
   and those are kept off, so this is still open for them. See the baseline
   notes above, including what KleidiAI costs on Q6_K.) Then reduce the
   work itself: bigger map chunks amortise better (Batch 3 doubled them for
   the small models that get an 8K window), and our system prompt is
   identical on every map call, so prompt-prefix KV caching is nearly free.
   (`llama_jni.c` reuses the longest prefix a prompt shares with the
   previous one, except on hybrid models such as Qwen 3.5, whose cache
   refuses a partial drop, and on a Gemma model given the small
   sliding-window cache once the reused part has slid out of it; both then
   re-read the whole prompt.) That is likely to beat a GPU speedup we
   cannot ship.
2. **One free experiment on the sherpa side:** try `provider = "xnnpack"`
   instead of `"cpu"`. sherpa-onnx falls back to CPU with a log line if the
   EP is absent, so it cannot break anything. Note that NNAPI is a dead end
   twice over — deprecated by Google in Android 15 and by ONNX Runtime, and
   the prebuilt sherpa-onnx Android libraries were built at `android-21`,
   which compiles the NNAPI EP out entirely (checked for v1.13.4; not
   re-checked for v2.0.3). Setting `provider = "nnapi"`
   today silently gives you CPU. There is no QNN provider in sherpa-onnx at
   all, and it would be Qualcomm-only regardless.

### If someone wants to test this anyway

Timebox it to a day and make it a **correctness** test. Build with
`-DGGML_VULKAN=ON` (CI-wise this is easy: `glslc`, `libvulkan-dev` and
`spirv-headers` are apt packages, the shader generator is a host tool, and
ggml's CMake supports cross-compiling it) and run `test-backend-ops` on the
device. Do not benchmark first. If `MUL_MAT` fails for our quant type — which
the Imagination report says it will — that is the answer in one run, plus a
reproducible artifact worth attaching to their thread.

Revisit only if Imagination ships a driver fix **and** ggml gains a PowerVR
vendor path. Six months is a reasonable interval; more often is wasted
effort.


---

## LiteRT-LM — what was established, and why it was removed

Backlog item #103, 2026-08-03. Shipped as an opt-in second on-device
summarisation runtime in v3.12.0–v3.12.2, **off by default**; **removed
entirely in v3.12.6-beta1**. It never produced a summary on the target
device. Work stopped by request before the energy question — the one it
existed to answer — was measured.

Recorded because most of this cost a CI round trip or a device test, and
none of it should have to be found twice. The spike harness and its
scripts, referenced throughout below, were removed with the runtime and are
recoverable from git history — `git log --all --diff-filter=D -- spike/`
finds the deletion, and the commit before it has the working tree.

### Settled facts

- **The runtime loads and runs on a Pixel 10.** The spike reached native
  code: `LiteRtLmJniException: Failed to create engine: INVALID_ARGUMENT:
  Unsupported or unknown file format`. That exception comes from inside
  `liblitertlm_jni.so`, so the library loaded, executed, opened the model
  and read it. Whatever is wrong is above the library, not the hardware.
- **Page alignment is NOT a problem.** All three `PT_LOAD` segments of
  `liblitertlm_jni.so` are `0x4000` aligned, so it is fine on a 16 KB page
  kernel. Checked directly from the shipped APK by
  `spike/scripts/check_so_alignment.py`. This was a theory of ours; it was
  wrong.
- **Adoption does NOT force a Kotlin upgrade.** The AAR carries Kotlin
  2.2.21 metadata and a plain 2.0.21 build fails on `kotlin-stdlib`, but
  `-Xskip-metadata-version-check` is sufficient. Both directions verified by
  `spike/scripts/kotlin_compat_probe.sh`. An earlier note claiming an
  app-wide upgrade was required was wrong and is retracted.
- **Every first-party `.litertlm` publisher is licence-gated.** All of
  `google/gemma-3n-*` and `litert-community/*` return 401 unauthenticated.
  The ungated alternatives are individual re-uploaders, not the established
  quantizers the GGUF catalogue relies on, and one ships safety tuning
  removed. This is why the feature imports a file rather than offering a
  download button, and it is not a limitation any code change removes.
  See `spike/scripts/discover_litertlm.py`.
- **The API, pinned exactly** (from `javap` on the resolved AAR, and
  confirmed on device):
  `EngineConfig(String modelPath, Backend backend, Backend visionBackend,
  Backend audioBackend, Integer maxNumTokens, Integer maxNumImages, String
  cacheDir)`, then `Engine(EngineConfig)` and `initialize()`.
  `Backend.CPU(threads, threads)`, `Backend.GPU()`,
  `Backend.GOOGLE_TENSOR()`, `Backend.NPU(nativeLibraryDir)`.
- **`Backend.GOOGLE_TENSOR` is a first-class backend**, distinct from the
  generic `NPU`, and takes no vendor library directory. The Tensor unit is
  addressable through this runtime. Nothing here says it is *cheaper*.

### The two open failures

1. **In Recap:** the `:litert` sandbox process never starts.
   `bindService` returns true, no `onServiceConnected`, no `onBindingDied`,
   no `onNullBinding`, and `LiteRtTrace` reports "the engine process never
   started — nothing ran in it at all" — meaning not even
   `Application.onCreate` ran in it. Two real bugs were fixed on the way
   here (per-process app init, and a Service field initializer that
   resolved LiteRT classes during construction); neither was the cause.
2. **In the spike:** the runtime rejects the model file itself. Not yet
   distinguished between a bad download — a licence page or Git LFS
   pointer saved under the model's name is the leading theory, given every
   publisher is gated — and a container-version mismatch with
   `litertlm-android:0.15.0`. The spike prints stat size and header bytes
   for exactly this, but that build was never run.

### Why it was removed

It cost about **+10 MB of compressed APK** — `liblitertlm_jni.so` is
20.2 MB raw, 8.8 MB in the APK — shipped to every device including the
overwhelming majority that would never turn it on. That is the same
objection this document already raised against the Vulkan backend at
+12.5 MB, and it applied here with more force, because that runtime at
least would have worked.

Removing it also reclaimed things the APK size does not show:

- The `filesDir/litert-models/` directory, holding a 2–4 GB imported
  `.litertlm` file on any device that used the feature. `RecapApp` deletes
  it on first launch after the upgrade — without that, taking away the
  screen would have stranded more disk than the whole app uses, with no way
  to free it.
- `-Xskip-metadata-version-check` on the Kotlin compiler, which suppressed
  metadata mismatches app-wide rather than only for the AAR that needed it.
- `buildFeatures.aidl`, and the `:litert` sandbox process.
- A three-valued `EngineRouting.Target`. With one on-device runtime again,
  "who answers" and "does it stay on the phone" are one question, so it
  collapsed back to a single tested predicate.

**If revisited:** start with failure 2, not failure 1. Whether the runtime
can read a model at all is upstream of whether Recap's sandbox starts, and
it was answerable in one device run with the spike as it stood.
