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
- sherpa-onnx runs `provider = "cpu"` (`NemoEngine.kt`, `SherpaEmbedder.kt`).
- The only acceleration in the app today is CPU-side: arm64 NEON, 2–6
  threads, quantized weights, and the q8_0 KV cache + flash attention added
  in v2.9.0-beta3.
- **Correction (2026-09):** this file used to say "NEON/dotprod". The build
  never produced dotprod. `GGML_NATIVE` is off and nothing set
  `GGML_CPU_ARM_ARCH` or `GGML_CPU_ALL_VARIANTS`, so ggml compiled for plain
  `armv8-a`: emulated SDOT for every quantized dot product, F16 maths
  converted through f32, and the dotprod/i8mm repack GEMMs compiled out
  (ggml checks these features at compile time only). There are now two
  builds of the JNI pair. The baseline stays `armv8-a`, because minSdk 26
  still admits Cortex-A53/A73 phones, which would SIGILL on anything newer.
  `:native-v82` builds `armv8.2-a+dotprod+fp16`, and `CpuFeatures.kt` loads
  that pair only when every core lists `asimddp`, `asimdhp` and `fphp` in
  `/proc/cpuinfo`. In ggml's own kernels, i8mm/SVE (armv8.6+) still take
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
  dotprod/i8mm/SME route for Q4_0, Q8_0 and Q6_K weight matmuls (Q4_K
  only on SME2 CPUs), without a third CPU tier. Its SVE kernels need
  256-bit vectors, and phone cores have 128-bit SVE2, so they never
  qualify on a phone. That makes it a smaller fix for the gap in the line
  above than it sounds: on every phone without SME2, the Q4_K bulk of the
  catalogue's Q4_K_M models stays where it was (`CPU_REPACK` on the v82
  build, the plain mmap'd CPU buffer on the baseline), and only their
  Q6_K tensors move.
  - **What it costs (2026-11 review, read from v1.0.0's
    `ggml-cpu/kleidiai/kleidiai.cpp`):** on any phone with dotprod (the
    whole v82 tier, and baseline-tier phones that have it), KleidiAI takes
    every Q6_K matmul weight: the output head and about half the
    `ffn_down`/`attn_v` tensors. It dequantises Q6_K (and Q8_0) and
    requantises it to int8 with one scale per row, which is lossy: the v82
    build ran these through `CPU_REPACK`'s exact Q6_K kernels before. Those
    tensors also grow 8/6.5625 = 1.22x, as anonymous memory in a
    `CPU_KLEIDIAI` buffer: about +270 MB for Llama 3.1 8B (1.5B Q6_K
    parameters), up to about 9% of the file for the catalogue's models
    (Gemma 3 1B, whose tied 262k-token output head is Q6_K). DeviceFit's
    10% weight slack (`llmWorkingSetMb`) has to absorb that; it was not
    raised for it. Q4_0 weights (none in the catalogue) keep per-block
    scales and are not affected this way.
  - whisper.cpp offers its weights to the same buffer types, but the
    shipped Whisper models are F16, q5_0 and q5_1. KleidiAI has no q5
    kernels and runs F16 only on SME2, so on other phones transcription
    stays on ggml's own kernels.
  - `GGML_LLAMAFILE` keeps its default. Not measured: no device numbers,
    and APK growth is unknown until the first CI build. llama.cpp's
    warnings, errors and load-time buffer lines now reach logcat under the
    tag `meetily_ggml` (they went to stderr, which an app discards), so a
    phone shows whether it engaged: `load_tensors: CPU_KLEIDIAI model
    buffer size = ...`, and once per process `kleidiai: primary q8 kernel
    feature DOTPROD` (or `I8MM`). The q4_k line names a kernel only on SME2
    phones; everywhere else it reads `kleidiai: no compatible q4_k kernels
    found for CPU features mask ...`, which is expected, not a failure.
  - **Device check before keeping it on:** on one dotprod phone, summarise
    the same meeting with the same Q4_K_M model (a 7–8B one shows the
    memory cost best) in this build and in one configured with
    `-DGGML_CPU_KLEIDIAI=OFF`. Compare the summaries, the Ask answers, peak
    PSS (`dumpsys meminfo` during generation), first-token latency and
    tokens/s. If quality or memory is worse, set `GGML_CPU_KLEIDIAI` OFF in
    the arm64 branch of `CMakeLists.txt` too, until SME2 phones are
    targeted: without SME2 there is no Q4_K speed-up to trade against it.
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
  merging the two libraries into one per CPU level (E27), is still open.

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
   Q4_K_M models it does nothing on a phone without SME2, so this is still
   open for them. See the baseline notes above, including what KleidiAI
   costs on Q6_K.) Then reduce the work itself: bigger map chunks amortise
   better, and our system prompt is identical on every map call, so
   prompt-prefix KV caching is nearly free. That is likely to beat a GPU
   speedup we cannot ship.
2. **One free experiment on the sherpa side:** try `provider = "xnnpack"`
   instead of `"cpu"`. sherpa-onnx falls back to CPU with a log line if the
   EP is absent, so it cannot break anything. Note that NNAPI is a dead end
   twice over — deprecated by Google in Android 15 and by ONNX Runtime, and
   the prebuilt sherpa-onnx Android libraries are built at `android-21`,
   which compiles the NNAPI EP out entirely. Setting `provider = "nnapi"`
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
