#include <string.h>
#include <android/log.h>

#include "engine_log.h"

/*
 * llama.cpp, whisper.cpp and ggml each log through a callback that defaults
 * to stderr: nothing an app process sees. Forward warnings and errors, and
 * from INFO only the load-time lines that say where the model went and what
 * it costs: "<buffer> model/KV/compute buffer size = N MiB" (the buffer name
 * says CPU_KLEIDIAI or CPU_REPACK when those took the weights), KleidiAI's
 * "kleidiai: ..." kernel choice, and the KV cache's cell counts
 * ("llama_kv_cache: size = ...", and llama_kv_cache_iswa's "creating ...
 * KV cache, size = N cells", which shows whether a sliding-window model got
 * the small cache). Shape only: nothing forwarded carries prompt, reply or
 * transcript text, like every other log line in these sources.
 *
 * One function for both engines, because they share one ggml in this
 * library and ggml keeps ONE callback per process. llama_log_set and
 * whisper_log_set both replace it, and whisper.cpp sets it again at every
 * model and VAD load, so if only one engine passed this, the other's next
 * load would send ggml's warnings (and KleidiAI's one-off kernel choice,
 * logged by whichever engine loads first) back to stderr. whisper.cpp's own
 * INFO lines match none of the patterns below; its warnings and errors
 * (model file problems, a prompt cut to fit) now reach logcat too.
 */
void meetily_engine_log(enum ggml_log_level level, const char *text, void *user_data) {
    (void) user_data;
    if (text == NULL) return;
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR:
            prio = ANDROID_LOG_ERROR;
            break;
        case GGML_LOG_LEVEL_WARN:
            prio = ANDROID_LOG_WARN;
            break;
        case GGML_LOG_LEVEL_INFO:
            if (strstr(text, " buffer size = ") == NULL &&
                strncmp(text, "kleidiai:", 9) != 0 &&
                strncmp(text, "llama_kv_cache: size = ", 23) != 0 &&
                strstr(text, " KV cache, size = ") == NULL) {
                return;
            }
            prio = ANDROID_LOG_INFO;
            break;
        default:
            return; /* DEBUG, and CONT pieces (which carry no level of their own) */
    }
    __android_log_print(prio, "meetily_ggml", "%s", text);
}
