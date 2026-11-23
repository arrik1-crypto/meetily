package com.meetily.mobile.data

/**
 * The rules behind a meeting's clear-voice copy: its audio with the
 * background noise taken out on the phone (whisper/SpeechEnhancer, through
 * ClearVoiceService), kept beside the recording for playback and clips. The
 * recording itself is never changed. Pure, so it is unit-tested.
 *
 * The copy is named after the recording it was made from, so every path
 * that deletes a recording finds the copy by name (AudioStore.delete), and
 * a copy made from an earlier recording of the meeting is recognised as
 * stale by [copyOf] rather than played in place of the new one.
 */
object ClearVoice {

    /** The bundled GTCRN speech-enhancement model (assets). */
    const val MODEL_ASSET = "gtcrn_simple.onnx"

    /** Its size, which the copy out of the APK is checked against. */
    const val MODEL_BYTES = 535_638L

    /**
     * Its SHA-256, checked once per process before sherpa-onnx loads it:
     * the loader ends the process on a model without the metadata it reads,
     * so a damaged copy must never reach it. SpeechEnhanceAssetTest pins the
     * bundled file to the same digest.
     */
    const val MODEL_SHA256 = "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534"

    /** GTCRN's rate: the decoder delivers 16 kHz mono, which is what it takes. */
    const val SAMPLE_RATE = 16_000

    /**
     * The copy's AAC bitrate: the live recording's (MeetingAudioWriter), so
     * the copy costs what the recording does, about 29 MB an hour.
     */
    const val BIT_RATE = 64_000

    /** Appended to the recording's name without its extension. */
    const val SUFFIX = ".clear.m4a"

    /** A copy still being written; renamed onto the copy's name once whole. */
    const val PART_SUFFIX = ".part"

    /**
     * Above this a copy asks first with the meeting's length and a battery
     * note (ClearVoiceService runs it as a foreground job either way).
     */
    const val LONG_MEETING_MS = 30 * 60_000L

    /** The copy's file name for the recording [audioName], in the same directory. */
    fun copyNameFor(audioName: String): String = audioName.substringBeforeLast('.') + SUFFIX

    /** Where the copy of [audioName] is written before it is complete. */
    fun partNameFor(audioName: String): String = copyNameFor(audioName) + PART_SUFFIX

    /**
     * True for a copy or a copy being written. Backups leave these out:
     * the copy is made again from the recording, which is backed up.
     */
    fun isCopyName(name: String): Boolean =
        name.endsWith(SUFFIX) || name.endsWith(SUFFIX + PART_SUFFIX)

    /**
     * The copy a meeting may play: [copyFile] when it was made from the
     * recording [audioFile] the meeting holds now, else null. A copy left
     * from a recording that was replaced, a meeting whose audio was removed,
     * or a name a restored backup carries that this rule would not produce,
     * all read as no copy.
     */
    fun copyOf(audioFile: String?, copyFile: String?): String? {
        if (audioFile.isNullOrBlank() || copyFile.isNullOrBlank()) return null
        return copyFile.takeIf { it == copyNameFor(audioFile) }
    }

    /**
     * Roughly how many bytes a copy of [durationMs] of audio takes: the
     * bitrate plus a little for the MP4 index. For the confirmation only.
     */
    fun estimatedBytes(durationMs: Long): Long {
        val payload = durationMs.coerceAtLeast(0L) * BIT_RATE / 8 / 1000
        return payload + payload / 50
    }

    /** True when a copy of [durationMs] of audio asks first (see [LONG_MEETING_MS]). */
    fun isLong(durationMs: Long): Boolean = durationMs >= LONG_MEETING_MS
}
