#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "llama.h"

#define TAG "meetily_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/*
 * On-device chat completion for the local AI engine. One loaded model at a
 * time (the Kotlin side serializes calls); each generate() is a fresh
 * conversation: apply the model's chat template, clear the KV cache, decode
 * the prompt in n_batch chunks, then sample until EOG or the token budget.
 */

typedef struct {
    struct llama_model *model;
    struct llama_context *ctx;
    struct llama_sampler *smpl;
} local_llm;

JNIEXPORT jlong JNICALL
Java_com_meetily_mobile_llm_LlamaBridge_initModel(
        JNIEnv *env, jobject thiz, jstring model_path, jint n_ctx, jint n_threads) {
    (void) thiz;
    const char *path = (*env)->GetStringUTFChars(env, model_path, NULL);
    if (path == NULL) return 0;

    llama_backend_init();

    struct llama_model_params mparams = llama_model_default_params();
    struct llama_model *model = llama_model_load_from_file(path, mparams);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    if (model == NULL) {
        LOGE("failed to load model");
        return 0;
    }

    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) (n_ctx > 0 ? n_ctx : 4096);
    cparams.n_batch = 512;
    cparams.n_threads = n_threads > 0 ? n_threads : 4;
    cparams.n_threads_batch = n_threads > 0 ? n_threads : 4;
    struct llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == NULL) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    struct llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(0xC0FFEE));

    local_llm *llm = (local_llm *) malloc(sizeof(local_llm));
    if (llm == NULL) {
        llama_sampler_free(smpl);
        llama_free(ctx);
        llama_model_free(model);
        return 0;
    }
    llm->model = model;
    llm->ctx = ctx;
    llm->smpl = smpl;
    LOGI("local model loaded (n_ctx=%u)", cparams.n_ctx);
    return (jlong) (intptr_t) llm;
}

JNIEXPORT void JNICALL
Java_com_meetily_mobile_llm_LlamaBridge_freeModel(
        JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env;
    (void) thiz;
    if (ptr == 0) return;
    local_llm *llm = (local_llm *) (intptr_t) ptr;
    llama_sampler_free(llm->smpl);
    llama_free(llm->ctx);
    llama_model_free(llm->model);
    free(llm);
}

/*
 * packed messages: per message, 0x1e + role + 0x1f + content. Parsed into
 * llama_chat_message entries pointing into a NUL-punched copy.
 */
JNIEXPORT jstring JNICALL
Java_com_meetily_mobile_llm_LlamaBridge_generate(
        JNIEnv *env, jobject thiz, jlong ptr, jstring packed, jint max_tokens) {
    (void) thiz;
    if (ptr == 0 || packed == NULL) return NULL;
    local_llm *llm = (local_llm *) (intptr_t) ptr;

    const char *packed_c = (*env)->GetStringUTFChars(env, packed, NULL);
    if (packed_c == NULL) return NULL;
    char *work = strdup(packed_c);
    (*env)->ReleaseStringUTFChars(env, packed, packed_c);
    if (work == NULL) return NULL;

    /* Parse the packed messages. */
    struct llama_chat_message msgs[32];
    size_t n_msgs = 0;
    char *cursor = work;
    while (n_msgs < 32) {
        char *rs = strchr(cursor, '\x1e');
        if (rs == NULL) break;
        char *role = rs + 1;
        char *us = strchr(role, '\x1f');
        if (us == NULL) break;
        *us = '\0';
        char *content = us + 1;
        char *next = strchr(content, '\x1e');
        if (next != NULL) *next = '\0';
        msgs[n_msgs].role = role;
        msgs[n_msgs].content = content;
        n_msgs++;
        if (next == NULL) break;
        *next = '\x1e'; /* restore for the next strchr scan */
        cursor = next;
    }
    if (n_msgs == 0) {
        free(work);
        return NULL;
    }
    /* Re-punch separators consumed by the scan (contents end at 0x1e). */
    for (size_t i = 0; i < n_msgs; i++) {
        char *tail = strchr(msgs[i].content, '\x1e');
        if (tail != NULL) *tail = '\0';
    }

    /* Chat template: prefer the model's own, fall back to ChatML. */
    const char *tmpl = llama_model_chat_template(llm->model, NULL);
    int32_t buf_len = 8192;
    char *prompt = (char *) malloc((size_t) buf_len);
    if (prompt == NULL) {
        free(work);
        return NULL;
    }
    int32_t need = llama_chat_apply_template(
            tmpl != NULL ? tmpl : "chatml", msgs, n_msgs, true, prompt, buf_len);
    if (need < 0 && tmpl != NULL) {
        need = llama_chat_apply_template("chatml", msgs, n_msgs, true, prompt, buf_len);
    }
    if (need > buf_len) {
        char *grown = (char *) realloc(prompt, (size_t) need + 1);
        if (grown == NULL) {
            free(prompt);
            free(work);
            return NULL;
        }
        prompt = grown;
        buf_len = need + 1;
        need = llama_chat_apply_template(
                tmpl != NULL ? tmpl : "chatml", msgs, n_msgs, true, prompt, buf_len);
    }
    if (need < 0) {
        LOGE("chat template failed");
        free(prompt);
        free(work);
        return NULL;
    }

    /* Tokenize. */
    const struct llama_vocab *vocab = llama_model_get_vocab(llm->model);
    int32_t cap = need + 64;
    llama_token *tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) cap);
    if (tokens == NULL) {
        free(prompt);
        free(work);
        return NULL;
    }
    int32_t n_prompt = llama_tokenize(vocab, prompt, need, tokens, cap, true, true);
    free(prompt);
    free(work);
    if (n_prompt <= 0) {
        LOGE("tokenize failed: %d", n_prompt);
        free(tokens);
        return NULL;
    }

    /* Clamp so the prompt plus the reply fits the context. */
    const int32_t n_ctx = (int32_t) llama_n_ctx(llm->ctx);
    const int32_t limit = n_ctx - max_tokens - 8;
    if (limit > 0 && n_prompt > limit) {
        n_prompt = limit;
    }

    llama_kv_cache_clear(llm->ctx);

    /* Decode the prompt in n_batch-sized chunks. */
    const int32_t n_batch = (int32_t) llama_n_batch(llm->ctx);
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        int32_t chunk = n_prompt - i < n_batch ? n_prompt - i : n_batch;
        struct llama_batch batch = llama_batch_get_one(tokens + i, chunk);
        if (llama_decode(llm->ctx, batch) != 0) {
            LOGE("prompt decode failed at %d", i);
            free(tokens);
            return NULL;
        }
    }
    free(tokens);

    /* Sample until EOG or budget. */
    size_t out_cap = 4096;
    size_t out_len = 0;
    char *out = (char *) malloc(out_cap);
    if (out == NULL) return NULL;
    out[0] = '\0';
    char piece[256];

    for (jint g = 0; g < max_tokens; g++) {
        llama_token tok = llama_sampler_sample(llm->smpl, llm->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int32_t plen = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (plen > 0) {
            if (out_len + (size_t) plen + 1 > out_cap) {
                out_cap = (out_len + (size_t) plen + 1) * 2;
                char *grown = (char *) realloc(out, out_cap);
                if (grown == NULL) {
                    free(out);
                    return NULL;
                }
                out = grown;
            }
            memcpy(out + out_len, piece, (size_t) plen);
            out_len += (size_t) plen;
            out[out_len] = '\0';
        }
        struct llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(llm->ctx, batch) != 0) {
            LOGE("decode failed mid-generation");
            break;
        }
    }

    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
