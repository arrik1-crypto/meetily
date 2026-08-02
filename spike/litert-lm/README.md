# LiteRT-LM spike

Throwaway measurement tool. Not part of Recap, not shipped, debug-signed.

## The question

Recap summarises with llama.cpp on CPU. Would an on-device LLM runtime that
can reach the GPU or the Tensor NPU do the same work for meaningfully less
energy?

Speed is not the question. An accelerator that is twice as fast at three times
the power is a regression for a battery-constrained app, and a
tokens-per-second chart would call it a win. So the headline number here is
**microamp-hours per summary**, read from the battery fuel gauge, with
timings as supporting detail.

## What it does

1. Binds to whatever on-device LLM runtime is on the classpath.
2. Loads a `.litertlm` model, discards a warmup generation.
3. Times three generations of a realistic prompt — one map-reduce section of
   meeting transcript, the same unit of work `LocalLlm` feeds the model today,
   asking for the same four-section summary.
4. Reports µAh consumed, median wall time, approximate tokens/sec, thermal
   status before and after, and **which backend the runtime actually
   selected** — which is not always the one requested.

## Running it

The phone must be **unplugged**. The fuel gauge counts up while charging, so a
plugged-in run reports nothing usable.

```
adb push gemma-4-E4B-it.litertlm \
  /sdcard/Android/data/com.recap.spike.litert/files/models/
```

Then open the app, pick a backend, and run. "Copy report" puts the whole
result on the clipboard.

## What the CI discovery runs established

Recorded here because it took three runs to get and should not have to be got
again, whatever is decided.

**Artifact.** `com.google.ai.edge.litertlm:litertlm-android:0.15.0` on Google
Maven — a 19.8 MB AAR, `jni/arm64-v8a` and `jni/x86_64`, containing a single
21 MB `liblitertlm_jni.so`. The first guess, `com.google.mediapipe:tasks-genai`,
resolves but is the older MediaPipe LLM Inference runtime, not this one.

**API.**

```
Engine(EngineConfig) : AutoCloseable
  initialize()
  createSession(SessionConfig) : Session
  createConversation(ConversationConfig) : Conversation

EngineConfig(modelPath, backend, visionBackend, audioBackend,
             maxNumTokens, maxNumImages, cacheDir)

Session
  runPrefill(List<InputData>)
  runDecode() : String
  generateContent(List<InputData>) : String
  generateContentStream(List<InputData>, ResponseCallback)
  cancelProcess()
```

**Backends.** `Backend` is an abstract class, not an enum, so these are nested
classes:

```
Backend.CPU(threadCount, numOfThreads)
Backend.GPU()
Backend.GOOGLE_TENSOR()
Backend.NPU(nativeLibraryDir)
```

`GOOGLE_TENSOR` is a named, first-class backend — the Pixel TPU is addressable
through this runtime. Note it is distinct from the generic `NPU`, which takes
a native library directory and therefore expects vendor libraries the AAR does
not ship; `GOOGLE_TENSOR` takes no arguments, consistent with using the Tensor
stack already on the device.

**Kotlin.** The AAR carries Kotlin 2.2.21 metadata, and a plain Kotlin 2.0.21
build fails on it — not on any LiteRT class, but on `kotlin-stdlib` and
`kotlin-reflect` being pulled in at 2.2.21. That was first read as "adopting
this runtime forces a Kotlin upgrade across the whole app", and quoted as an
adoption cost three times before anyone tested it.

It is wrong. Recap's 2.0.21 compiles against the AAR with a single flag:

```
-Xskip-metadata-version-check
```

Verified by `spike/scripts/kotlin_compat_probe.sh`, which builds both ways:
plain 2.0.21 fails, 2.0.21 + the flag reports BUILD SUCCESSFUL. Worth noting
the first version of that probe piped gradle into `tail` and branched on the
result, so it read `tail`'s exit status and printed BUILT underneath BUILD
FAILED — the verdict was always true and the flagged case stayed unknown for
a run longer than it needed to.

**Gated models.** Every first-party `.litertlm` repo requires authentication:
`google/gemma-3n-*` and all of `litert-community/*`. Only third-party
re-uploads are fetchable unauthenticated, and those are individual accounts
rather than the established quantizers (`unsloth`, `bartowski`) the GGUF
catalogue relies on. See `spike/scripts/discover_litertlm.py`.

## Why the binding is reflection

The published Kotlin guide was not reachable when this was written, so the
exact class and method names are unknown. Compiling against a guess would mean
one build failure per wrong guess and a CI round trip to learn a single
signature.

Reflection collapses that into one run: the app builds regardless, and when
the binding does not resolve it prints the candidate classes that *did* load
and their public methods — which is the information needed to write the real
adapter. The CI workflow also cracks the resolved AAR open and dumps the API,
so the answer arrives even if nothing runs.

None of this belongs in the app. Once the API is known, reflection is exactly
the wrong choice.

## Interpreting the result

Compare µAh per summary against llama.cpp measured the same way. Bear in mind
what it would cost to act on a win:

- llama.cpp currently supplies the shared `ggml` that whisper.cpp builds
  against (`android/app/src/main/cpp/CMakeLists.txt`). Removing it perturbs
  the transcription stack.
- The GGUF catalogue is 13 models; LiteRT-LM's published set is much smaller.
  A transition means carrying both runtimes.
- Summarisation is one pass per meeting. Transcription is 40 minutes of audio.
  Even a large win here is a win on the smaller half of the energy budget.
