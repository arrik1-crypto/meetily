# Hardware acceleration — findings and decision

Backlog items #68 (GPU for Whisper / local LLM) and #69 (Gemini Nano via
AICore). Research done 2026-07-25 against primary sources. **Nothing here
has been measured on hardware**, which is itself the main conclusion: both
paths hinge on numbers we do not have and cannot get from a build server.

Baseline, verified in this repo:

- `app/src/main/cpp/CMakeLists.txt` enables no GPU backend. `ggml` builds
  CPU-only, and whisper.cpp v1.8.6 and llama.cpp b10089 share that one
  target.
- `llama_jni.c` never sets `mparams.n_gpu_layers`.
- sherpa-onnx runs `provider = "cpu"` (`NemoEngine.kt`, `SherpaEmbedder.kt`).
- The only acceleration in the app today is CPU-side: arm64 NEON/dotprod,
  2–6 threads, quantized weights, and the q8_0 KV cache + flash attention
  added in v2.9.0-beta3.

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
   it is with llama.cpp, where we own the whole stack.

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

_(findings below)_
