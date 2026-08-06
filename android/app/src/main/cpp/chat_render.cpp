// Renders a chat prompt with the model's OWN template, via Jinja.
//
// llama.cpp has two template engines. The one in the core library,
// llama_chat_apply_template, does not evaluate Jinja at all: it substring-
// matches the template against a hard-coded list of known formats
// (src/llama-chat.cpp) and returns -1 for anything it does not recognise.
// That list is a snapshot, so it goes stale the moment a model ships a new
// turn format — Gemma 4 uses <|turn>role / <turn|> and matches nothing in
// b10089, whose only Gemma branch tests for "<start_of_turn>".
//
// The real renderer lives in llama.cpp's `llama-common` target and evaluates
// the GGUF's embedded Jinja template properly. This file is the C-callable
// shim over it, because llama_jni.c is C and this API is C++.
//
// Doing it this way fixes every model at once rather than one at a time.
// The alternative on offer was hand-transcribing Gemma 4's turn format into
// C, which would have worked until the next model shipped a new one.

#include "chat.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>
#include <utility>

extern "C" char *meetily_render_chat(
        const struct llama_model *model,
        const char *const *roles,
        const char *const *contents,
        int n_msgs,
        int *out_len,
        char *err,
        size_t err_len) {
    if (out_len != nullptr) {
        *out_len = 0;
    }
    try {
        // "" = no override; use whatever the GGUF carries.
        common_chat_templates_ptr tmpls = common_chat_templates_init(model, "");
        if (!tmpls) {
            snprintf(err, err_len, "the model carries no usable chat template");
            return nullptr;
        }

        common_chat_templates_inputs in;
        in.use_jinja = true;
        in.add_generation_prompt = true;
        // This app strips reasoning blocks and shows only the answer, so ask
        // the template not to open one. Models that never deliberate ignore
        // it; Gemma 4 renders its empty thought channel, which is what its
        // own template does when thinking is off.
        in.enable_thinking = false;
        in.reasoning_format = COMMON_REASONING_FORMAT_NONE;
        // BOS is added at tokenize time unless the template emits it itself;
        // the caller checks which happened. Adding it here as well would
        // double it.
        in.add_bos = false;
        in.add_eos = false;

        in.messages.reserve(static_cast<size_t>(n_msgs));
        for (int i = 0; i < n_msgs; i++) {
            common_chat_msg msg;
            msg.role = roles[i] != nullptr ? roles[i] : "user";
            msg.content = contents[i] != nullptr ? contents[i] : "";
            in.messages.push_back(std::move(msg));
        }

        common_chat_params params = common_chat_templates_apply(tmpls.get(), in);
        const std::string &prompt = params.prompt;
        if (prompt.empty()) {
            snprintf(err, err_len, "the model's chat template rendered nothing");
            return nullptr;
        }

        char *out = static_cast<char *>(malloc(prompt.size() + 1));
        if (out == nullptr) {
            snprintf(err, err_len, "out of memory rendering the prompt");
            return nullptr;
        }
        memcpy(out, prompt.data(), prompt.size());
        out[prompt.size()] = '\0';
        if (out_len != nullptr) {
            *out_len = static_cast<int>(prompt.size());
        }
        return out;
    } catch (const std::exception &e) {
        // Jinja evaluation throws on a malformed or unsupported template.
        // Never let that cross the JNI boundary — the caller falls back.
        snprintf(err, err_len, "%s", e.what());
        return nullptr;
    } catch (...) {
        snprintf(err, err_len, "unknown error while rendering the chat template");
        return nullptr;
    }
}
