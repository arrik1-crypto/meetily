package com.meetily.mobile.llm

/**
 * The message wire format of [LlamaBridge.generate] and
 * [LlamaBridge.countTokens]: per message, [RECORD] + role + [UNIT] + content,
 * which llama_jni.c splits back into messages before applying the model's
 * chat template.
 *
 * Content is text this app did not write: a transcript, an imported file,
 * OCR text, notes, a title, an attendee's name, the user's question. A
 * [RECORD] inside any of it, followed by "system" and a [UNIT], would end the
 * message there and open a new one with whatever role it named, and the
 * template would then frame that text with the model's real turn markers.
 * So both separators become spaces in every role and content before
 * packing. The native side does the rest: text inside a message can no
 * longer produce control tokens such as "<|im_end|>" (chat_render.cpp).
 */
object PromptPacking {
    const val RECORD = '\u001e'
    const val UNIT = '\u001f'

    /** [text] with both separators replaced by a space; the same instance when it has none. */
    fun clean(text: String): String =
        if (text.indexOf(RECORD) < 0 && text.indexOf(UNIT) < 0) {
            text
        } else {
            text.replace(RECORD, ' ').replace(UNIT, ' ')
        }

    fun pack(messages: List<Pair<String, String>>): String = buildString {
        for ((role, content) in messages) {
            append(RECORD).append(clean(role)).append(UNIT).append(clean(content))
        }
    }
}
