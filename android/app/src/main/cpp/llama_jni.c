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
 * Why the last generate()/countTokens() failed.
 *
 * generate() returns NULL from six distinct native paths, and an immediate
 * end-of-turn returns an empty string. Kotlin's `?.trim().orEmpty()` mapped
 * every one of them to the same blank string and the same sentence in the
 * UI — "the on-device model produced no output" — which named the one cause
 * (the model) that often was not involved. Two speculative fixes were
 * shipped against that sentence before anyone could tell the cases apart.
 *
 * Not thread-safe by design: LocalLlm serialises every call through one
 * lock, so there is exactly one in flight.
 */
static char g_last_error[512] = "";

#define FAIL(...) do { \
        snprintf(g_last_error, sizeof(g_last_error), __VA_ARGS__); \
        LOGE("%s", g_last_error); \
    } while (0)

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

    /*
     * Quantize the KV cache to q8_0: at 4096 ctx on a 7-9B model this frees
     * roughly half a gigabyte versus fp16, which is the difference between
     * surviving and being LMK-killed on 12 GB phones. Flash attention is
     * required for a quantized V cache. If any model/device combination
     * rejects this configuration, fall back to default fp16 KV below.
     */
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cparams.type_k = GGML_TYPE_Q8_0;
    cparams.type_v = GGML_TYPE_Q8_0;
    struct llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == NULL) {
        LOGI("q8_0 KV + flash-attn rejected; retrying with default cache");
        cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        cparams.type_k = GGML_TYPE_F16;
        cparams.type_v = GGML_TYPE_F16;
        ctx = llama_init_from_model(model, cparams);
    }
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
 * Resizes the thread pool between generations.
 *
 * A recording can start after a summary is already under way, and its
 * capture threads land on top of a pool that was sized for an idle phone.
 * Called from LocalLlm at the top of each generate(), where no decode is in
 * flight, so this never races a running batch.
 */
JNIEXPORT void JNICALL
Java_com_meetily_mobile_llm_LlamaBridge_setThreads(
        JNIEnv *env, jobject thiz, jlong ptr, jint n_threads) {
    (void) env;
    (void) thiz;
    if (ptr == 0 || n_threads <= 0) return;
    local_llm *llm = (local_llm *) (intptr_t) ptr;
    llama_set_n_threads(llm->ctx, n_threads, n_threads);
}

/*
 * packed messages: per message, 0x1e + role + 0x1f + content. Parsed into
 * llama_chat_message entries pointing into a NUL-punched copy, run through
 * the model's chat template, and tokenized.
 *
 * Returns a malloc'd token array and writes the count to *out_n, or NULL on
 * failure. Shared by generate() and countTokens() so the two can never
 * disagree about what a prompt costs — a measurement the caller cannot rely
 * on is worse than no measurement at all.
 */
static llama_token *tokenize_packed(
        JNIEnv *env, local_llm *llm, jstring packed, int32_t *out_n) {
    *out_n = 0;

    const char *packed_c = (*env)->GetStringUTFChars(env, packed, NULL);
    if (packed_c == NULL) return NULL;
    char *work = strdup(packed_c);
    (*env)->ReleaseStringUTFChars(env, packed, packed_c);
    if (work == NULL) {
        FAIL("could not copy the packed prompt (out of memory)");
        return NULL;
    }

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
        FAIL("no messages were parsed from the packed prompt");
        free(work);
        return NULL;
    }
    /* Re-punch separators consumed by the scan (contents end at 0x1e). */
    for (size_t i = 0; i < n_msgs; i++) {
        char *tail = strchr(msgs[i].content, '\x1e');
        if (tail != NULL) *tail = '\0';
    }

    /*
     * Chat template. Resolve it ONCE and keep that choice.
     *
     * The previous version let the ChatML fallback pick a template and then
     * threw the choice away: the regrow below re-rendered with
     * `tmpl != NULL ? tmpl : "chatml"` — the model's own template, the one
     * that had just failed. So any prompt over the initial 8192-byte buffer
     * returned NULL for any model llama.cpp cannot match. That is every
     * Gemma 4 build: its turn markers are <|turn>role / <turn|>, and
     * b10089's llama-chat.cpp matches Gemma only on "<start_of_turn>"
     * (:155) before falling through to UNKNOWN (:239), which llama.cpp
     * turns into -1. Verified against the official Gemma 4 template shipped
     * in that same tag: it contains "<start_of_turn>" exactly zero times.
     *
     * Every summary prompt here exceeds 8192 bytes, so on Gemma 4 this
     * failed every time, in both the section pass and the final one.
     */
    const char *tmpl = llama_model_chat_template(llm->model, NULL);
    int32_t buf_len = 8192;
    char *prompt = (char *) malloc((size_t) buf_len);
    if (prompt == NULL) {
        FAIL("could not allocate the prompt buffer (out of memory)");
        free(work);
        return NULL;
    }

    int32_t need;
    if (tmpl == NULL) {
        /* No template in the GGUF at all. ChatML is the conventional
           default and nothing contradicts it here. */
        tmpl = "chatml";
        need = llama_chat_apply_template(tmpl, msgs, n_msgs, true, prompt, buf_len);
    } else {
        need = llama_chat_apply_template(tmpl, msgs, n_msgs, true, prompt, buf_len);
        if (need < 0) {
            /*
             * The model DECLARES a format and this llama.cpp does not know
             * it. Falling back to ChatML here is not a default — it is a
             * guess against the model's own statement. It would emit
             * <|im_start|> markers that are absent from the vocabulary and
             * never emit the model's generation cue, producing a
             * plausible-looking but badly framed summary that nothing
             * downstream can detect. Refusing is the honest answer.
             */
            FAIL("this model's chat format is not supported by the built-in "
                 "engine — pick a different model");
            free(prompt);
            free(work);
            return NULL;
        }
    }

    if (need >= buf_len) {   /* >=, so prompt[need] always has a slot */
        char *grown = (char *) realloc(prompt, (size_t) need + 1);
        if (grown == NULL) {
            FAIL("could not grow the prompt buffer to %d bytes", need + 1);
            free(prompt);
            free(work);
            return NULL;
        }
        prompt = grown;
        buf_len = need + 1;
        /* The SAME template. This is the line that discarded the choice. */
        need = llama_chat_apply_template(tmpl, msgs, n_msgs, true, prompt, buf_len);
    }
    if (need < 0) {
        FAIL("applying the chat template failed for %zu messages", n_msgs);
        free(prompt);
        free(work);
        return NULL;
    }
    prompt[need] = '\0';
    LOGI("prompt: %d bytes, %zu msgs, tail=[%s]",
         need, n_msgs, prompt + (need > 48 ? need - 48 : 0));

    /* Tokenize. */
    const struct llama_vocab *vocab = llama_model_get_vocab(llm->model);
    int32_t cap = need + 64;
    llama_token *tokens = (llama_token *) malloc(sizeof(llama_token) * (size_t) cap);
    if (tokens == NULL) {
        FAIL("could not allocate %d tokens (out of memory)", cap);
        free(prompt);
        free(work);
        return NULL;
    }
    int32_t n_prompt = llama_tokenize(vocab, prompt, need, tokens, cap, true, true);
    free(prompt);
    free(work);
    if (n_prompt <= 0) {
        FAIL("tokenizing failed (%d) for a %d-byte prompt", n_prompt, need);
        free(tokens);
        return NULL;
    }
    *out_n = n_prompt;
    return tokens;
}

/*
 * Why the last generate() or countTokens() failed, or "" if it did not.
 *
 * Read only after a null/blank return; LocalLlm serialises calls, so the
 * value always belongs to the call that just finished.
 */
JNIEXPORT jstring JNICALL
Java_com_meetily_mobile_llm_LlamaBridge_lastError(JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, g_last_error);
}

/*
 * Tokens this prompt costs with the template applied — exactly the number
 * generate() will see.
 *
 * Exposed because the Kotlin side was budgeting from a chars-per-token
 * guess, and a guess that runs optimistic overflows the window silently.
 * Meeting transcripts are the worst case for it: timestamps, speaker
 * labels and proper nouns tokenize far worse than the prose the estimate
 * was calibrated on.
 */
JNIEXPORT jint JNICALL
Java_com_meetily_mobile_llm_LlamaBridge_countTokens(
        JNIEnv *env, jobject thiz, jlong ptr, jstring packed) {
    (void) thiz;
    if (ptr == 0 || packed == NULL) return -1;
    local_llm *llm = (local_llm *) (intptr_t) ptr;
    g_last_error[0] = '\0';
    int32_t n = 0;
    llama_token *tokens = tokenize_packed(env, llm, packed, &n);
    if (tokens == NULL) return -1;
    free(tokens);
    return (jint) n;
}

JNIEXPORT jstring JNICALL
Java_com_meetily_mobile_llm_LlamaBridge_generate(
        JNIEnv *env, jobject thiz, jlong ptr, jstring packed, jint max_tokens) {
    (void) thiz;
    if (ptr == 0 || packed == NULL) return NULL;
    local_llm *llm = (local_llm *) (intptr_t) ptr;
    const struct llama_vocab *vocab = llama_model_get_vocab(llm->model);
    g_last_error[0] = '\0';

    int32_t n_prompt = 0;
    llama_token *tokens = tokenize_packed(env, llm, packed, &n_prompt);
    if (tokens == NULL) return NULL;

    /*
     * Clamp so the prompt plus the reply fits the context — dropping from
     * the MIDDLE, never the tail.
     *
     * This used to truncate the tail, which is where the template puts the
     * assistant header: the tokens that turn the prompt from "continue this
     * text" into "now answer". Cutting them off left the model resuming a
     * transcript mid-sentence, and its first sampled token was end-of-turn.
     * That exited the sampling loop before it wrote anything, and an empty
     * string is not an error anywhere in this file, so the failure arrived
     * in the UI as "the model produced no output" with nothing logged.
     *
     * Keeping a head as well as the tail preserves the system instruction,
     * which is the other end that must survive.
     */
    const int32_t n_ctx = (int32_t) llama_n_ctx(llm->ctx);
    const int32_t limit = n_ctx - max_tokens - 8;
    if (limit > 0 && n_prompt > limit) {
        const int32_t head = limit / 4;
        const int32_t tail = limit - head;
        LOGE("prompt %d > limit %d; dropping %d tokens from the middle",
             n_prompt, limit, n_prompt - limit);
        memmove(tokens + head, tokens + (n_prompt - tail),
                sizeof(llama_token) * (size_t) tail);
        n_prompt = limit;
    }

    /* b10089: llama_kv_cache_clear was replaced by the memory API. */
    llama_memory_clear(llama_get_memory(llm->ctx), true);

    /* Decode the prompt in n_batch-sized chunks. */
    const int32_t n_batch = (int32_t) llama_n_batch(llm->ctx);
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        int32_t chunk = n_prompt - i < n_batch ? n_prompt - i : n_batch;
        struct llama_batch batch = llama_batch_get_one(tokens + i, chunk);
        if (llama_decode(llm->ctx, batch) != 0) {
            FAIL("reading the prompt failed at token %d of %d (n_batch=%d, "
                 "n_ctx=%d) — usually not enough memory for this model",
                 i, n_prompt, n_batch, (int) llama_n_ctx(llm->ctx));
            free(tokens);
            return NULL;
        }
    }
    free(tokens);

    /* Sample until EOG or budget. */
    size_t out_cap = 4096;
    size_t out_len = 0;
    char *out = (char *) malloc(out_cap);
    if (out == NULL) {
        FAIL("could not allocate the reply buffer (out of memory)");
        return NULL;
    }
    out[0] = '\0';
    char piece[256];

    for (jint g = 0; g < max_tokens; g++) {
        llama_token tok = llama_sampler_sample(llm->smpl, llm->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) {
            /*
             * Ending the turn as the VERY first token is not the same event
             * as ending it after writing an answer, and only this one is a
             * fault. It is what a model does when handed a prompt framed for
             * a different model: nothing cues it to speak, so it stops.
             */
            if (g == 0) {
                FAIL("the model ended its turn without writing anything "
                     "(prompt was %d tokens)", n_prompt);
            }
            break;
        }
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

    if (out_len == 0 && g_last_error[0] == '\0') {
        FAIL("the model produced %d tokens, none of which were text", max_tokens);
    }
    jstring result = (*env)->NewStringUTF(env, out);
    free(out);
    return result;
}
