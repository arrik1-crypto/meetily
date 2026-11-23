package com.meetily.mobile.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sums behind silencing a passage of a meeting's audio (PassageSilencer
 * does the decoding and encoding). Pure, so each is unit-tested:
 * - which frames go silent, and the short fades either side of them;
 * - the format the new file is written in: the recording's own rate,
 *   channels and a similar bitrate, as AAC-LC in ADTS frames, which is what
 *   live recordings are (MeetingAudioWriter);
 * - how far the encoder and decoder together delay the sound, measured on
 *   the phone itself, so the new file is written that much early and every
 *   time in the transcript still points at the same sound.
 */
object PassageAudio {

    /**
     * Each fade's length. The span itself is wholly silent; the fades sit
     * just outside it, so the sound either side does not stop or start with
     * a click. 15 ms is shorter than a syllable and longer than the few
     * milliseconds a click needs.
     */
    const val FADE_MS = 15

    /** The frame [ms] falls in, at [sampleRate]; [PassagePlan.END] stays the end. */
    fun frameAt(ms: Long, sampleRate: Int): Long =
        if (ms == PassagePlan.END) Long.MAX_VALUE else ms.coerceAtLeast(0L) * sampleRate / 1000L

    /** A fade's length in frames at [sampleRate]: at least one. */
    fun fadeFrames(sampleRate: Int, fadeMs: Int = FADE_MS): Int =
        (sampleRate.toLong() * fadeMs / 1000L).toInt().coerceAtLeast(1)

    /** Frames [startFrame, endFrame) go silent, with [fade] frames of fade outside each edge. */
    data class Silence(val startFrame: Long, val endFrame: Long, val fade: Int) {
        /** The frames whose level changes at all. */
        val firstTouched: Long get() = (startFrame - fade).coerceAtLeast(0L)
        val endTouched: Long get() = if (endFrame == Long.MAX_VALUE) Long.MAX_VALUE else endFrame + fade

        companion object {
            /** The silence for [startMs, endMs) at [sampleRate]. */
            fun of(startMs: Long, endMs: Long, sampleRate: Int, fadeMs: Int = FADE_MS): Silence =
                Silence(frameAt(startMs, sampleRate), frameAt(endMs, sampleRate), fadeFrames(sampleRate, fadeMs))
        }
    }

    /**
     * The level of frame [frame], from 0 (inside the span) to 1 (untouched):
     * a raised-cosine fade over [Silence.fade] frames down to the span's
     * start and up again after its end.
     */
    fun gain(frame: Long, silence: Silence): Float {
        if (silence.endFrame <= silence.startFrame) return 1f
        if (frame >= silence.startFrame && frame < silence.endFrame) return 0f
        val distance = if (frame < silence.startFrame) {
            silence.startFrame - frame
        } else {
            frame - silence.endFrame + 1
        }
        if (distance > silence.fade) return 1f
        val x = distance.toDouble() / (silence.fade + 1)
        return (0.5 - 0.5 * cos(PI * x)).toFloat()
    }

    /**
     * Applies [silence] to [frames] interleaved frames of [channels] in
     * [pcm], the first of which is frame [firstFrame] of the recording.
     * Returns true when any sample changed.
     */
    fun apply(pcm: ShortArray, frames: Int, channels: Int, firstFrame: Long, silence: Silence): Boolean {
        if (silence.endFrame <= silence.startFrame || frames <= 0) return false
        val lastFrame = firstFrame + frames
        if (lastFrame <= silence.firstTouched || firstFrame >= silence.endTouched) return false
        val from = (silence.firstTouched - firstFrame).coerceIn(0L, frames.toLong()).toInt()
        val until = if (silence.endTouched == Long.MAX_VALUE) {
            frames
        } else {
            (silence.endTouched - firstFrame).coerceIn(0L, frames.toLong()).toInt()
        }
        val ch = channels.coerceAtLeast(1)
        for (f in from until until) {
            val g = gain(firstFrame + f, silence)
            if (g >= 1f) continue
            for (c in 0 until ch) {
                val at = f * ch + c
                pcm[at] = if (g <= 0f) 0 else (pcm[at] * g).toInt().toShort()
            }
        }
        return true
    }

    // --- The new file's format ------------------------------------------------

    /** AAC sample rates in ADTS order: a rate's index is its sampling_frequency_index ([adtsFormat] reads it back). */
    private val ADTS_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /**
     * The rates the platform AAC-LC encoder takes on every phone (the
     * Android compatibility definition lists 8 to 48 kHz).
     */
    private val ENCODER_RATES = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    /** The live recording's format (MeetingAudioWriter), the fallback for anything else. */
    const val SPEECH_RATE = 16_000
    const val SPEECH_BIT_RATE = 64_000

    /** What the new file is written as, from what the recording decodes to. */
    data class Format(val sampleRate: Int, val channels: Int, val bitRate: Int) {
        /** The recording must be mixed down and resampled to this (its own rate or channels cannot be kept). */
        fun converts(sourceRate: Int, sourceChannels: Int): Boolean =
            sampleRate != sourceRate || channels != sourceChannels
    }

    /**
     * The format for a recording that decodes to [sampleRate] and
     * [channels], whose container states [sourceBitRate] (0 or less when it
     * does not). Its own rate and channels when the encoder takes them (one
     * or two channels, a rate from 8 to 48 kHz); otherwise mono 16 kHz,
     * the live recording's format, which is what transcription reads
     * anyway. The bitrate is the source's, kept within what AAC-LC can
     * carry at that rate, or the live recording's 64 kbps per channel
     * (96 above 24 kHz) when the source does not say, as for WAV or FLAC.
     */
    fun formatFor(sampleRate: Int, channels: Int, sourceBitRate: Int): Format {
        val keeps = sampleRate in ENCODER_RATES && channels in 1..2
        val rate = if (keeps) sampleRate else SPEECH_RATE
        val ch = if (keeps) channels else 1
        return Format(rate, ch, bitRateFor(sourceBitRate, rate, ch))
    }

    /**
     * Whether a run may write the recording, which decodes to [sampleRate]
     * and [channels], in the format [formatFor] gives: always when that
     * keeps its rate and channels, and otherwise only when the user saw the
     * confirmation say that the whole recording is mixed down to 16 kHz
     * mono ([allowConvert], see [reencodeOf]). The decoder has the last
     * word, since it can give other than the file's header says, so the run
     * asks this again of what it decodes.
     */
    fun mayWrite(sampleRate: Int, channels: Int, allowConvert: Boolean): Boolean =
        allowConvert || !formatFor(sampleRate, channels, -1).converts(sampleRate, channels)

    /** The MIME type of AAC (MediaFormat.MIMETYPE_AUDIO_AAC), which the new file is. */
    const val AAC_MIME = "audio/mp4a-latm"

    /** What writing the recording again does to it beyond the passage, for the confirmation. */
    enum class Reencode {
        /** AAC already, at a rate and channel count the encoder takes: written again as it was. */
        SAME_FORMAT,

        /** Another kind of audio (WAV, FLAC, MP3, Opus…): written again as AAC, at its own rate and channels. */
        OTHER_CODEC,

        /** A rate or channel count the encoder cannot take: the whole recording is mixed down to 16 kHz mono. */
        DOWNGRADED
    }

    /**
     * [Reencode] for a recording whose first audio track's header states
     * [mime], [sampleRate] and [channels]. A rate or channel count that is
     * not known counts as [Reencode.DOWNGRADED], so the confirmation says
     * the most that can happen.
     */
    fun reencodeOf(mime: String?, sampleRate: Int, channels: Int): Reencode = when {
        sampleRate <= 0 || channels <= 0 -> Reencode.DOWNGRADED
        !mayWrite(sampleRate, channels, allowConvert = false) -> Reencode.DOWNGRADED
        mime != AAC_MIME -> Reencode.OTHER_CODEC
        else -> Reencode.SAME_FORMAT
    }

    // --- What a raw ADTS recording's first header says ------------------------

    /**
     * How many bytes [adtsFormatOf] reads at a time: an ID3v2 tag's header,
     * the most the platform's AAC extractor looks at in one go when it
     * checks a file.
     */
    const val HEADER_BYTES = 10

    /**
     * The rates the platform's AAC extractor takes: the first twelve
     * sampling-frequency indexes, without 7350 Hz.
     */
    private const val EXTRACTOR_RATES = 12

    /** A raw ADTS file's rate and channel count, as its first frame header states them. */
    data class AdtsFormat(val sampleRate: Int, val channels: Int) {
        /** What writing the file again does ([reencodeOf]); it is AAC. */
        val reencode: Reencode get() = reencodeOf(AAC_MIME, sampleRate, channels)
    }

    /**
     * The length of the ID3v2 tag that [bytes] starts with (its 10-byte
     * header plus the size that header states), or 0 when [bytes] does not
     * start with one. Read as the platform's AAC extractor reads it.
     */
    fun id3TagBytes(bytes: ByteArray): Long {
        if (bytes.size < HEADER_BYTES) return 0L
        if (bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return 0L
        val size = ((bytes[6].toInt() and 0x7F) shl 21) or
            ((bytes[7].toInt() and 0x7F) shl 14) or
            ((bytes[8].toInt() and 0x7F) shl 7) or
            (bytes[9].toInt() and 0x7F)
        return HEADER_BYTES + size.toLong()
    }

    /**
     * The rate and channel count the ADTS frame header at the start of
     * [bytes] states, as the platform's AAC extractor reads them: the rate
     * of its sampling-frequency index, and its channel configuration taken
     * as the count. Null when [bytes] does not start with an ADTS sync word
     * or names a rate the extractor does not take.
     */
    fun adtsFormat(bytes: ByteArray): AdtsFormat? {
        if (bytes.size < 4) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        val b3 = bytes[3].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return null
        val index = (b2 shr 2) and 0xF
        if (index >= EXTRACTOR_RATES) return null
        return AdtsFormat(ADTS_RATES[index], ((b2 and 1) shl 2) or (b3 shr 6))
    }

    /**
     * The format of a raw ADTS file (every live recording, and every file a
     * passage run writes) from its first frame header, found as the
     * platform's AAC extractor finds it: past any ID3v2 tags. That
     * extractor also walks every frame header of the file before it says
     * anything, which takes seconds on a long meeting; this reads a few
     * bytes. [readAt] fills the array it is given from a file offset and
     * returns how many bytes it read. Null when the file is not raw ADTS
     * (only the extractor can say then) or names a rate it does not take.
     */
    fun adtsFormatOf(readAt: (offset: Long, into: ByteArray) -> Int): AdtsFormat? {
        val bytes = ByteArray(HEADER_BYTES)
        var offset = 0L
        while (true) {
            // The extractor wants this many bytes here even when they are a frame header.
            if (readAt(offset, bytes) < HEADER_BYTES) return null
            val tag = id3TagBytes(bytes)
            if (tag == 0L) return adtsFormat(bytes)
            offset += tag
        }
    }

    /**
     * A length a track format states in microseconds, in ms as
     * MediaMetadataRetriever gives it (rounded to the nearest); 0 for none.
     */
    fun durationMsOf(durationUs: Long): Long =
        if (durationUs <= 0L) 0L else (durationUs.coerceAtMost(Long.MAX_VALUE - 500L) + 500L) / 1000L

    /** See [formatFor]. */
    fun bitRateFor(sourceBitRate: Int, sampleRate: Int, channels: Int): Int {
        // AAC-LC carries at most 6144 bits per channel in a 1024-sample frame.
        val maxPerChannel = minOf(6 * sampleRate, 160_000)
        val minPerChannel = minOf(24_000, maxPerChannel / 2)
        val wanted = if (sourceBitRate > 0) {
            sourceBitRate
        } else {
            (if (sampleRate > 24_000) 96_000 else SPEECH_BIT_RATE) * channels
        }
        return wanted.coerceIn(minPerChannel * channels, maxPerChannel * channels)
    }

    /**
     * Roughly how many bytes [durationMs] of audio takes at [bitRate] (no
     * more than the highest [bitRateFor] gives), plus a little for the
     * frame headers. For the free-space check before a run. A recording
     * that states no bitrate is counted at that highest rate: the format it
     * decodes to is not known yet, and [bitRateFor] gives such a recording
     * up to 96 kbps per channel, three times the live recording's rate for
     * 48 kHz stereo. Too much asked is a refusal up front; too little is a
     * full disk part-way through.
     */
    fun estimatedBytes(durationMs: Long, bitRate: Int): Long {
        val rate = if (bitRate > 0) minOf(bitRate, MAX_BIT_RATE) else MAX_BIT_RATE
        val payload = durationMs.coerceAtLeast(0L) * rate / 8 / 1000
        return payload + payload / 50
    }

    /** The highest bitrate [bitRateFor] gives: two channels at 160 kbps. */
    const val MAX_BIT_RATE = 320_000

    /**
     * The 7-byte ADTS header for one AAC-LC frame of [payloadBytes] at
     * [sampleRate] with [channels]; null for a rate ADTS cannot name.
     */
    fun adtsHeader(payloadBytes: Int, sampleRate: Int, channels: Int): ByteArray? {
        val freqIndex = ADTS_RATES.indexOf(sampleRate)
        if (freqIndex < 0 || channels !in 1..7) return null
        val frameLength = payloadBytes + 7
        val profile = 2 // AAC-LC
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 0, no CRC
            (((profile - 1) shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or ((frameLength shr 11) and 0x3)).toByte(),
            ((frameLength shr 3) and 0xFF).toByte(),
            (((frameLength and 0x7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
    }

    // --- The codec's delay ----------------------------------------------------

    /** How long the silence before the probe sound lasts, in frames. */
    private const val PROBE_LEAD = 2048

    /** The longest delay looked for: four AAC frames and change. */
    const val MAX_DELAY_FRAMES = 4096 + 1024

    /** How alike the decoded probe must be to the one encoded for the delay to count. */
    const val MIN_MATCH = 0.5

    /**
     * The probe sound, mono at [sampleRate]: silence, then a 200 ms rising
     * chirp from 300 Hz to 3 kHz under a Hann window (AAC keeps a tone's
     * shape well, and a chirp matches itself at one lag only), then
     * silence long enough for the longest delay.
     */
    fun probe(sampleRate: Int): FloatArray {
        val chirp = sampleRate / 5
        val out = FloatArray(PROBE_LEAD + chirp + MAX_DELAY_FRAMES + 2048)
        val f0 = 300.0
        val f1 = 3000.0
        val seconds = chirp.toDouble() / sampleRate
        for (i in 0 until chirp) {
            val t = i.toDouble() / sampleRate
            val phase = 2 * PI * (f0 * t + (f1 - f0) * t * t / (2 * seconds))
            val window = sin(PI * i / chirp).let { it * it }
            out[PROBE_LEAD + i] = (0.5 * window * sin(phase)).toFloat()
        }
        return out
    }

    /**
     * The delay, in frames, between [sent] (as [probe] made it) and
     * [decoded] (the same sound after encoding and decoding): the lag at
     * which they match best, or null when no lag up to [maxDelay] matches
     * well enough ([MIN_MATCH], normalised correlation), which means the
     * codec did not give the sound back.
     */
    fun delayOf(sent: FloatArray, decoded: FloatArray, maxDelay: Int = MAX_DELAY_FRAMES): Int? {
        // Only the part with sound in it is compared.
        var from = 0
        while (from < sent.size && sent[from] == 0f) from++
        var until = sent.size
        while (until > from && sent[until - 1] == 0f) until--
        if (until <= from) return null
        var energy = 0.0
        for (i in from until until) energy += sent[i].toDouble() * sent[i]
        var best = Double.NEGATIVE_INFINITY
        var bestLag = -1
        for (lag in 0..maxDelay) {
            if (until + lag > decoded.size) break
            var dot = 0.0
            var power = 0.0
            for (i in from until until) {
                val d = decoded[i + lag].toDouble()
                dot += sent[i] * d
                power += d * d
            }
            if (power <= 0.0) continue
            val score = dot / sqrt(energy * power)
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        return bestLag.takeIf { it >= 0 && best >= MIN_MATCH }
    }
}
