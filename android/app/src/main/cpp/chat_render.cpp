// Renders a chat prompt with the model's OWN template, via Jinja, and
// tokenises it so that only the template can produce control tokens.
//
// llama.cpp has two template engines. The one in the core library,
// llama_chat_apply_template, does not evaluate Jinja at all: it substring-
// matches the template against a hard-coded list of known formats
// (src/llama-chat.cpp) and returns -1 for anything it does not recognise.
// That list is a snapshot, so it goes stale the moment a model ships a new
// turn format — Gemma 4 uses <|turn>role / <turn|> and still matches
// nothing there at v1.0.0, whose only Gemma branch tests for
// "<start_of_turn>".
//
// The real renderer lives in llama.cpp's `llama-common` target and evaluates
// the GGUF's embedded Jinja template properly. This file is the C-callable
// shim over it, because llama_jni.c is C and this API is C++.
//
// Doing it this way fixes every model at once rather than one at a time.
// The alternative on offer was hand-transcribing Gemma 4's turn format into
// C, which would have worked until the next model shipped a new one.
//
// Control tokens. Everything in a message (a transcript, OCR text, notes, a
// title, an attendee's name, the question asked) is text the app did not
// write. Tokenised with parse_special the way the template's own markers
// must be, a "<|im_end|>" or "<end_of_turn>" inside a transcript became the
// real end-of-turn token, and whatever followed it read to the model as a
// new turn: a prompt injection. At v1.0.0 the renderer returns the prompt
// as parts, each marked as template text or as input (common_chat_prompt),
// and meetily_tokenize_prompt below lets only template parts produce
// control tokens.

#include "chat_render.h"

#include "chat.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <functional>
#include <string>
#include <string_view>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

// The texts llama_tokenize can turn into a single special token.
struct special_texts {
    // CONTROL and UNKNOWN tokens: matched only when parse_special is true.
    std::unordered_set<std::string_view> control;
    // USER_DEFINED tokens that look like markup ("<...>" or "[...]"). The
    // tokenizer matches these even when parse_special is false, and a GGUF
    // can mark a turn marker this way rather than as CONTROL. Views into
    // the vocabulary's own strings, which outlive every call here.
    std::unordered_set<std::string_view> markers;
    // Every key length, longest first, and every key's first byte.
    std::vector<size_t> lengths;
    bool first[256] = {};
};

bool looks_like_marker(std::string_view t) {
    if (t.size() < 3) {
        return false;
    }
    const bool angle = t.front() == '<' && t.back() == '>';
    const bool square = t.front() == '[' && t.back() == ']';
    if (!angle && !square) {
        return false;
    }
    for (const unsigned char c : t) {
        if (c <= ' ' || c == 0x7f) {
            return false;
        }
    }
    return true;
}

special_texts collect_special(const llama_vocab *vocab) {
    special_texts st;
    const int32_t n = llama_vocab_n_tokens(vocab);
    for (llama_token id = 0; id < n; id++) {
        const int attr = llama_vocab_get_attr(vocab, id);
        const bool control = (attr & (LLAMA_TOKEN_ATTR_CONTROL | LLAMA_TOKEN_ATTR_UNKNOWN)) != 0;
        if (!control && (attr & LLAMA_TOKEN_ATTR_USER_DEFINED) == 0) {
            continue;
        }
        const char *text = llama_vocab_get_text(vocab, id);
        if (text == nullptr || text[0] == '\0') {
            continue;
        }
        const std::string_view t(text);
        if (control) {
            st.control.insert(t);
        } else if (looks_like_marker(t)) {
            st.markers.insert(t);
        } else {
            continue;
        }
        st.first[static_cast<unsigned char>(t[0])] = true;
        st.lengths.push_back(t.size());
    }
    std::sort(st.lengths.begin(), st.lengths.end(), std::greater<size_t>());
    st.lengths.erase(std::unique(st.lengths.begin(), st.lengths.end()), st.lengths.end());
    return st;
}

struct hit {
    size_t pos;
    size_t len;
    bool control;
};

// Every position in s where a special text starts, with the longest one
// there. Overlapping occurrences are all reported, which is conservative:
// the tokenizer itself only ever produces a subset of them.
std::vector<hit> find_special(const special_texts &st, std::string_view s) {
    std::vector<hit> out;
    for (size_t p = 0; p < s.size(); p++) {
        if (!st.first[static_cast<unsigned char>(s[p])]) {
            continue;
        }
        for (const size_t len : st.lengths) {
            if (len > s.size() - p) {
                continue;
            }
            const std::string_view cand = s.substr(p, len);
            if (st.control.count(cand) != 0) {
                out.push_back({p, len, true});
                break;
            }
            if (st.markers.count(cand) != 0) {
                out.push_back({p, len, false});
                break;
            }
        }
    }
    return out;
}

// Bytes in the UTF-8 character that starts with lead byte c.
size_t utf8_char_len(unsigned char c) {
    if (c < 0x80) return 1;
    if ((c & 0xE0) == 0xC0) return 2;
    if ((c & 0xF0) == 0xE0) return 3;
    if ((c & 0xF8) == 0xF0) return 4;
    return 1;
}

// U+200B ZERO WIDTH SPACE: splits a token's text without changing what a
// reader (or the model) sees.
constexpr char ZWSP[] = "\xE2\x80\x8B";

// s with a zero-width space after the first character of every hit that
// keep() accepts, so none of them can be read as its token any more. A hit
// one character long cannot be split and is left alone.
template <typename Keep>
std::string break_hits(std::string_view s, const std::vector<hit> &hits, Keep keep, int *n_broken) {
    std::vector<size_t> at;
    for (const hit &h : hits) {
        const size_t cut = h.pos + utf8_char_len(static_cast<unsigned char>(s[h.pos]));
        if (keep(h) && cut < h.pos + h.len) {
            at.push_back(cut);
        }
    }
    std::sort(at.begin(), at.end());
    at.erase(std::unique(at.begin(), at.end()), at.end());
    if (n_broken != nullptr) {
        *n_broken = static_cast<int>(at.size());
    }
    std::string out;
    out.reserve(s.size() + at.size() * (sizeof(ZWSP) - 1));
    size_t from = 0;
    for (const size_t cut : at) {
        out.append(s.substr(from, cut - from));
        out.append(ZWSP);
        from = cut;
    }
    out.append(s.substr(from));
    return out;
}

bool tokenize_into(const llama_vocab *vocab, std::string_view text, bool add_special,
                   bool parse_special, std::vector<llama_token> &out) {
    if (text.empty()) {
        return true;
    }
    // Never more tokens than bytes, plus BOS/EOS; one retry covers a
    // tokenizer that disagrees.
    std::vector<llama_token> buf(text.size() + 8);
    int32_t n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), buf.data(),
                               static_cast<int32_t>(buf.size()), add_special, parse_special);
    if (n < 0 && n != INT32_MIN) {
        buf.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), buf.data(),
                           static_cast<int32_t>(buf.size()), add_special, parse_special);
    }
    if (n < 0) {
        return false;
    }
    out.insert(out.end(), buf.begin(), buf.begin() + n);
    return true;
}

bool overlaps(const hit &h, const meetily_prompt_span &s) {
    const size_t a = static_cast<size_t>(s.start);
    const size_t b = a + static_cast<size_t>(s.len);
    return h.pos < b && a < h.pos + h.len;
}

llama_token *to_malloc(const std::vector<llama_token> &v) {
    auto *out = static_cast<llama_token *>(malloc(sizeof(llama_token) * (v.empty() ? 1 : v.size())));
    if (out != nullptr && !v.empty()) {
        memcpy(out, v.data(), sizeof(llama_token) * v.size());
    }
    return out;
}

}  // namespace

extern "C" char *meetily_render_chat(
        const struct llama_model *model,
        const char *const *roles,
        const char *const *contents,
        int n_msgs,
        int *out_len,
        meetily_prompt_span **out_spans,
        int *out_n_spans,
        char *err,
        size_t err_len) {
    if (out_len != nullptr) {
        *out_len = 0;
    }
    if (out_spans != nullptr) {
        *out_spans = nullptr;
    }
    if (out_n_spans != nullptr) {
        *out_n_spans = 0;
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
        // double it. (Since v1.0.0 the renderer also drops a leading BOS the
        // template wrote when the vocabulary adds one itself.)
        in.add_bos = false;
        in.add_eos = false;

        in.messages.reserve(static_cast<size_t>(n_msgs));
        for (int i = 0; i < n_msgs; i++) {
            common_chat_msg msg;
            msg.role = roles[i] != nullptr ? roles[i] : "user";
            msg.content = contents[i] != nullptr ? contents[i] : "";
            in.messages.push_back(std::move(msg));
        }

        const common_chat_params params = common_chat_templates_apply(tmpls.get(), in);

        // Flatten the parts, merging neighbours of the same kind: the
        // renderer emits one part per template string, not per run.
        std::string prompt;
        std::vector<meetily_prompt_span> spans;
        for (const common_chat_prompt_part &part : params.prompt.parts) {
            if (part.text.empty()) {
                continue;
            }
            const int32_t is_input = part.is_input ? 1 : 0;
            if (!spans.empty() && spans.back().is_input == is_input) {
                spans.back().len += static_cast<int32_t>(part.text.size());
            } else {
                spans.push_back({static_cast<int32_t>(prompt.size()),
                                 static_cast<int32_t>(part.text.size()), is_input});
            }
            prompt += part.text;
        }
        if (prompt.empty()) {
            snprintf(err, err_len, "the model's chat template rendered nothing");
            return nullptr;
        }

        char *out = static_cast<char *>(malloc(prompt.size() + 1));
        auto *span_out = static_cast<meetily_prompt_span *>(
                malloc(sizeof(meetily_prompt_span) * spans.size()));
        if (out == nullptr || span_out == nullptr) {
            free(out);
            free(span_out);
            snprintf(err, err_len, "out of memory rendering the prompt");
            return nullptr;
        }
        memcpy(out, prompt.data(), prompt.size());
        out[prompt.size()] = '\0';
        memcpy(span_out, spans.data(), sizeof(meetily_prompt_span) * spans.size());
        if (out_len != nullptr) {
            *out_len = static_cast<int>(prompt.size());
        }
        if (out_spans != nullptr) {
            *out_spans = span_out;
        } else {
            free(span_out);
        }
        if (out_n_spans != nullptr) {
            *out_n_spans = static_cast<int>(spans.size());
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

extern "C" llama_token *meetily_tokenize_prompt(
        const struct llama_model *model,
        const char *text,
        int32_t len,
        const meetily_prompt_span *spans,
        int n_spans,
        bool add_special,
        int32_t *out_n,
        int *out_guarded,
        char *err,
        size_t err_len) {
    *out_n = 0;
    *out_guarded = 0;
    try {
        const llama_vocab *vocab = llama_model_get_vocab(model);
        const std::string_view all(text, static_cast<size_t>(len));
        const special_texts st = collect_special(vocab);
        const std::vector<hit> hits = find_special(st, all);

        // The markers this template writes itself. Only those can forge a
        // turn; any other markup-like token in the content ("<br>" in a
        // vocabulary that has one) reads the same either way.
        std::unordered_set<std::string_view> template_markers;
        for (const hit &h : hits) {
            if (h.control) {
                continue;
            }
            for (int i = 0; i < n_spans; i++) {
                const size_t a = static_cast<size_t>(spans[i].start);
                if (!spans[i].is_input && h.pos >= a && h.pos + h.len <= a + static_cast<size_t>(spans[i].len)) {
                    template_markers.insert(all.substr(h.pos, h.len));
                    break;
                }
            }
        }
        const auto matters = [&](const hit &h) {
            return h.control || template_markers.count(all.substr(h.pos, h.len)) != 0;
        };

        // Which input spans hold (or touch) such text.
        std::vector<bool> tainted(static_cast<size_t>(n_spans), false);
        int n_tainted = 0;
        for (const hit &h : hits) {
            if (!matters(h)) {
                continue;
            }
            for (int i = 0; i < n_spans; i++) {
                if (spans[i].is_input && !tainted[i] && overlaps(h, spans[i])) {
                    tainted[i] = true;
                    n_tainted++;
                }
            }
        }

        std::vector<llama_token> tokens;
        if (n_tainted == 0) {
            // The usual case: no input can produce a special token, so one
            // call over the whole prompt gives exactly the tokens it always
            // did, boundaries included.
            if (!tokenize_into(vocab, all, add_special, true, tokens)) {
                snprintf(err, err_len, "the tokenizer failed on a %d-byte prompt", len);
                return nullptr;
            }
        } else {
            // Span by span: template text with parse_special, input without
            // it, and a template marker the tokenizer would match regardless
            // (USER_DEFINED) broken apart first. BOS/EOS are placed by hand,
            // as llama_tokenize's add_special would. Only taken when needed,
            // because separate calls can tokenise a span boundary slightly
            // differently from one call (a SentencePiece vocabulary that
            // adds a leading space adds it to every span).
            if (add_special && llama_vocab_get_add_bos(vocab) && llama_vocab_bos(vocab) != LLAMA_TOKEN_NULL) {
                tokens.push_back(llama_vocab_bos(vocab));
            }
            for (int i = 0; i < n_spans; i++) {
                const std::string_view piece = all.substr(static_cast<size_t>(spans[i].start),
                                                          static_cast<size_t>(spans[i].len));
                bool ok;
                if (!spans[i].is_input) {
                    ok = tokenize_into(vocab, piece, false, true, tokens);
                } else if (tainted[i]) {
                    const std::vector<hit> inner = find_special(st, piece);
                    const std::string safe = break_hits(piece, inner, [&](const hit &h) {
                        return !h.control && template_markers.count(piece.substr(h.pos, h.len)) != 0;
                    }, nullptr);
                    ok = tokenize_into(vocab, safe, false, false, tokens);
                } else {
                    ok = tokenize_into(vocab, piece, false, false, tokens);
                }
                if (!ok) {
                    snprintf(err, err_len, "the tokenizer failed on prompt span %d of %d", i, n_spans);
                    return nullptr;
                }
            }
            if (add_special && llama_vocab_get_add_eos(vocab) && llama_vocab_eos(vocab) != LLAMA_TOKEN_NULL) {
                tokens.push_back(llama_vocab_eos(vocab));
            }
        }

        llama_token *out = to_malloc(tokens);
        if (out == nullptr) {
            snprintf(err, err_len, "could not allocate %zu tokens (out of memory)", tokens.size());
            return nullptr;
        }
        *out_n = static_cast<int32_t>(tokens.size());
        *out_guarded = n_tainted;
        return out;
    } catch (const std::exception &e) {
        snprintf(err, err_len, "tokenizing failed: %s", e.what());
        return nullptr;
    } catch (...) {
        snprintf(err, err_len, "tokenizing failed");
        return nullptr;
    }
}

extern "C" int meetily_guard_contents(
        const struct llama_model *model,
        const char *const *contents,
        int n,
        char **out_copies) {
    for (int i = 0; i < n; i++) {
        out_copies[i] = nullptr;
    }
    try {
        const special_texts st = collect_special(llama_model_get_vocab(model));
        int changed = 0;
        for (int i = 0; i < n; i++) {
            if (contents[i] == nullptr) {
                continue;
            }
            const std::string_view s(contents[i]);
            const std::vector<hit> hits = find_special(st, s);
            if (hits.empty()) {
                continue;
            }
            int broken = 0;
            const std::string safe = break_hits(s, hits, [](const hit &) { return true; }, &broken);
            if (broken == 0) {
                continue;
            }
            char *copy = static_cast<char *>(malloc(safe.size() + 1));
            if (copy == nullptr) {
                for (int j = 0; j < n; j++) {
                    free(out_copies[j]);
                    out_copies[j] = nullptr;
                }
                return -1;
            }
            memcpy(copy, safe.data(), safe.size());
            copy[safe.size()] = '\0';
            out_copies[i] = copy;
            changed++;
        }
        return changed;
    } catch (...) {
        for (int j = 0; j < n; j++) {
            free(out_copies[j]);
            out_copies[j] = nullptr;
        }
        return -1;
    }
}
