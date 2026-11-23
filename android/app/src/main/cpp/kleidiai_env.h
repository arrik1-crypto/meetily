/*
 * KleidiAI's SME/SME2 kernels are off in both engines unless the build sets
 * MEETILY_KLEIDIAI_SME (CMakeLists.txt says why: on an SME phone they hold
 * a second packed copy of every Q6_K/Q8_0 weight, which DeviceFit does not
 * budget for). Its dotprod and i8mm kernels are unaffected.
 *
 * ggml has no build option for this, only the GGML_KLEIDIAI_SME variable
 * (llama.cpp v1.0.0, ggml-cpu/kleidiai/kleidiai.cpp): 0 drops the
 * SME-family kernels. The ggml both engines share in libmeetily_native reads
 * it once, the first time a model load asks for its buffer types, so every
 * entry point that loads a model calls this first: it does not matter which
 * engine loads first. An existing value is kept (overwrite 0), so a
 * debuggable build can still set it from wrap.sh.
 */
#pragma once

#include <stdlib.h>

static inline void meetily_kleidiai_env(void) {
#ifndef MEETILY_KLEIDIAI_SME
    setenv("GGML_KLEIDIAI_SME", "0", 0);
#endif
}
