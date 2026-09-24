package com.meetily.mobile.whisper

import com.meetily.mobile.data.WordStamp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thin JNI bridge to whisper.cpp. Access only through [WhisperModels.isRuntimeAvailable]
 * guards — loading can fail on unsupported ABIs.
 */
object WhisperBridge {

    @Volatile
    private var loaded = false

    fun load(): Boolean {
        if (loaded) return true
        return try {
            com.meetily.mobile.data.CpuFeatures.loadEngineLibrary("meetily_whisper")
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun initContext(modelPath: String): Long
    external fun freeContext(ptr: Long)

    /**
     * Stops a transcription already running on [ptr], from another thread.
     * Sticky until cleared; [abortable] manages it for a single call.
     */
    external fun setAbort(ptr: Long, on: Boolean)

    // The natives return raw bytes, not a jstring: whisper's byte-level
    // tokens are not always valid modified UTF-8, which NewStringUTF needs.
    // Decoding here as standard UTF-8 keeps 4-byte characters intact and
    // turns a broken sequence into U+FFFD instead of a crash or mojibake.
    private external fun transcribeBytes(
        ptr: Long,
        samples: FloatArray,
        language: String?,
        nThreads: Int,
        translate: Boolean,
        prompt: String?
    ): ByteArray?

    private external fun transcribeWordsBytes(
        ptr: Long,
        samples: FloatArray,
        language: String?,
        nThreads: Int,
        translate: Boolean,
        prompt: String?,
        audioCtx: Int
    ): ByteArray?

    fun transcribe(
        ptr: Long,
        samples: FloatArray,
        language: String?,
        nThreads: Int,
        translate: Boolean,
        /** Custom-vocabulary glossary fed as whisper's initial prompt. */
        prompt: String?
    ): String? = transcribeBytes(ptr, samples, language, nThreads, translate, prompt)
        ?.let { String(it, Charsets.UTF_8) }

    /**
     * Transcription with word-level timings. Wire format from the JNI side:
     * per word, 0x1e + start-ms + 0x1f + text, where the text keeps
     * whisper's own leading space. [parseWords] decodes it.
     *
     * [audioCtx] is the number of encoder frames to run, 0 for the full
     * 30-second window. Only live capture passes one; see [liveAudioCtx].
     */
    fun transcribeWords(
        ptr: Long,
        samples: FloatArray,
        language: String?,
        nThreads: Int,
        translate: Boolean,
        prompt: String?,
        audioCtx: Int = 0
    ): String? = transcribeWordsBytes(ptr, samples, language, nThreads, translate, prompt, audioCtx)
        ?.let { String(it, Charsets.UTF_8) }

    /**
     * The language whisper settled on during the most recent call, or null.
     *
     * Passing "auto" costs a complete extra encoder pass every call — whisper
     * encodes the window once just to read a language logit, then again to do
     * the work. Detect once, then pass the answer for the rest of the file.
     */
    external fun lastLanguage(ptr: Long): String?

    /**
     * Encoder frames for one live chunk of [samples] 16 kHz samples, or 0
     * for the full window.
     *
     * Whisper encodes 30 seconds (1500 frames, 50 per second) on every call
     * however short the audio, so a 4-second live chunk paid for 30. The
     * chunk's own frames plus a margin, rounded up to a multiple of 64, is
     * enough to cover it. The floor of 384 frames (about 7.7 s) is there
     * because very small contexts are known to make multilingual and turbo
     * models repeat or hallucinate. The batch importer never uses this: it
     * already fills the window.
     */
    fun liveAudioCtx(samples: Int): Int {
        if (samples <= 0) return 0
        val frames = (samples + SAMPLES_PER_FRAME - 1) / SAMPLES_PER_FRAME
        val sized = ((frames + CTX_MARGIN + 63) / 64 * 64).coerceAtLeast(MIN_LIVE_CTX)
        return if (sized >= FULL_CTX) 0 else sized
    }

    private const val SAMPLES_PER_FRAME = 320 // 16 kHz / 50 frames per second
    private const val CTX_MARGIN = 64
    private const val MIN_LIVE_CTX = 384
    private const val FULL_CTX = 1500

    /**
     * Runs [block] (one transcription on [ptr]) so that [cancelled] turning
     * true stops it mid-call, not only once the whole window is done.
     *
     * whisper_full is one blocking native call that can take a minute on a
     * large model, and until now nothing could interrupt it: a cancel or an
     * unplug waited out the batch at full CPU. A watcher polls [cancelled]
     * and raises this context's native abort flag, which ggml checks between
     * graph nodes. An aborted call returns null.
     *
     * The watcher is joined before returning, so it can never touch [ptr]
     * after the caller frees it.
     */
    fun <T> abortable(ptr: Long, cancelled: () -> Boolean, block: () -> T): T {
        setAbort(ptr, false)
        val finished = CountDownLatch(1)
        val watcher = Thread({
            try {
                var stop = false
                while (!stop && !finished.await(ABORT_POLL_MS, TimeUnit.MILLISECONDS)) {
                    if (cancelled()) {
                        setAbort(ptr, true)
                        stop = true
                    }
                }
            } catch (_: InterruptedException) {
                // Only the caller interrupts, and it is already done.
            }
        }, "whisper-abort-watch")
        watcher.isDaemon = true
        watcher.start()
        try {
            return block()
        } finally {
            finished.countDown()
            var interrupted = false
            while (watcher.isAlive) {
                try {
                    watcher.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private const val ABORT_POLL_MS = 200L

    /**
     * Decodes [transcribeWords] output into (full text, word timings).
     *
     * The text is the records concatenated as-is, because each keeps
     * whisper's own leading space: spaced languages come out spaced, and
     * Chinese, Japanese or Thai come out unspaced. Joining trimmed words
     * with " " put a space between every CJK token ("今日 は 会議").
     */
    fun parseWords(
        raw: String?
    ): Pair<String, List<WordStamp>> {
        if (raw.isNullOrEmpty()) return "" to emptyList()
        val words = ArrayList<WordStamp>()
        val text = StringBuilder()
        for (entry in raw.split('\u001e')) {
            if (entry.isEmpty()) continue
            val sep = entry.indexOf('\u001f')
            if (sep <= 0) continue
            val ms = entry.substring(0, sep).toLongOrNull() ?: continue
            val body = entry.substring(sep + 1)
            val word = body.trim()
            if (word.isEmpty()) continue
            text.append(body)
            words.add(WordStamp(ms, word))
        }
        return text.toString().trim() to words
    }

    /**
     * Rebuilds display text from trimmed [words], for when they have been
     * regrouped (BatchSplit) and the original spacing is no longer at hand.
     *
     * A space goes between two words unless either side of the join is a
     * character from a script written without spaces (Han, kana, Thai, Lao,
     * Burmese, Khmer) or CJK punctuation.
     */
    fun joinWords(words: List<WordStamp>): String {
        val out = StringBuilder()
        for (word in words) {
            val text = word.text
            if (text.isEmpty()) continue
            if (out.isNotEmpty() &&
                !isUnspacedScript(Character.codePointBefore(out, out.length)) &&
                !isUnspacedScript(Character.codePointAt(text, 0))
            ) {
                out.append(' ')
            }
            out.append(text)
        }
        return out.toString()
    }

    private fun isUnspacedScript(codePoint: Int): Boolean {
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.MYANMAR,
            Character.UnicodeScript.KHMER -> return true
            else -> Unit
        }
        return when (Character.UnicodeBlock.of(codePoint)) {
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
            Character.UnicodeBlock.KATAKANA -> true
            else -> false
        }
    }
}
