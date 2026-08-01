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
