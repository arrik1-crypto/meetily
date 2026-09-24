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

/*
 * What the Kotlin side holds as its "context pointer".
 *
 * The abort flag has to live per context, not in a global: live
 * transcription runs its own context alongside an import or an accuracy
 * check, and stopping the batch job must not kill the live one.
 */
typedef struct {
    struct whisper_context *ctx;
    volatile int abort;
} whisper_handle;

static struct whisper_context *ctx_of(jlong ptr) {
    whisper_handle *h = (whisper_handle *) (intptr_t) ptr;
    return h != NULL ? h->ctx : NULL;
}

/*
 * Polled by ggml between graph nodes, so a stop lands within one node of the
 * encoder or decoder instead of after a whole 30-second window.
 */
static bool should_abort(void *user_data) {
    const whisper_handle *h = (const whisper_handle *) user_data;
    return h != NULL && h->abort != 0;
}

static bool count_encoder_pass(struct whisper_context *ctx,
                               struct whisper_state *state,
                               void *user_data) {
    (void) ctx;
    (void) state;
    g_encoder_passes++;
    return !should_abort(user_data); /* false skips the encode */
}

/*
 * Hands raw bytes to Kotlin, which decodes them as standard UTF-8.
 *
 * Whisper's tokens are byte-level BPE pieces, so its output is not
 * guaranteed to be valid MODIFIED UTF-8, which is what NewStringUTF wants:
 * a 4-byte character (emoji, rare Han) never is, and a sequence cut short
 * is not either. CheckJNI aborts the process on that, and release ART
 * mangles it, sometimes swallowing the record separators that follow.
 * String(bytes, UTF_8) replaces a bad sequence with U+FFFD instead.
 */
static jbyteArray to_java_bytes(JNIEnv *env, const char *buf, size_t len) {
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    if (arr == NULL) return NULL;
    if (len > 0) {
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) buf);
    }
    return arr;
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
    whisper_handle *h = (whisper_handle *) calloc(1, sizeof(whisper_handle));
    if (h == NULL) {
        whisper_free(ctx);
        return 0;
    }
    h->ctx = ctx;
    LOGI("model loaded");
    return (jlong) (intptr_t) h;
}

JNIEXPORT void JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_freeContext(
        JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env;
    (void) thiz;
    if (ptr != 0) {
        whisper_handle *h = (whisper_handle *) (intptr_t) ptr;
        whisper_free(h->ctx);
        free(h);
    }
}

/*
 * Stops a whisper_full already running on this context, from another
 * thread. Sticky, like the llama flag: whoever raises it clears it before
 * the next call, so a stop that arrives between calls is not lost.
 */
JNIEXPORT void JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_setAbort(
        JNIEnv *env, jobject thiz, jlong ptr, jboolean on) {
    (void) env;
    (void) thiz;
    if (ptr == 0) return;
    ((whisper_handle *) (intptr_t) ptr)->abort = on ? 1 : 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_transcribeBytes(
        JNIEnv *env, jobject thiz, jlong ptr, jfloatArray samples,
        jstring language, jint n_threads, jboolean translate,
        jstring prompt) {
    (void) thiz;
    whisper_handle *handle = (whisper_handle *) (intptr_t) ptr;
    struct whisper_context *ctx = ctx_of(ptr);
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
    params.abort_callback = should_abort;
    params.abort_callback_user_data = handle;

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
        if (handle->abort) {
            LOGI("whisper_full stopped on request");
        } else {
            LOGE("whisper_full failed: %d", ret);
        }
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

    jbyteArray out = to_java_bytes(env, buf, len);
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
    struct whisper_context *ctx = ctx_of(ptr);
    if (ctx == NULL) return NULL;
    int id = whisper_full_lang_id(ctx);
    if (id < 0) return NULL;
    const char *str = whisper_lang_str(id);
    if (str == NULL || str[0] == '\0') return NULL;
    return (*env)->NewStringUTF(env, str);
}

/*
 * audio_ctx: encoder frames to run (50 per second of audio), or 0 for the
 * model's full 30-second window. Only live capture passes a value; see
 * WhisperBridge.liveAudioCtx for how it is sized.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_meetily_mobile_whisper_WhisperBridge_transcribeWordsBytes(
        JNIEnv *env, jobject thiz, jlong ptr, jfloatArray samples,
        jstring language, jint n_threads, jboolean translate,
        jstring prompt, jint audio_ctx) {
    (void) thiz;
    whisper_handle *handle = (whisper_handle *) (intptr_t) ptr;
    struct whisper_context *ctx = ctx_of(ptr);
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
    params.encoder_begin_callback_user_data = handle;
    params.abort_callback = should_abort;
    params.abort_callback_user_data = handle;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.translate = translate ? true : false;
    params.suppress_blank = true;
    /*
     * whisper_full rejects a value above the model's own context, so
     * anything at or past it means "the whole window". The field is copied
     * into the state on every call, so passing 0 really does restore the
     * default for the next caller.
     */
    if (audio_ctx > 0 && audio_ctx < whisper_n_audio_ctx(ctx)) {
        params.audio_ctx = (int) audio_ctx;
    }

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
    LOGI("whisper_full: %d encoder pass(es) for %d samples (audio_ctx=%d)",
         g_encoder_passes, (int) n_samples, params.audio_ctx);
    (*env)->ReleaseFloatArrayElements(env, samples, pcm, JNI_ABORT);
    if (lang != NULL) {
        (*env)->ReleaseStringUTFChars(env, language, lang);
    }
    if (prompt_chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, prompt, prompt_chars);
    }
    if (ret != 0) {
        if (handle->abort) {
            LOGI("whisper_full stopped on request");
        } else {
            LOGE("whisper_full failed: %d", ret);
        }
        return NULL;
    }

    size_t cap = 8192;
    size_t len = 0;
    char *buf = (char *) malloc(cap);
    if (buf == NULL) return NULL;
    buf[0] = '\0';
    int word_open = 0;
    /* A pure-space token was dropped; the next record carries its space. */
    int pending_space = 0;

    /*
     * Languages written without spaces between words.
     *
     * The word-boundary rule below is "a token that begins with a space
     * starts a new word". In Chinese, Japanese, Thai, Lao, Burmese and Khmer
     * no token ever begins with one, so after the first token word_open
     * stays set and no further word is ever opened: an entire batch collapses
     * into ONE record carrying only the first token's timestamp. The caller
     * then has a single "word" spanning up to 28 seconds, and every other
     * chunk's timing and speaker attribution is discarded.
     *
     * For these scripts each character run gets its own record. Detected
     * from the language whisper actually decoded, so it follows
     * auto-detection rather than trusting the requested hint. Not when
     * translating: whisper_full_lang_id still reports the SOURCE language
     * then, but the text being decoded is English, which the space rule
     * already handles — per-token records would cut its words into BPE
     * pieces.
     */
    int no_space_script = 0;
    if (!translate) {
        const int lang_id = whisper_full_lang_id(ctx);
        const char *code = (lang_id >= 0) ? whisper_lang_str(lang_id) : NULL;
        if (code != NULL) {
            no_space_script =
                strcmp(code, "zh") == 0 || strcmp(code, "ja") == 0 ||
                strcmp(code, "th") == 0 || strcmp(code, "lo") == 0 ||
                strcmp(code, "my") == 0 || strcmp(code, "km") == 0 ||
                strcmp(code, "yue") == 0;
        }
    }

    /*
     * Each record keeps the token's own leading space rather than stripping
     * it, so the Kotlin side can rebuild the text by plain concatenation:
     * "今日は" stays joined and "hello world" keeps its space. Joining
     * stripped records with " " put a space between every CJK token.
     */
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

            const unsigned char first = (unsigned char) text[0];
            /*
             * Byte-level BPE can split one character across tokens, e.g.
             * [E4 B8][AD]. A token that opens with a continuation byte
             * (10xxxxxx) is the rest of the previous record's character, so
             * it always extends that record — even across a segment
             * boundary — or a header would land between the character's
             * bytes and corrupt both. Only with nothing written yet does it
             * open a record of its own.
             */
            const int cont = (first & 0xC0) == 0x80;
            int starts_word;
            if (cont) {
                starts_word = (len == 0);
            } else {
                const char *p = text;
                while (*p == ' ') p++;
                if (*p == '\0') { /* pure-space token */
                    pending_space = 1;
                    continue;
                }
                /*
                 * In a no-space script a non-ASCII token starts its own
                 * record, and so does an ASCII token after a non-ASCII one.
                 * ASCII following ASCII extends it, so an English word or
                 * number spoken inside Japanese stays one record instead of
                 * one per BPE piece.
                 */
                const int prev_non_ascii =
                        word_open && len > 0 && ((unsigned char) buf[len - 1]) >= 0x80;
                starts_word = !word_open || first == ' ' || pending_space ||
                        (no_space_script && (first >= 0x80 || prev_non_ascii));
            }
            char header[48];
            size_t hlen = 0;
            if (starts_word) {
                hlen = (size_t) snprintf(header, sizeof(header),
                                         "\x1e%lld\x1f%s", ms,
                                         (pending_space && first != ' ') ? " " : "");
                pending_space = 0;
                word_open = 1;
            }
            size_t tlen = strlen(text);
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
            memcpy(buf + len, text, tlen);
            len += tlen;
            buf[len] = '\0';
        }
        /*
         * A segment boundary always starts a new word. In spaced scripts it
         * is also a word break in the text, which the next segment's first
         * token may not spell out with a leading space of its own.
         */
        word_open = 0;
        if (!no_space_script) pending_space = 1;
    }

    jbyteArray out = to_java_bytes(env, buf, len);
    free(buf);
    return out;
}
