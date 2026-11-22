package com.meetily.mobile

import com.meetily.mobile.diag.TombstoneText
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TombstoneTextTest {

    /** Just enough of a protobuf writer to build tombstones by field number. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        private fun varint(value: Long) {
            var v = value
            while (true) {
                if ((v and 0x7fL.inv()) == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7f) or 0x80).toInt())
                v = v ushr 7
            }
        }

        fun int(field: Int, value: Long) = apply {
            varint((field shl 3).toLong())
            varint(value)
        }

        fun bytes(field: Int, value: ByteArray) = apply {
            varint(((field shl 3) or 2).toLong())
            varint(value.size.toLong())
            out.write(value)
        }

        fun str(field: Int, value: String) = bytes(field, value.toByteArray())

        fun msg(field: Int, value: Proto) = bytes(field, value.build())

        fun build(): ByteArray = out.toByteArray()
    }

    private fun frame(relPc: Long, function: String, offset: Long, file: String, buildId: String) =
        Proto().int(1, relPc).int(2, relPc + 0x7000_0000L).int(3, 0x1234)
            .str(4, function).int(5, offset).str(6, file).str(8, buildId)

    private fun tombstone(): Proto {
        val crashing = Proto().int(1, 4242).str(2, "llm-worker")
            .msg(3, Proto().str(1, "reg_marker").int(2, 0))
            .msg(4, frame(0xabc, "llama_decode", 44, "/data/app/~~r4nd0m==/com.recap.mobile-x==/lib/arm64/libllama.so", "deadbeef"))
            .msg(4, frame(0x1f00, "", 0, "/apex/com.android.runtime/lib64/bionic/libc.so", "cafe"))
        val other = Proto().int(1, 7).str(2, "main")
            .msg(4, frame(0x10, "nativePollOnce", 8, "/system/lib64/libandroid_runtime.so", "01"))
        return Proto()
            .int(1, 1) // arm64
            .str(2, "google/device/build:16/fingerprint")
            .int(5, 4200)
            .int(6, 4242)
            .msg(
                10,
                Proto().int(1, 11).str(2, "SIGSEGV").int(3, 1).str(4, "SEGV_MAPERR")
                    .int(8, 1).int(9, 0)
            )
            .str(14, "")
            .msg(15, Proto().str(1, "null pointer dereference"))
            .msg(16, Proto().int(1, 7).msg(2, other))
            .msg(16, Proto().int(1, 4242).msg(2, crashing))
            .msg(17, Proto().int(1, 0x1000).str(7, "/data/user/0/com.recap.mobile/files/meetings/a.json"))
            .msg(
                18,
                Proto().str(1, "main")
                    .msg(2, Proto().str(5, "Recap").str(6, "transcript: the secret plan"))
            )
            .msg(19, Proto().int(1, 42).str(2, "/data/user/0/com.recap.mobile/files/audio/m.wav"))
    }

    @Test
    fun decodesSignalCauseAndTheCrashingThreadsBacktrace() {
        val text = TombstoneText.summarise(tombstone().build())
        assertNotNull(text)
        text!!
        assertTrue(text, text.contains("Arch: arm64"))
        assertTrue(text, text.contains("Signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0"))
        assertTrue(text, text.contains("Cause: null pointer dereference"))
        assertTrue(text, text.contains("Crashing thread: 4242 \"llm-worker\""))
        assertTrue(
            text,
            text.contains("#00 pc 0000000000000abc  libllama.so (llama_decode+44) (BuildId: deadbeef)")
        )
        assertTrue(text, text.contains("#01 pc 0000000000001f00  libc.so (BuildId: cafe)"))
        // Only the crashing thread, and none of the parts that can carry data.
        assertFalse(text, text.contains("nativePollOnce"))
        assertFalse(text, text.contains("secret"))
        assertFalse(text, text.contains("/data/"))
        assertFalse(text, text.contains("meetings"))
        assertFalse(text, text.contains("m.wav"))
        assertFalse(text, text.contains("reg_marker"))
        // An empty abort message is not printed.
        assertFalse(text, text.contains("Abort message"))
    }

    @Test
    fun printsTheAbortMessageAndNegativeSignalCodes() {
        val bytes = Proto()
            .int(6, 9)
            // int32 -6 (SI_TKILL) on the wire: ten-byte two's complement.
            .msg(10, Proto().int(1, 6).str(2, "SIGABRT").int(3, -6L).str(4, "SI_TKILL"))
            .str(14, "GGML_ASSERT(n_tokens > 0) failed")
            .build()
        val text = TombstoneText.summarise(bytes)!!
        assertTrue(text, text.contains("Signal 6 (SIGABRT), code -6 (SI_TKILL)"))
        assertTrue(text, text.contains("Abort message: 'GGML_ASSERT(n_tokens > 0) failed'"))
        assertTrue(text, text.contains("Crashing thread: 9 (not in the saved part of the tombstone)"))
        // Absent arch is proto3's zero, ARM32, with 32-bit pcs.
        assertTrue(text, text.contains("Arch: arm"))
    }

    @Test
    fun aCutCopyStillYieldsWhatItHolds() {
        val full = tombstone().build()
        for (cut in listOf(full.size - 1, full.size / 2, 40, 12)) {
            // Never throws, whatever the cut point.
            TombstoneText.summarise(full, cut)
        }
        val half = TombstoneText.summarise(full, full.size / 2)
        assertNotNull(half)
        assertTrue(half!!, half.contains("SIGSEGV"))
    }

    @Test
    fun garbageIsNotATombstone() {
        assertNull(TombstoneText.summarise(ByteArray(0)))
        assertNull(TombstoneText.summarise("not a protobuf at all\n".toByteArray()))
        for (seed in 0 until 50) {
            val random = java.util.Random(seed.toLong())
            val noise = ByteArray(200).also { random.nextBytes(it) }
            TombstoneText.summarise(noise)
        }
    }

    @Test
    fun longBacktracesAreCapped() {
        var thread = Proto().int(1, 1).str(2, "t")
        repeat(TombstoneText.MAX_FRAMES + 10) {
            thread = thread.msg(4, frame(it.toLong(), "f$it", 0, "lib.so", "b"))
        }
        val text = TombstoneText.summarise(
            Proto().int(1, 1).int(6, 1).msg(16, Proto().int(1, 1).msg(2, thread)).build()
        )!!
        assertTrue(text, text.contains("#63 pc"))
        assertFalse(text, text.contains("#64 pc"))
        assertTrue(text, text.contains("... 10 more frames"))
    }
}
