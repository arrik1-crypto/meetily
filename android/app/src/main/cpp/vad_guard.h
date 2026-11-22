// C interface of vad_guard.cpp, the C++ side of libmeetily_whisper's speech
// gate, for whisper_jni.c. Internal to the library: jni_exports.map keeps it
// local.
#pragma once

#include <stdbool.h>

#include "whisper.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * whisper_vad_init_from_file_with_params with [n_threads] CPU threads and no
 * GPU, or NULL on any failure, including a C++ exception thrown inside
 * whisper.cpp. Free the result with whisper_vad_free.
 */
struct whisper_vad_context *meetily_vad_init(const char *path, int n_threads);

/*
 * whisper_vad_detect_speech ([reset] true: fresh LSTM state) or
 * whisper_vad_detect_speech_no_reset (false: carries on from the previous
 * call). False on failure, including a C++ exception; the probabilities are
 * then not to be read.
 */
bool meetily_vad_detect(struct whisper_vad_context *vctx, const float *samples,
                        int n_samples, bool reset);

#ifdef __cplusplus
}
#endif
