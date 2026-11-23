package com.meetily.mobile

import com.meetily.mobile.data.PassageAudio
import com.meetily.mobile.data.PassageAudio.Silence
import com.meetily.mobile.data.PassagePlan
import com.meetily.mobile.data.PassageSwap
import com.meetily.mobile.data.PassageSwap.Facts
import com.meetily.mobile.data.PassageSwap.Leftover
import com.meetily.mobile.data.PassageSwap.Verdict
import com.meetily.mobile.data.SafeFiles
import com.meetily.mobile.whisper.PassageSilencer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Silencing a passage of the audio: the span goes wholly silent with short
 * fades just outside it, the new file keeps the recording's format and
 * length, the codec's delay is measured so nothing shifts, and the new file
 * only replaces the recording once it reads back whole.
 */
class PassageAudioTest {

    @get:Rule
    val folder = TemporaryFolder()

    // --- Fades --------------------------------------------------------------

    @Test
    fun framesAndFadesAtTheRecordingsRate() {
        assertEquals(16_000L, PassageAudio.frameAt(1_000, 16_000))
        assertEquals(48_000L * 3, PassageAudio.frameAt(3_000, 48_000))
        assertEquals(0L, PassageAudio.frameAt(-5, 16_000))
        assertEquals(Long.MAX_VALUE, PassageAudio.frameAt(PassagePlan.END, 16_000))
        // 15 ms: 240 frames at 16 kHz, 720 at 48 kHz, never none.
        assertEquals(240, PassageAudio.fadeFrames(16_000))
        assertEquals(720, PassageAudio.fadeFrames(48_000))
        assertEquals(1, PassageAudio.fadeFrames(10, 1))
        assertTrue(PassageAudio.FADE_MS in 10..20)
    }

    @Test
    fun theSpanIsSilentAndUntouchedFarFromIt() {
        val s = Silence(1_000, 2_000, 100)
        for (f in listOf(1_000L, 1_500L, 1_999L)) assertEquals(0f, PassageAudio.gain(f, s))
        for (f in listOf(0L, 899L, 2_100L, 5_000L)) assertEquals(1f, PassageAudio.gain(f, s))
    }

    @Test
    fun fadesRiseSmoothlyAwayFromBothEdges() {
        val s = Silence(1_000, 2_000, 100)
        var previous = 0f
        // Down to the start: level rises with distance before it.
        for (d in 1..100) {
            val g = PassageAudio.gain(1_000L - d, s)
            assertTrue("before start at $d: $g", g > previous && g < 1f)
            previous = g
        }
        previous = 0f
        for (d in 0 until 100) {
            val g = PassageAudio.gain(2_000L + d, s)
            assertTrue("after end at $d: $g", g > previous && g < 1f)
            previous = g
        }
        // The two fades mirror each other.
        for (d in 1..100) {
            assertEquals(PassageAudio.gain(1_000L - d, s), PassageAudio.gain(2_000L + d - 1, s), 1e-6f)
        }
        // Half way through a fade is half level.
        assertEquals(0.5f, PassageAudio.gain(1_000L - 50, Silence(1_000, 2_000, 99)), 1e-6f)
    }

    @Test
    fun aSpanAtTheStartOrToTheEndHasOneFade() {
        val atStart = Silence(0, 500, 50)
        assertEquals(0f, PassageAudio.gain(0, atStart))
        assertTrue(PassageAudio.gain(500, atStart) < 1f)
        val toEnd = Silence.of(1_000, PassagePlan.END, 16_000)
        assertEquals(Long.MAX_VALUE, toEnd.endFrame)
        assertEquals(0f, PassageAudio.gain(10_000_000L, toEnd))
        assertTrue(PassageAudio.gain(15_999, toEnd) < 1f)
        // An empty span touches nothing.
        assertEquals(1f, PassageAudio.gain(10, Silence(10, 10, 5)))
    }

    @Test
    fun applyingSilencesInterleavedChunksWhereverTheyFall() {
        val channels = 2
        val silence = Silence(10, 20, 4)
        // Three chunks of 12 frames each, all at full scale.
        val out = ShortArray(36 * channels)
        var at = 0L
        for (chunk in 0 until 3) {
            val pcm = ShortArray(12 * channels) { 10_000 }
            PassageAudio.apply(pcm, 12, channels, at, silence)
            pcm.copyInto(out, (at * channels).toInt())
            at += 12
        }
        for (frame in 0 until 36) {
            val g = PassageAudio.gain(frame.toLong(), silence)
            for (c in 0 until channels) {
                assertEquals("frame $frame ch $c", (10_000 * g).toInt().toShort(), out[frame * channels + c])
            }
        }
        // Both channels always alike, and the span itself all zero.
        for (frame in 10 until 20) assertEquals(0, out[frame * 2].toInt())
        assertEquals(10_000, out[5].toInt())
    }

    @Test
    fun chunksAwayFromTheSpanAreLeftAlone() {
        val silence = Silence(1_000, 2_000, 10)
        val pcm = ShortArray(100) { 7 }
        assertFalse(PassageAudio.apply(pcm, 100, 1, 0, silence))
        assertFalse(PassageAudio.apply(pcm, 100, 1, 2_010, silence))
        assertTrue(pcm.all { it.toInt() == 7 })
        assertTrue(PassageAudio.apply(pcm, 100, 1, 950, silence))
        assertEquals(0, pcm[99].toInt())
        // Only the frames given are touched.
        val partial = ShortArray(10) { 7 }
        PassageAudio.apply(partial, 5, 1, 1_000, silence)
        assertEquals(listOf(0, 0, 0, 0, 0, 7, 7, 7, 7, 7), partial.map { it.toInt() })
    }

    // --- Format ---------------------------------------------------------------

    @Test
    fun aLiveRecordingKeepsItsOwnFormat() {
        val f = PassageAudio.formatFor(16_000, 1, -1)
        assertEquals(PassageAudio.Format(16_000, 1, 64_000), f)
        assertFalse(f.converts(16_000, 1))
    }

    @Test
    fun anImportKeepsItsRateChannelsAndBitrate() {
        assertEquals(PassageAudio.Format(48_000, 2, 128_000), PassageAudio.formatFor(48_000, 2, 128_000))
        assertEquals(PassageAudio.Format(44_100, 1, 96_000), PassageAudio.formatFor(44_100, 1, 0))
        // Lossless sources say a huge bitrate; AAC caps it.
        assertEquals(320_000, PassageAudio.bitRateFor(1_411_200, 48_000, 2))
        // Very low bitrates are raised to something AAC-LC encodes well.
        assertEquals(24_000, PassageAudio.bitRateFor(8_000, 16_000, 1))
        // 8 kHz: AAC-LC carries at most 48 kbps per channel there.
        assertEquals(48_000, PassageAudio.bitRateFor(256_000, 8_000, 1))
        assertEquals(PassageAudio.Format(8_000, 1, 48_000), PassageAudio.formatFor(8_000, 1, 0))
    }

    @Test
    fun theSpaceCheckEstimatesTheNewFile() {
        // An hour of a live recording: 64 kbps, 28.8 MB, plus 2 %.
        assertEquals(28_800_000L + 576_000L, PassageAudio.estimatedBytes(3_600_000L, 64_000))
        // No stated bitrate: counted at the most the encoder can be given,
        // never below what bitRateFor gives such a recording (192 kbps for
        // 48 kHz stereo, three times the live recording's rate).
        assertEquals(144_000_000L + 2_880_000L, PassageAudio.estimatedBytes(3_600_000L, -1))
        assertEquals(
            PassageAudio.estimatedBytes(3_600_000L, PassageAudio.MAX_BIT_RATE),
            PassageAudio.estimatedBytes(3_600_000L, 0)
        )
        for (rate in listOf(8_000, 16_000, 44_100, 48_000)) {
            for (channels in 1..2) {
                val written = PassageAudio.bitRateFor(-1, rate, channels)
                assertTrue(
                    PassageAudio.estimatedBytes(3_600_000L, -1) >= PassageAudio.estimatedBytes(3_600_000L, written)
                )
            }
        }
        // A lossless source is written at no more than the highest AAC rate.
        assertEquals(
            PassageAudio.estimatedBytes(60_000L, PassageAudio.MAX_BIT_RATE),
            PassageAudio.estimatedBytes(60_000L, 1_411_200)
        )
        assertEquals(0L, PassageAudio.estimatedBytes(-5L, 64_000))
        // Never below what bitRateFor can give.
        assertEquals(PassageAudio.MAX_BIT_RATE, PassageAudio.bitRateFor(10_000_000, 48_000, 2))
    }

    @Test
    fun formatsTheEncoderCannotTakeBecomeSpeechFormat() {
        val surround = PassageAudio.formatFor(48_000, 6, 384_000)
        assertEquals(16_000, surround.sampleRate)
        assertEquals(1, surround.channels)
        assertTrue(surround.converts(48_000, 6))
        val odd = PassageAudio.formatFor(96_000, 2, 0)
        assertEquals(PassageAudio.Format(16_000, 1, 64_000), odd)
    }

    @Test
    fun aRunNeverMixesDownARecordingWithoutTheUsersAgreement() {
        // A 4-channel 96 kHz WAV, 5.1 sound from a video, hi-res stereo and
        // an odd rate: each would lose channels or rate for the whole
        // recording, so a run may write them only after the confirmation
        // said so.
        for ((rate, channels) in listOf(96_000 to 4, 48_000 to 6, 96_000 to 2, 88_200 to 2, 64_000 to 1, 7_350 to 1)) {
            assertFalse("$rate Hz × $channels", PassageAudio.mayWrite(rate, channels, allowConvert = false))
            assertTrue("$rate Hz × $channels", PassageAudio.mayWrite(rate, channels, allowConvert = true))
        }
        // Formats the encoder keeps as they are need no agreement.
        for ((rate, channels) in listOf(16_000 to 1, 44_100 to 2, 48_000 to 2, 8_000 to 1, 22_050 to 2)) {
            assertTrue("$rate Hz × $channels", PassageAudio.mayWrite(rate, channels, allowConvert = false))
        }
    }

    @Test
    fun theConfirmationSaysWhatWritingTheRecordingAgainDoes() {
        val aac = PassageAudio.AAC_MIME
        // A live recording or an AAC import: written again as it was.
        assertEquals(PassageAudio.Reencode.SAME_FORMAT, PassageAudio.reencodeOf(aac, 16_000, 1))
        assertEquals(PassageAudio.Reencode.SAME_FORMAT, PassageAudio.reencodeOf(aac, 44_100, 2))
        // A lossless or other import at a rate the encoder takes becomes AAC.
        assertEquals(PassageAudio.Reencode.OTHER_CODEC, PassageAudio.reencodeOf("audio/raw", 44_100, 2))
        assertEquals(PassageAudio.Reencode.OTHER_CODEC, PassageAudio.reencodeOf("audio/flac", 48_000, 1))
        assertEquals(PassageAudio.Reencode.OTHER_CODEC, PassageAudio.reencodeOf("audio/mpeg", 44_100, 2))
        assertEquals(PassageAudio.Reencode.OTHER_CODEC, PassageAudio.reencodeOf(null, 16_000, 1))
        // Mixed down to 16 kHz mono, whatever the codec.
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageAudio.reencodeOf("audio/raw", 96_000, 4))
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageAudio.reencodeOf(aac, 48_000, 6))
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageAudio.reencodeOf("audio/flac", 96_000, 2))
        // Not known: the most that can happen.
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageAudio.reencodeOf(aac, 0, 1))
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageAudio.reencodeOf(aac, 16_000, 0))
        // The confirmation warns of a mix-down exactly when a run would need the agreement.
        for (rate in listOf(7_350, 8_000, 16_000, 44_100, 48_000, 64_000, 88_200, 96_000)) {
            for (channels in 1..8) {
                assertEquals(
                    "$rate Hz × $channels",
                    !PassageAudio.mayWrite(rate, channels, allowConvert = false),
                    PassageAudio.reencodeOf(aac, rate, channels) == PassageAudio.Reencode.DOWNGRADED
                )
            }
        }
    }

    @Test
    fun adtsHeadersMatchTheLiveRecordingsAndNameTheRateAndChannels() {
        // What MeetingAudioWriter writes for a 100-byte frame at 16 kHz mono.
        val live = PassageAudio.adtsHeader(100, 16_000, 1)!!
        val frameLength = 107
        val expected = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((1 shl 6) or (8 shl 2) or 0).toByte(),
            ((1 shl 6) or ((frameLength shr 11) and 3)).toByte(),
            ((frameLength shr 3) and 0xFF).toByte(),
            (((frameLength and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
        assertArrayEquals(expected, live)
        val stereo = PassageAudio.adtsHeader(500, 48_000, 2)!!
        assertEquals(3, (stereo[2].toInt() shr 2) and 0xF) // 48 kHz
        assertEquals(2, ((stereo[2].toInt() and 1) shl 2) or ((stereo[3].toInt() shr 6) and 3))
        val length = ((stereo[3].toInt() and 3) shl 11) or ((stereo[4].toInt() and 0xFF) shl 3) or
            ((stereo[5].toInt() and 0xFF) shr 5)
        assertEquals(507, length)
        assertNull(PassageAudio.adtsHeader(100, 17_000, 1))
        assertNull(PassageAudio.adtsHeader(100, 16_000, 9))
    }

    /** A file of [bytes], read as PassageSilencer reads one; counts the reads. */
    private class FakeFile(val bytes: ByteArray) {
        var reads = 0
        var furthest = 0L

        fun readAt(offset: Long, into: ByteArray): Int {
            reads++
            if (offset >= bytes.size) return 0
            val n = minOf(into.size.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), into, 0, n)
            furthest = maxOf(furthest, offset + n)
            return n
        }
    }

    /** [frames] ADTS frames of [payload] bytes each, as MeetingAudioWriter or a passage run writes them. */
    private fun adtsFile(frames: Int, sampleRate: Int, channels: Int, payload: Int = 200): ByteArray {
        val header = PassageAudio.adtsHeader(payload, sampleRate, channels)!!
        val out = java.io.ByteArrayOutputStream()
        repeat(frames) {
            out.write(header)
            out.write(ByteArray(payload) { i -> (i * 7 + it).toByte() })
        }
        return out.toByteArray()
    }

    /** An ID3v2 tag of [body] bytes, its size in the header's four 7-bit bytes. */
    private fun id3(body: Int): ByteArray {
        val tag = ByteArray(10 + body)
        tag[0] = 'I'.code.toByte(); tag[1] = 'D'.code.toByte(); tag[2] = '3'.code.toByte()
        tag[3] = 4
        tag[6] = ((body shr 21) and 0x7F).toByte()
        tag[7] = ((body shr 14) and 0x7F).toByte()
        tag[8] = ((body shr 7) and 0x7F).toByte()
        tag[9] = (body and 0x7F).toByte()
        return tag
    }

    @Test
    fun aLiveRecordingsFormatComesFromItsFirstHeaderWithoutWalkingTheFile() {
        // An hour of live recording: 16 kHz mono AAC, 56,250 frames (small ones here).
        val hour = FakeFile(adtsFile(3600 * 16_000 / 1024, 16_000, 1, payload = 8))
        val format = PassageAudio.adtsFormatOf(hour::readAt)
        assertEquals(PassageAudio.AdtsFormat(16_000, 1), format)
        assertEquals(PassageAudio.Reencode.SAME_FORMAT, format!!.reencode)
        // One read of the first ten bytes, however long the recording.
        assertEquals(1, hour.reads)
        assertEquals(PassageAudio.HEADER_BYTES.toLong(), hour.furthest)
        // The exact bytes MeetingAudioWriter starts a recording with.
        val live = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x60, 0x40, 0x0D, 0xFF.toByte(), 0xFC.toByte(), 0, 0, 0)
        assertEquals(PassageAudio.AdtsFormat(16_000, 1), PassageAudio.adtsFormat(live))
    }

    @Test
    fun anAdtsHeaderIsReadAsThePlatformsAacExtractorReadsIt() {
        // What a passage run writes for an import it kept as it was.
        for ((rate, channels) in listOf(48_000 to 2, 44_100 to 2, 8_000 to 1, 22_050 to 1, 96_000 to 2)) {
            val file = FakeFile(adtsFile(3, rate, channels))
            val format = PassageAudio.adtsFormatOf(file::readAt)
            assertEquals("$rate Hz × $channels", PassageAudio.AdtsFormat(rate, channels), format)
            assertEquals(PassageAudio.reencodeOf(PassageAudio.AAC_MIME, rate, channels), format!!.reencode)
        }
        assertEquals(PassageAudio.Reencode.SAME_FORMAT, PassageAudio.AdtsFormat(48_000, 2).reencode)
        // 96 kHz, a 5.1 configuration (6) or none (0, a program config element): the mix-down warning.
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageAudio.AdtsFormat(96_000, 2).reencode)
        val surround = PassageAudio.adtsHeader(200, 48_000, 6)!! + ByteArray(3)
        assertEquals(PassageAudio.AdtsFormat(48_000, 6), PassageAudio.adtsFormat(surround))
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageAudio.adtsFormat(surround)!!.reencode)
        val noConfig = surround.copyOf().also { it[2] = (it[2].toInt() and 0xFE).toByte(); it[3] = (it[3].toInt() and 0x3F).toByte() }
        assertEquals(PassageAudio.AdtsFormat(48_000, 0), PassageAudio.adtsFormat(noConfig))
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageAudio.adtsFormat(noConfig)!!.reencode)
        // An MPEG-2 header (ID bit set, 0xF9) and one with a CRC (0xF0) are ADTS too.
        val mpeg2 = surround.copyOf().also { it[1] = 0xF9.toByte() }
        assertEquals(48_000, PassageAudio.adtsFormat(mpeg2)!!.sampleRate)
        val crc = surround.copyOf().also { it[1] = 0xF0.toByte() }
        assertEquals(48_000, PassageAudio.adtsFormat(crc)!!.sampleRate)
        // 7350 Hz and the reserved indexes: the extractor does not take the file, so neither does this.
        for (index in 12..15) {
            val odd = surround.copyOf().also { it[2] = ((it[2].toInt() and 0xC3) or (index shl 2)).toByte() }
            assertNull("index $index", PassageAudio.adtsFormat(odd))
        }
    }

    @Test
    fun anythingButRawAdtsIsLeftToTheExtractor() {
        fun formatOf(bytes: ByteArray) = PassageAudio.adtsFormatOf(FakeFile(bytes)::readAt)
        // MP3 frame sync (layer III, not ADTS's layer 0), an MP4's ftyp box, WAV, FLAC, Ogg.
        assertNull(formatOf(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(400)))
        assertNull(formatOf(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() + ByteArray(400)))
        assertNull(formatOf("RIFF".toByteArray() + ByteArray(4) + "WAVEfmt ".toByteArray() + ByteArray(400)))
        assertNull(formatOf("fLaC".toByteArray() + ByteArray(400)))
        assertNull(formatOf("OggS".toByteArray() + ByteArray(400)))
        // Empty, and shorter than the ten bytes the extractor looks at.
        assertNull(formatOf(ByteArray(0)))
        assertNull(formatOf(adtsFile(1, 16_000, 1).copyOf(9)))
        // An MP3 behind an ID3 tag is still an MP3.
        assertNull(formatOf(id3(300) + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(400)))
        // A tag that runs past the end of the file.
        assertNull(formatOf(id3(5000).copyOf(200)))
    }

    @Test
    fun id3TagsBeforeAnAdtsStreamAreSkippedAsTheExtractorSkipsThem() {
        assertEquals(10L + 300L, PassageAudio.id3TagBytes(id3(300)))
        // The size is four 7-bit bytes (syncsafe): 2^21 + 2^14 + 2^7 + 1.
        assertEquals(10L + 2_113_665L, PassageAudio.id3TagBytes(id3(2_113_665)))
        assertEquals(0L, PassageAudio.id3TagBytes(adtsFile(1, 16_000, 1)))
        assertEquals(0L, PassageAudio.id3TagBytes("ID3".toByteArray()))
        // One tag, then two in a row, then the stream.
        val stream = adtsFile(4, 44_100, 2)
        val one = FakeFile(id3(1000) + stream)
        assertEquals(PassageAudio.AdtsFormat(44_100, 2), PassageAudio.adtsFormatOf(one::readAt))
        assertEquals(2, one.reads)
        val two = FakeFile(id3(1000) + id3(0) + stream)
        assertEquals(PassageAudio.AdtsFormat(44_100, 2), PassageAudio.adtsFormatOf(two::readAt))
        assertEquals(3, two.reads)
        assertEquals(1010L + 10L + PassageAudio.HEADER_BYTES, two.furthest)
    }

    @Test
    fun thePickerReadsARecordingsFormatFromTheFileItself() {
        val live = folder.newFile("live.aac").apply { writeBytes(adtsFile(50, 16_000, 1)) }
        assertEquals(PassageAudio.Reencode.SAME_FORMAT, PassageSilencer.adtsReencode(live))
        val tagged = folder.newFile("tagged.aac").apply { writeBytes(id3(700) + adtsFile(5, 48_000, 6)) }
        assertEquals(PassageAudio.Reencode.DOWNGRADED, PassageSilencer.adtsReencode(tagged))
        // Not raw ADTS, too short or missing: the extractor must say (PassageSilencer.readHeader).
        val m4a = folder.newFile("import.m4a").apply {
            writeBytes(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray() + ByteArray(400))
        }
        assertNull(PassageSilencer.adtsReencode(m4a))
        assertNull(PassageSilencer.adtsReencode(folder.newFile("empty.aac")))
        assertNull(PassageSilencer.adtsReencode(java.io.File(folder.root, "gone.aac")))
    }

    @Test
    fun aStatedLengthIsRoundedAsMediaMetadataRetrieverRoundsIt() {
        // An hour of 16 kHz AAC frames as the AAC extractor states it: 56,250 frames of 64,000 µs.
        assertEquals(3_600_000L, PassageAudio.durationMsOf(56_250L * 64_000L))
        assertEquals(1L, PassageAudio.durationMsOf(500L))
        assertEquals(0L, PassageAudio.durationMsOf(499L))
        assertEquals(2L, PassageAudio.durationMsOf(1_500L))
        assertEquals(0L, PassageAudio.durationMsOf(0L))
        assertEquals(0L, PassageAudio.durationMsOf(-1L))
        assertEquals(Long.MAX_VALUE / 1000L, PassageAudio.durationMsOf(Long.MAX_VALUE))
    }

    // --- The codec's delay ------------------------------------------------------

    @Test
    fun theProbeHasSilenceThenASoundThenRoomForTheDelay() {
        val probe = PassageAudio.probe(16_000)
        assertEquals(0f, probe[0])
        assertTrue(probe.any { it != 0f })
        val lastSound = probe.indexOfLast { it != 0f }
        assertTrue(probe.size - lastSound > PassageAudio.MAX_DELAY_FRAMES)
        assertTrue(probe.all { kotlin.math.abs(it) <= 0.5f })
    }

    @Test
    fun theDelayIsFoundWhereTheSoundCameBack() {
        for (rate in listOf(8_000, 16_000, 48_000)) {
            val probe = PassageAudio.probe(rate)
            for (delay in listOf(0, 1, 1024, 2048, 2112, 4000)) {
                val back = FloatArray(probe.size + delay + 1024)
                // Quieter, with a little hiss, the way a codec gives it back.
                var seed = 12345
                for (i in probe.indices) back[i + delay] = probe[i] * 0.8f
                for (i in back.indices) {
                    seed = seed * 1103515245 + 12345
                    back[i] += ((seed ushr 16) and 0x7FFF) / 32768f * 0.01f - 0.005f
                }
                assertEquals("rate $rate delay $delay", delay, PassageAudio.delayOf(probe, back))
            }
        }
    }

    @Test
    fun noDelayIsClaimedWhenTheSoundDidNotComeBack() {
        val probe = PassageAudio.probe(16_000)
        assertNull(PassageAudio.delayOf(probe, FloatArray(probe.size + 8_000)))
        var seed = 7
        val noise = FloatArray(probe.size + 8_000) {
            seed = seed * 1103515245 + 12345
            ((seed ushr 16) and 0x7FFF) / 32768f - 0.5f
        }
        assertNull(PassageAudio.delayOf(probe, noise))
        // Too short to hold the sound at any delay.
        assertNull(PassageAudio.delayOf(probe, FloatArray(100)))
    }

    // --- The swap -----------------------------------------------------------------

    @Test
    fun theNewFileNeverTakesTheRecordingsName() {
        assertEquals("m1-edit1.aac", PassageSwap.newAudioName("m1.aac"))
        assertEquals("m1-edit2.aac", PassageSwap.newAudioName("m1-edit1.aac"))
        assertEquals("m1-edit10.aac", PassageSwap.newAudioName("m1-edit9.aac"))
        assertEquals("m1-edit1.aac", PassageSwap.newAudioName("m1.mp3"))
        assertEquals("m1-edit1.aac", PassageSwap.newAudioName("m1"))
        // A meeting id with a hyphen is not an edit number.
        assertEquals("a-b-c-edit1.aac", PassageSwap.newAudioName("a-b-c.m4a"))
        assertEquals("x-editor-edit1.aac", PassageSwap.newAudioName("x-editor.wav"))
        for (name in listOf("m1.aac", "m1-edit1.aac", "m1-edit1.m4a", "m1", "m1-edit0.aac")) {
            val next = PassageSwap.newAudioName(name)
            assertNotEquals(name, next)
            assertTrue(SafeFiles.isPlainName(next))
        }
        // The clear-voice copy of the new file is not the old one's.
        assertNotEquals(
            com.meetily.mobile.data.ClearVoice.copyNameFor("m1.aac"),
            com.meetily.mobile.data.ClearVoice.copyNameFor(PassageSwap.newAudioName("m1.aac"))
        )
    }

    @Test
    fun workFilesAreNamedAfterTheMeeting() {
        assertEquals("m1.part", PassageSwap.partName("m1"))
        assertEquals("m1.swap", PassageSwap.markerName("m1"))
        assertEquals("m1", PassageSwap.meetingOfMarker("m1.swap"))
        assertNull(PassageSwap.meetingOfMarker("m1.part"))
        assertNull(PassageSwap.meetingOfMarker(".swap"))
    }

    @Test
    fun aMarkerNamesTwoPlainFilesOrNothing() {
        val text = PassageSwap.markerText("m1.aac", "m1-edit1.aac")
        assertEquals("m1.aac" to "m1-edit1.aac", PassageSwap.markerNames(text))
        // Unreadable, incomplete, the same file twice, or anything outside
        // the audio folder: only ever deleted, never followed.
        assertNull(PassageSwap.markerNames("not json"))
        assertNull(PassageSwap.markerNames("{\"old\":\"m1.aac\"}"))
        assertNull(PassageSwap.markerNames(PassageSwap.markerText("m1.aac", "m1.aac")))
        assertNull(PassageSwap.markerNames(PassageSwap.markerText("../m1.aac", "m1-edit1.aac")))
        assertNull(PassageSwap.markerNames(PassageSwap.markerText("m1.aac", "a/m1-edit1.aac")))
    }

    @Test
    fun theNewFileMustReadBackAsTheSameAudio() {
        val ok = Facts("audio/mp4a-latm", 16_000, 1, 60_064)
        assertEquals(Verdict.OK, PassageSwap.verify(ok, 60_000, 16_000, 1))
        assertEquals(Verdict.NOT_AUDIO, PassageSwap.verify(null, 60_000, 16_000, 1))
        assertEquals(Verdict.NOT_AUDIO, PassageSwap.verify(ok.copy(mime = "audio/mpeg"), 60_000, 16_000, 1))
        assertEquals(Verdict.WRONG_FORMAT, PassageSwap.verify(ok.copy(sampleRate = 8_000), 60_000, 16_000, 1))
        assertEquals(Verdict.WRONG_FORMAT, PassageSwap.verify(ok.copy(channels = 2), 60_000, 16_000, 1))
        assertEquals(Verdict.WRONG_LENGTH, PassageSwap.verify(ok.copy(durationMs = 59_000), 60_000, 16_000, 1))
        assertEquals(Verdict.WRONG_LENGTH, PassageSwap.verify(ok.copy(durationMs = 61_000), 60_000, 16_000, 1))
        // No stated length: the format decides.
        assertEquals(Verdict.OK, PassageSwap.verify(ok.copy(durationMs = -1), 60_000, 16_000, 1))
        // Three frames and 50 ms either way.
        assertEquals(242L, PassageSwap.toleranceMs(16_000))
        assertEquals(Verdict.OK, PassageSwap.verify(ok.copy(durationMs = 60_242), 60_000, 16_000, 1))
        assertEquals(Verdict.WRONG_LENGTH, PassageSwap.verify(ok.copy(durationMs = 60_243), 60_000, 16_000, 1))
    }

    @Test
    fun theSwapHappensOnlyOverTheRecordingTheRunRead() {
        assertTrue(PassageSwap.maySwap(true, "m1.aac", "m1.aac"))
        assertFalse(PassageSwap.maySwap(false, "m1.aac", "m1.aac"))
        assertFalse(PassageSwap.maySwap(true, null, "m1.aac"))
        assertFalse(PassageSwap.maySwap(true, "m1-edit1.aac", "m1.aac"))
    }

    @Test
    fun anUnfinishedSwapIsFinishedOrUndoneFromWhatTheMeetingPointsAt() {
        val old = "m1.aac"
        val new = "m1-edit1.aac"
        assertEquals(Leftover.DELETE_NEW, PassageSwap.leftover(true, old, old, new))
        assertEquals(Leftover.DELETE_OLD, PassageSwap.leftover(true, new, old, new))
        assertEquals(Leftover.DELETE_BOTH, PassageSwap.leftover(true, null, old, new))
        assertEquals(Leftover.DELETE_BOTH, PassageSwap.leftover(true, "other.aac", old, new))
        // Not in the library: it may be in Recently deleted, pointing at either.
        assertEquals(Leftover.KEEP_BOTH, PassageSwap.leftover(false, null, old, new))
    }
}
