// C interface of chat_render.cpp, the C++ side of libmeetily_llama, for
// llama_jni.c. Everything here is internal to the library: jni_exports.map
// keeps it all local.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "llama.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * One run of a rendered prompt: bytes [start, start + len).
 *
 * is_input is 1 for text that came from a message (meeting content, notes,
 * OCR text, titles, attendee names, the user's question, and the app's own
 * instructions) and 0 for text the chat template wrote around it: the turn
 * markers, role headers and generation cue.
 */
typedef struct meetily_prompt_span {
    int32_t start;
    int32_t len;
    int32_t is_input;
} meetily_prompt_span;

/*
 * Renders the messages with the model's own Jinja template.
 *
 * Returns the malloc'd prompt (NUL-terminated) and writes its length, plus a
 * malloc'd array of spans that covers it in order, or NULL with a reason in
 * err. The caller frees both.
 */
char *meetily_render_chat(const struct llama_model *model,
                          const char *const *roles,
                          const char *const *contents,
                          int n_msgs,
                          int *out_len,
                          meetily_prompt_span **out_spans,
                          int *out_n_spans,
                          char *err,
                          size_t err_len);

/*
 * Tokenises a rendered prompt so that only template spans can produce
 * control tokens: text inside an input span that spells one ("<|im_end|>",
 * "<end_of_turn>", "<|eot_id|>"...) is read as plain text.
 *
 * add_special asks for the vocabulary's BOS/EOS, as llama_tokenize's does.
 * Returns malloc'd tokens and writes their count, or NULL with a reason in
 * err. *out_guarded is the number of input spans that held such text (0 on
 * the usual path, which tokenises exactly as one llama_tokenize call would).
 */
llama_token *meetily_tokenize_prompt(const struct llama_model *model,
                                     const char *text,
                                     int32_t len,
                                     const meetily_prompt_span *spans,
                                     int n_spans,
                                     bool add_special,
                                     int32_t *out_n,
                                     int *out_guarded,
                                     char *err,
                                     size_t err_len);

/*
 * For the fallback renderer (llama_chat_apply_template), which cannot say
 * where each message ends up: breaks every control token's text, and every
 * markup-like user-defined token's text, in each content by inserting a
 * zero-width space after its first character.
 *
 * out_copies[i] receives a malloc'd guarded copy, or NULL when contents[i]
 * needs none. Returns how many contents were changed, or -1 (with nothing
 * left allocated) when out of memory.
 */
int meetily_guard_contents(const struct llama_model *model,
                           const char *const *contents,
                           int n,
                           char **out_copies);

#ifdef __cplusplus
}
#endif
