package com.meetily.mobile

import com.meetily.mobile.data.TarExtract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Taking the word re-timing model's files out of its tar archive. The
 * archive comes unpinned from the network, so nothing in it may write
 * outside the directory given, or anything but the files asked for.
 */
class TarExtractTest {

    private lateinit var root: File
    private lateinit var out: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tar-test").toFile()
        out = File(root, "out").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private val wanted = listOf(
        TarExtract.Wanted("meta.json", 1024),
        TarExtract.Wanted("lexicon.txt", 1024)
    )

    /** A tar writer for the tests: [entries] are (name, type, data). */
    private fun tar(vararg entries: Triple<String, Char, ByteArray>, posix: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, type, data) in entries) {
            out.write(header(name, data.size.toLong(), type, posix))
            out.write(data)
            val pad = (512 - data.size % 512) % 512
            out.write(ByteArray(pad))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun header(name: String, size: Long, type: Char, posix: Boolean): ByteArray {
        val h = ByteArray(512)
        fun put(offset: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            System.arraycopy(bytes, 0, h, offset, bytes.size)
        }
        put(0, name.take(100))
        put(100, "0000644\u0000")
        put(108, "0000000\u0000")
        put(116, "0000000\u0000")
        put(124, String.format("%011o", size) + "\u0000")
        put(136, "00000000000\u0000")
        h[156] = type.code.toByte()
        if (posix) {
            put(257, "ustar\u0000")
            put(263, "00")
        } else {
            put(257, "ustar  \u0000")
        }
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xff }
        put(148, String.format("%06o", sum) + "\u0000 ")
        return h
    }

    private fun file(name: String, text: String) = Triple(name, '0', text.toByteArray())
    private fun dir(name: String) = Triple(name, '5', ByteArray(0))

    private fun extract(bytes: ByteArray, cancelled: () -> Boolean = { false }): Set<String> =
        TarExtract.extract(ByteArrayInputStream(bytes), out, wanted, cancelled)

    private fun assertUnsafe(bytes: ByteArray) {
        try {
            extract(bytes)
            fail("accepted an unsafe archive")
        } catch (_: TarExtract.UnsafeArchiveException) {
        }
    }

    @Test
    fun takesTheWantedFilesFromTheArchivesFolder() {
        val bytes = tar(
            dir("model/"),
            file("model/meta.json", "{\"a\":1}"),
            file("model/en.wav", "RIFF....".repeat(100)),
            file("model/lexicon.txt", "ask æ s k\n")
        )
        assertEquals(setOf("meta.json", "lexicon.txt"), extract(bytes))
        assertEquals("{\"a\":1}", File(out, "meta.json").readText())
        assertEquals("ask æ s k\n", File(out, "lexicon.txt").readText())
        // Nothing else is written, here or beside it.
        assertEquals(setOf("meta.json", "lexicon.txt"), out.list()!!.toSet())
        assertEquals(setOf("out"), root.list()!!.toSet())
    }

    @Test
    fun takesFilesAtTheTopToo() {
        assertEquals(setOf("meta.json"), extract(tar(file("meta.json", "{}"))))
    }

    @Test
    fun leavesFilesDeeperDownAlone() {
        val bytes = tar(file("model/sub/meta.json", "{}"), file("./model/lexicon.txt", "x y"))
        assertEquals(setOf("lexicon.txt"), extract(bytes))
        assertFalse(File(out, "meta.json").exists())
    }

    @Test
    fun readsPosixPrefixesAndBlocksOfExactSize() {
        val exact = "x".repeat(512)
        val bytes = tar(file("model/lexicon.txt", exact), file("model/meta.json", "{}"), posix = true)
        assertEquals(setOf("lexicon.txt", "meta.json"), extract(bytes))
        assertEquals(exact, File(out, "lexicon.txt").readText())
    }

    @Test
    fun refusesParentDirectories() {
        assertUnsafe(tar(file("model/../meta.json", "{}")))
        assertUnsafe(tar(file("../lexicon.txt", "x")))
        // Refused even when it names nothing wanted.
        assertUnsafe(tar(file("model/../../etc/passwd", "x")))
    }

    @Test
    fun refusesAbsoluteAndWindowsPaths() {
        assertUnsafe(tar(file("/meta.json", "{}")))
        assertUnsafe(tar(file("model\\meta.json", "{}")))
        assertUnsafe(tar(file("C:meta.json", "{}")))
    }

    @Test
    fun refusesLinksAndDevices() {
        assertUnsafe(tar(Triple("model/meta.json", '2', ByteArray(0))))
        assertUnsafe(tar(Triple("model/meta.json", '1', ByteArray(0))))
        assertUnsafe(tar(Triple("model/dev", '3', ByteArray(0))))
        assertUnsafe(tar(Triple("model/sparse", 'S', ByteArray(0))))
    }

    @Test
    fun refusesAWantedFileTwice() {
        assertUnsafe(tar(file("a/meta.json", "{}"), file("b/meta.json", "{}")))
    }

    @Test
    fun refusesAFileOverItsCap() {
        assertUnsafe(tar(file("model/meta.json", "x".repeat(1025))))
    }

    @Test
    fun followsGnuLongNamesAndChecksThem() {
        val long = "model/" + "d".repeat(120) + "/../../lexicon.txt"
        assertUnsafe(tar(Triple("././@LongLink", 'L', long.toByteArray()), file("short", "x")))
        val fine = "m".repeat(150) + "/lexicon.txt"
        assertEquals(
            setOf("lexicon.txt"),
            extract(tar(Triple("././@LongLink", 'L', fine.toByteArray()), file("short", "x")))
        )
    }

    @Test
    fun followsPaxPathsAndChecksThem() {
        val evil = "21 path=../meta.json\n"
        assertUnsafe(tar(Triple("PaxHeader", 'x', evil.toByteArray()), file("harmless", "{}")))
        val renamed = "24 path=model/meta.json\n"
        assertEquals(
            setOf("meta.json"),
            extract(tar(Triple("PaxHeader", 'x', renamed.toByteArray()), file("other.bin", "{}")))
        )
        assertEquals("model/meta.json", TarExtract.paxRecord(renamed, "path"))
        assertNull(TarExtract.paxRecord("12 size=100\n", "path"))
    }

    @Test
    fun aDamagedHeaderFails() {
        val bytes = tar(file("model/meta.json", "{}"))
        bytes[10] = (bytes[10] + 1).toByte()
        try {
            extract(bytes)
            fail("checksum not checked")
        } catch (e: IOException) {
            assertFalse(e is TarExtract.UnsafeArchiveException)
        }
    }

    @Test
    fun aTruncatedArchiveFails() {
        val bytes = tar(file("model/lexicon.txt", "x".repeat(900)))
        for (cut in listOf(0, 100, 600, 1000)) {
            try {
                extract(bytes.copyOf(cut))
                fail("cut at $cut accepted")
            } catch (_: EOFException) {
            }
        }
    }

    @Test
    fun stopsWhenCancelled() {
        try {
            extract(tar(file("model/meta.json", "{}")), cancelled = { true })
            fail("not cancelled")
        } catch (_: InterruptedException) {
        }
    }

    @Test
    fun namesAreCheckedComponentByComponent() {
        assertEquals(listOf("model", "meta.json"), TarExtract.components("./model//meta.json"))
        assertEquals(listOf("model"), TarExtract.components("model/"))
        assertNull(TarExtract.components("model/../x"))
        assertNull(TarExtract.components("/x"))
        assertNull(TarExtract.components("a\\b"))
        assertNull(TarExtract.components("a\u0000b"))
        assertTrue(TarExtract.components("a..b/c")!!.contains("a..b"))
    }

    @Test
    fun readsGnuBinarySizes() {
        val h = ByteArray(512)
        h[124] = 0x80.toByte()
        h[133] = 0x01
        h[134] = 0x00
        h[135] = 0x10
        assertEquals(0x10010L, TarExtract.parseSize(h))
        val octal = ByteArray(512)
        "00000001212\u0000".toByteArray().copyInto(octal, 124)
        assertEquals(650L, TarExtract.parseSize(octal))
    }
}
