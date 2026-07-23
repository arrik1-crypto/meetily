#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "meetily_whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

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
        jstring language, jint n_threads, jboolean translate) {
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

    int ret = whisper_full(ctx, params, pcm, (int) n_samples);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    if (lang != NULL) {
        (*env)->ReleaseStringUTFChars(env, language, lang);
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
