// The log callback both engines hand to their *_log_set function, so that
// llama.cpp, whisper.cpp and the one ggml under them all log through it.
// Internal to libmeetily_native: jni_exports.map keeps it local.
#pragma once

#include "ggml.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Forwards warnings and errors to logcat under "meetily_ggml", and from INFO
 * only a few load-time lines (engine_log.c lists them). Pass it to
 * llama_log_set or whisper_log_set before every model load.
 */
void meetily_engine_log(enum ggml_log_level level, const char *text, void *user_data);

#ifdef __cplusplus
}
#endif
