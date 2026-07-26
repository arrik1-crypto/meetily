#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "meetily_whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * The encoder is ~2.3 TFLOP per pass on large-v3-turbo and dominates the
 * cost of a call. Two settings were making it run two or three times per
 * whisper_full: language="auto" (which encodes once purely to read a
 * language logit, then again for the real work) and no_timestamps=false
 * (which lets the loop advance by a decoded timestamp and re-encode a
 * second window over the trailing silence every chunk carries).
 *
 * Both are addressed below. This counter exists so the result can be
 * measured instead of assumed: it should read 1 per call.
 */
static int g_encoder_passes = 0;

static bool count_encoder_pass(struct whisper_context *ctx,
                               struct whisper_state *state,
                               void *user_data) {
    (void) ctx;
    (void) state;
    (void) user_data;
    g_encoder_passes++;
    return true; /* never abort */
}

JNIEXPORT jlong JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_initContext(
        JNIEnv *env, jobject thiz, jstring model_path) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) return 0;
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (ctx == NULL) {
        LOGE("failed to load model");
        return 0;
    }
    LOGI("model loaded");
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT void JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_freeContext(
        JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env;
    (void) thiz;
    if (ptr != 0) {
        whisper_free((struct whisper_context *) (intptr_t) ptr);
    }
}

JNIEXPORT jstring JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_transcribe(
        JNIEnv *env, jobject thiz, jlong ptr, jfloatArray samples,
        jstring language, jint n_threads, jboolean translate,
        jstring prompt) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (ctx == NULL || samples == NULL) return NULL;

    jsize n_samples = (*env)->GetArrayLength(env, samples);
    if (n_samples <= 0) return NULL;
    jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (pcm == NULL) return NULL;

    struct whisper_full_params params =
            whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads > 0 ? n_threads : 4;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.translate = translate ? true : false;
    params.suppress_blank = true;

    const char *lang = NULL;
    if (language != NULL) {
        lang = (*env)->GetStringUTFChars(env, language, NULL);
        if (lang != NULL && lang[0] != '\0') {
            params.language = lang;
        }
    }
    /* Custom vocabulary: condition the decoder on a glossary of the user's
     * names/jargon so ambiguous audio resolves toward them. */
    const char *prompt_chars = NULL;
    if (prompt != NULL) {
        prompt_chars = (*env)->GetStringUTFChars(env, prompt, NULL);
        if (prompt_chars != NULL && prompt_chars[0] != '\0') {
            params.initial_prompt = prompt_chars;
        }
    }

    int ret = whisper_full(ctx, params, pcm, (int) n_samples);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    if (lang != NULL) {
        (*env)->ReleaseStringUTFChars(env, language, lang);
    }
    if (prompt_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, prompt, prompt_chars);
    }
    if (ret != 0) {
        LOGE("whisper_full failed: %d", ret);
        return NULL;
    }

    int n_segments = whisper_full_n_segments(ctx);
    size_t cap = 4096;
    size_t len = 0;
    char *buf = (char *) malloc(cap);
    if (buf == NULL) return NULL;
    buf[0] = '\0';
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        if (text == NULL) continue;
        size_t tlen = strlen(text);
        if (len + tlen + 2 > cap) {
            cap = (len + tlen + 2) * 2;
            char *grown = (char *) realloc(buf, cap);
            if (grown == NULL) {
                free(buf);
                return NULL;
            }
            buf = grown;
        }
        memcpy(buf + len, text, tlen);
        len += tlen;
        buf[len] = '\0';
    }

    jstring out = (*env)->NewStringUTF(env, buf);
    free(buf);
    return out;
}

/*
 * Like transcribe(), but with whisper token timestamps enabled, returning
 * word-level timings. Wire format (control chars can't appear in speech
 * text): for each word, 0x1e + start-ms (decimal) + 0x1f + word text.
 */
/*
 * The language whisper settled on for the most recent call, or NULL.
 *
 * Passing "auto" costs a COMPLETE extra encoder pass on every call: whisper
 * encodes the window once just to read a language logit, then encodes it
 * again to do the work. Detecting once and then passing the answer for the
 * rest of the file removes that pass from every subsequent call — and is
 * more accurate too, since auto-detect otherwise runs afresh on each batch
 * of spliced, silence-stripped audio where a single misfire would poison
 * the whole batch with no way for the caller to notice.
 */
JNIEXPORT jstring JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_lastLanguage(
        JNIEnv *env, jobject thiz, jlong ptr) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (ctx == NULL) return NULL;
    int id = whisper_full_lang_id(ctx);
    if (id < 0) return NULL;
    const char *str = whisper_lang_str(id);
    if (str == NULL || str[0] == '\0') return NULL;
    return (*env)->NewStringUTF(env, str);
}

JNIEXPORT jstring JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_transcribeWords(
        JNIEnv *env, jobject thiz, jlong ptr, jfloatArray samples,
        jstring language, jint n_threads, jboolean translate,
        jstring prompt) {
    (void) thiz;
    struct whisper_context *ctx = (struct whisper_context *) (intptr_t) ptr;
    if (ctx == NULL || samples == NULL) return NULL;

    jsize n_samples = (*env)->GetArrayLength(env, samples);
    if (n_samples <= 0) return NULL;
    jfloat *pcm = (*env)->GetFloatArrayElements(env, samples, NULL);
    if (pcm == NULL) return NULL;

    struct whisper_full_params params =
            whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads > 0 ? n_threads : 4;
    params.no_timestamps = false;
    params.token_timestamps = true;
    /*
     * Exactly one 30-second window per call. Without this, seek_delta comes
     * from the decoder's last timestamp token and the loop only stops once
     * it is within 100 ms of the end — but every chunk the importer cuts
     * carries at least 0.8 s of trailing silence, eight times that margin,
     * so the loop re-encoded a whole window over near-silence.
     *
     * Whisper's own segmentation is discarded anyway: the token walk below
     * flattens every segment into one word stream and BatchSplit re-splits
     * it by time. So there is nothing here for single_segment to lose.
     */
    params.single_segment = true;
    params.encoder_begin_callback = count_encoder_pass;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.translate = translate ? true : false;
    params.suppress_blank = true;

    const char *lang = NULL;
    if (language != NULL) {
        lang = (*env)->GetStringUTFChars(env, language, NULL);
        if (lang != NULL && lang[0] != '\0') {
            params.language = lang;
        }
    }
    const char *prompt_chars = NULL;
    if (prompt != NULL) {
        prompt_chars = (*env)->GetStringUTFChars(env, prompt, NULL);
        if (prompt_chars != NULL && prompt_chars[0] != '\0') {
            params.initial_prompt = prompt_chars;
        }
    }

    g_encoder_passes = 0;
    int ret = whisper_full(ctx, params, pcm, (int) n_samples);
    LOGI("whisper_full: %d encoder pass(es) for %d samples",
         g_encoder_passes, (int) n_samples);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    if (lang != NULL) {
        (*env)->ReleaseStringUTFChars(env, language, lang);
    }
    if (prompt_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, prompt, prompt_chars);
    }
    if (ret != 0) {
        LOGE("whisper_full failed: %d", ret);
        return NULL;
    }

    size_t cap = 8192;
    size_t len = 0;
    char *buf = (char *) malloc(cap);
    if (buf == NULL) return NULL;
    buf[0] = '\0';
    int word_open = 0;

    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        int n_tokens = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < n_tokens; j++) {
            whisper_token id = whisper_full_get_token_id(ctx, i, j);
            if (id >= whisper_token_eot(ctx)) continue; /* special token */
            const char *text = whisper_full_get_token_text(ctx, i, j);
            if (text == NULL || text[0] == '\0') continue;
            whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
            long long ms = (long long) td.t0 * 10;

            const char *emit = text;
            int starts_word = (!word_open || text[0] == ' ');
            char header[40];
            size_t hlen = 0;
            if (starts_word) {
                hlen = (size_t) snprintf(header, sizeof(header),
                                         "\x1e%lld\x1f", ms);
                while (*emit == ' ') emit++;
                if (*emit == '\0') continue; /* pure-space token */
                word_open = 1;
            }
            size_t tlen = strlen(emit);
            if (len + hlen + tlen + 1 > cap) {
                cap = (len + hlen + tlen + 1) * 2;
                char *grown = (char *) realloc(buf, cap);
                if (grown == NULL) {
                    free(buf);
                    return NULL;
                }
                buf = grown;
            }
            if (hlen > 0) {
                memcpy(buf + len, header, hlen);
                len += hlen;
            }
            memcpy(buf + len, emit, tlen);
            len += tlen;
            buf[len] = '\0';
        }
        word_open = 0; /* segment boundary always starts a new word */
    }

    jstring out = (*env)->NewStringUTF(env, buf);
    free(buf);
    return out;
}
