// The speech gate's calls into whisper.cpp's whisper_vad_* API, kept in C++
// so that nothing whisper.cpp throws reaches the C JNI functions in
// whisper_jni.c.
//
// At b5907 these are C functions written in C++, and some of them throw:
// whisper_vad_init_with_params throws std::runtime_error when it cannot
// create a ggml context or finds no buffer type for a tensor, and its new
// and std::vector, like the probability and window vectors of
// whisper_vad_detect_speech_no_reset, throw std::bad_alloc when memory runs
// out. An exception that leaves here would unwind into a JNI frame and end
// the process; caught, it is a failure like any other, and the gate stops
// and lets every chunk through.

#include "vad_guard.h"

#include <android/log.h>

#include <exception>

#define TAG "meetily_whisper"

extern "C" struct whisper_vad_context *meetily_vad_init(const char *path, int n_threads) {
    try {
        struct whisper_vad_context_params params = whisper_vad_default_context_params();
        params.n_threads = n_threads;
        params.use_gpu = false;
        return whisper_vad_init_from_file_with_params(path, params);
    } catch (const std::exception &e) {
        // whisper.cpp's messages here name a tensor or a failed allocation.
        __android_log_print(ANDROID_LOG_ERROR, TAG, "VAD init threw: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "VAD init threw");
    }
    return nullptr;
}

extern "C" bool meetily_vad_detect(struct whisper_vad_context *vctx, const float *samples,
                                   int n_samples, bool reset) {
    try {
        return reset ? whisper_vad_detect_speech(vctx, samples, n_samples)
                     : whisper_vad_detect_speech_no_reset(vctx, samples, n_samples);
    } catch (const std::exception &e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "VAD detection threw: %s", e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "VAD detection threw");
    }
    return false;
}
