package com.meetily.mobile

import com.meetily.mobile.data.Bzip2InputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bzip2 reader behind the word re-timing model's download. The vectors
 * in src/test/resources/bzip2 were written by Python's bz2 module (the
 * bzip2 1.0.x library); the plain data is rebuilt here by the same
 * formulas. The model archive itself (english-mfa-v3.0.0.tar.bz2, 48.5 MB)
 * was checked byte for byte against bzip2 -dc when this was written.
 */
class Bzip2InputStreamTest {

    private fun vector(name: String): ByteArray {
        val candidates = listOf(
            "src/test/resources/bzip2/$name",
            "app/src/test/resources/bzip2/$name",
            "android/app/src/test/resources/bzip2/$name"
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: throw AssertionError("$name not found from ${File(".").absolutePath}")
        return file.readBytes()
    }

    private fun decode(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Bzip2InputStream(ByteArrayInputStream(bytes)).use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** Five 100k blocks at level 1. */
    private fun multi(): ByteArray = buildString {
        for (i in 0 until 9000) {
            append("line ${i % 97} of the meeting, speaker ${i % 7} said ${"z".repeat(i % 11)}\n")
        }
    }.toByteArray()

    /** Runs around the four-byte run-length coding: 4, 5, 259 and 260 equal bytes. */
    private fun runs(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(5000) { 'a'.code.toByte() })
        out.write("xyz".toByteArray())
        out.write(ByteArray(4) { 'b'.code.toByte() })
        out.write(ByteArray(5) { 'c'.code.toByte() })
        out.write(ByteArray(259) { 'd'.code.toByte() })
        out.write(ByteArray(260) { 'e'.code.toByte() })
        out.write(ByteArray(256) { it.toByte() })
        out.write("\n\n\n".toByteArray())
        return out.toByteArray()
    }

    /** Every byte value, hardly compressible. */
    private fun noise(): ByteArray {
        var x = 12345L
        return ByteArray(4000) {
            x = (x * 1103515245L + 12345L) and 0x7FFFFFFFL
            ((x shr 16) and 0xFF).toByte()
        }
    }

    @Test
    fun decodesSeveralBlocks() {
        assertArrayEquals(multi(), decode(vector("multi.bz2")))
    }

    @Test
    fun decodesRunsAtTheRunLengthEdges() {
        assertArrayEquals(runs(), decode(vector("runs.bz2")))
    }

    @Test
    fun decodesEveryByteValue() {
        assertArrayEquals(noise(), decode(vector("noise.bz2")))
    }

    @Test
    fun decodesAnEmptyStream() {
        assertEquals(0, decode(vector("empty.bz2")).size)
    }

    @Test
    fun readsConcatenatedStreamsInTurn() {
        val joined = vector("runs.bz2") + vector("empty.bz2") + vector("noise.bz2")
        assertArrayEquals(runs() + noise(), decode(joined))
    }

    @Test
    fun ignoresWhatFollowsTheLastStream() {
        // As the bzip2 tool does with trailing bytes that are not a stream.
        assertArrayEquals(runs(), decode(vector("runs.bz2") + "trailing".toByteArray()))
    }

    @Test
    fun singleByteReadsGiveTheSameBytes() {
        val expected = runs()
        val input = Bzip2InputStream(ByteArrayInputStream(vector("runs.bz2")))
        for (b in expected) assertEquals(b.toInt() and 0xff, input.read())
        assertEquals(-1, input.read())
        assertEquals(0, input.read(ByteArray(4), 0, 0))
        input.close()
    }

    @Test
    fun countsTheCompressedBytesRead() {
        val bytes = vector("multi.bz2")
        val input = Bzip2InputStream(ByteArrayInputStream(bytes))
        val buffer = ByteArray(8192)
        var last = input.compressedBytesRead
        var grew = false
        while (input.read(buffer) >= 0) {
            assertTrue(input.compressedBytesRead >= last)
            if (input.compressedBytesRead > last) grew = true
            last = input.compressedBytesRead
        }
        assertTrue(grew)
        assertEquals(bytes.size.toLong(), input.compressedBytesRead)
    }

    @Test
    fun refusesWhatIsNotBzip2() {
        try {
            Bzip2InputStream(ByteArrayInputStream("hello world".toByteArray()))
            fail("not a bzip2 stream")
        } catch (_: IOException) {
        }
        try {
            Bzip2InputStream(ByteArrayInputStream(ByteArray(0)))
            fail("empty input")
        } catch (_: IOException) {
        }
    }

    @Test
    fun truncatedInputFailsRatherThanEndingQuietly() {
        val bytes = vector("multi.bz2")
        for (cut in listOf(10, bytes.size / 3, bytes.size / 2, bytes.size - 5, bytes.size - 1)) {
            try {
                decode(bytes.copyOf(cut))
                fail("cut at $cut decoded")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun damagedInputFailsRatherThanGivingWrongBytes() {
        // Every block's CRC and the stream's are checked, so a changed byte
        // anywhere after the header ends in an IOException.
        val bytes = vector("multi.bz2")
        var position = 20
        var tried = 0
        while (position < bytes.size) {
            val damaged = bytes.copyOf()
            damaged[position] = (damaged[position].toInt() xor 0x10).toByte()
            try {
                val out = decode(damaged)
                fail("damage at $position decoded to ${out.size} bytes")
            } catch (_: IOException) {
            }
            tried++
            position += 487
        }
        assertTrue(tried > 20)
    }

    @Test
    fun refusesAReadAfterClose() {
        val input = Bzip2InputStream(ByteArrayInputStream(vector("runs.bz2")))
        input.close()
        try {
            input.read()
            fail("read after close")
        } catch (_: IOException) {
        }
    }
}
