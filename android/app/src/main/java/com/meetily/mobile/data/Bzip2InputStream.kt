package com.meetily.mobile.data

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * A bzip2 decompressor, for the one optional model published only as a
 * .tar.bz2 (AlignerModels). Neither the platform nor the app's libraries
 * read bzip2, and a whole compression library for one download was not
 * worth its size and its own update stream.
 *
 * It follows the bzip2 1.0.x format exactly: stream header "BZh1".."BZh9",
 * blocks of Huffman-coded MTF/run-length symbols over a Burrows-Wheeler
 * transform, and the run-length coding of the original bytes. Every block's
 * CRC and the stream's combined CRC are checked, so damaged or truncated
 * input ends in an IOException, never in wrong bytes. Randomised blocks,
 * which no bzip2 since 0.9.5 writes, are refused. Concatenated streams are
 * read one after the other, as the bzip2 tool does.
 *
 * Memory: one block's transform, 4 bytes per input byte of the block (at
 * most 3.6 MB at level 9). Not thread-safe. Pure JVM; unit-tested.
 */
class Bzip2InputStream(input: InputStream) : InputStream() {

    private val source = CountingStream(input)

    /** Compressed bytes consumed so far, for progress. */
    val compressedBytesRead: Long get() = source.count

    private var bitBuffer = 0L
    private var bitCount = 0

    private var blockSize100k = 0
    private var tt = IntArray(0)

    // The current block's transform output.
    private var nblock = 0
    private var used = 0
    private var tPos = 0

    // Undoing the first run-length coding.
    private var lastByte = -1
    private var runLength = 0
    private var repeatLeft = 0
    private var repeatByte = 0

    private var inBlock = false
    private var storedBlockCrc = 0
    private var blockCrc = CRC_INIT
    private var combinedCrc = 0
    private var finished = false
    private var closed = false

    init {
        if (!readStreamHeader(first = true)) throw IOException("not a bzip2 stream")
    }

    override fun read(): Int {
        if (closed) throw IOException("stream closed")
        return nextByte()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("stream closed")
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            val c = nextByte()
            if (c < 0) break
            b[off + n] = c.toByte()
            n++
        }
        return if (n == 0) -1 else n
    }

    override fun close() {
        closed = true
        source.close()
    }

    /** The next decompressed byte, or -1 once every stream has ended. */
    private fun nextByte(): Int {
        while (true) {
            if (repeatLeft > 0) {
                repeatLeft--
                blockCrc = crcUpdate(blockCrc, repeatByte)
                return repeatByte
            }
            if (inBlock && used < nblock) {
                if (tPos !in 0 until nblock) throw IOException("bzip2 data is damaged")
                tPos = tt[tPos]
                val b = tPos and 0xff
                tPos = tPos ushr 8
                used++
                if (runLength == 4) {
                    // Four equal bytes, then how many more of them follow.
                    repeatLeft = b
                    repeatByte = lastByte
                    runLength = 0
                    lastByte = -1
                    continue
                }
                if (b == lastByte) {
                    runLength++
                } else {
                    lastByte = b
                    runLength = 1
                }
                blockCrc = crcUpdate(blockCrc, b)
                return b
            }
            if (inBlock) endBlock()
            if (finished || !startBlock()) return -1
        }
    }

    private fun endBlock() {
        inBlock = false
        val computed = blockCrc.inv()
        if (computed != storedBlockCrc) throw IOException("bzip2 block CRC mismatch")
        combinedCrc = ((combinedCrc shl 1) or (combinedCrc ushr 31)) xor computed
    }

    /**
     * Reads the next block, or the end of the stream (then the next stream,
     * if one follows). False when the input has ended.
     */
    private fun startBlock(): Boolean {
        while (true) {
            val magicHi = bits(24)
            val magicLo = bits(24)
            if (magicHi == 0x177245 && magicLo == 0x385090) {
                val stored = bits(32)
                if (stored != combinedCrc) throw IOException("bzip2 stream CRC mismatch")
                // Streams end on a byte boundary; another may follow.
                bitCount -= bitCount % 8
                if (!readStreamHeader(first = false)) {
                    finished = true
                    return false
                }
                continue
            }
            if (magicHi != 0x314159 || magicLo != 0x265359) throw IOException("bzip2 data is damaged")
            storedBlockCrc = bits(32)
            if (bit()) throw IOException("randomised bzip2 blocks are not supported")
            val origPtr = bits(24)
            decodeBlock(origPtr)
            return true
        }
    }

    /**
     * "BZh" and a level digit. At the start of the input it must be there;
     * after a stream, its absence (end of input, or anything else) ends the
     * data, as trailing bytes do for the bzip2 tool.
     */
    private fun readStreamHeader(first: Boolean): Boolean {
        val b = source.read()
        if (b != 'B'.code) return false
        val z = source.read()
        val h = source.read()
        val level = source.read()
        if (z != 'Z'.code || h != 'h'.code || level !in '1'.code..'9'.code) {
            if (first) return false
            throw IOException("bzip2 data is damaged")
        }
        blockSize100k = level - '0'.code
        val needed = blockSize100k * BLOCK_UNIT
        if (tt.size < needed) tt = IntArray(needed)
        combinedCrc = 0
        bitBuffer = 0L
        bitCount = 0
        return true
    }

    private fun decodeBlock(origPtr: Int) {
        // Which byte values occur.
        val inUse16 = bits(16)
        val seqToUnseq = IntArray(256)
        var numInUse = 0
        for (i in 0 until 16) {
            if (inUse16 and (0x8000 ushr i) != 0) {
                val bitsHere = bits(16)
                for (j in 0 until 16) {
                    if (bitsHere and (0x8000 ushr j) != 0) seqToUnseq[numInUse++] = i * 16 + j
                }
            }
        }
        if (numInUse == 0) throw IOException("bzip2 data is damaged")
        val alphaSize = numInUse + 2

        val nGroups = bits(3)
        if (nGroups !in 2..6) throw IOException("bzip2 data is damaged")
        var nSelectors = bits(15)
        if (nSelectors < 1) throw IOException("bzip2 data is damaged")
        val selectorMtf = ByteArray(MAX_SELECTORS)
        for (i in 0 until nSelectors) {
            var j = 0
            while (bit()) {
                j++
                if (j >= nGroups) throw IOException("bzip2 data is damaged")
            }
            // Encoders may write more than are ever used; bzip2 1.0.8 reads
            // and ignores them too.
            if (i < MAX_SELECTORS) selectorMtf[i] = j.toByte()
        }
        nSelectors = minOf(nSelectors, MAX_SELECTORS)
        val selectors = ByteArray(nSelectors)
        val pos = ByteArray(nGroups) { it.toByte() }
        for (i in 0 until nSelectors) {
            var v = selectorMtf[i].toInt()
            val tmp = pos[v]
            while (v > 0) {
                pos[v] = pos[v - 1]
                v--
            }
            pos[0] = tmp
            selectors[i] = tmp
        }

        // The code lengths, delta-coded, and the decoding tables.
        val limit = Array(nGroups) { IntArray(MAX_CODE_LEN + 2) }
        val base = Array(nGroups) { IntArray(MAX_CODE_LEN + 2) }
        val perm = Array(nGroups) { IntArray(MAX_ALPHA) }
        val minLens = IntArray(nGroups)
        val length = IntArray(alphaSize)
        for (t in 0 until nGroups) {
            var curr = bits(5)
            for (i in 0 until alphaSize) {
                while (true) {
                    if (curr < 1 || curr > 20) throw IOException("bzip2 data is damaged")
                    if (!bit()) break
                    curr += if (bit()) -1 else 1
                }
                length[i] = curr
            }
            var minLen = 32
            var maxLen = 0
            for (i in 0 until alphaSize) {
                maxLen = maxOf(maxLen, length[i])
                minLen = minOf(minLen, length[i])
            }
            createDecodeTables(limit[t], base[t], perm[t], length, minLen, maxLen, alphaSize)
            minLens[t] = minLen
        }

        // The symbols: runs of the front byte (RUNA, RUNB) and moves to front.
        val eob = numInUse + 1
        val nblockMax = blockSize100k * BLOCK_UNIT
        val unzftab = IntArray(256)
        val yy = IntArray(256) { it }
        var count = 0
        var groupNo = -1
        var groupPos = 0
        var table = 0

        fun nextSymbol(): Int {
            if (groupPos == 0) {
                groupNo++
                if (groupNo >= nSelectors) throw IOException("bzip2 data is damaged")
                groupPos = GROUP_SIZE
                table = selectors[groupNo].toInt()
            }
            groupPos--
            var zn = minLens[table]
            var zvec = bits(zn)
            val lim = limit[table]
            while (true) {
                if (zn > 20) throw IOException("bzip2 data is damaged")
                if (zvec <= lim[zn]) break
                zn++
                zvec = (zvec shl 1) or (if (bit()) 1 else 0)
            }
            val index = zvec - base[table][zn]
            if (index < 0 || index >= MAX_ALPHA) throw IOException("bzip2 data is damaged")
            return perm[table][index]
        }

        var sym = nextSymbol()
        while (sym != eob) {
            if (sym == RUNA || sym == RUNB) {
                var es = -1
                var n = 1
                do {
                    if (n >= 2 * 1024 * 1024) throw IOException("bzip2 data is damaged")
                    es += if (sym == RUNA) n else 2 * n
                    n *= 2
                    sym = nextSymbol()
                } while (sym == RUNA || sym == RUNB)
                es++
                val uc = seqToUnseq[yy[0]]
                if (count + es > nblockMax) throw IOException("bzip2 data is damaged")
                unzftab[uc] += es
                repeat(es) { tt[count++] = uc }
                continue
            }
            if (count >= nblockMax) throw IOException("bzip2 data is damaged")
            var nn = sym - 1
            if (nn < 0 || nn >= numInUse) throw IOException("bzip2 data is damaged")
            val front = yy[nn]
            while (nn > 0) {
                yy[nn] = yy[nn - 1]
                nn--
            }
            yy[0] = front
            val uc = seqToUnseq[front]
            unzftab[uc]++
            tt[count++] = uc
            sym = nextSymbol()
        }
        if (origPtr < 0 || origPtr >= count) throw IOException("bzip2 data is damaged")

        // The inverse transform, as links through tt.
        val cftab = IntArray(257)
        for (i in 1..256) cftab[i] = cftab[i - 1] + unzftab[i - 1]
        for (i in 0 until count) {
            val uc = tt[i] and 0xff
            tt[cftab[uc]] = tt[cftab[uc]] or (i shl 8)
            cftab[uc]++
        }
        nblock = count
        used = 0
        tPos = tt[origPtr] ushr 8
        lastByte = -1
        runLength = 0
        repeatLeft = 0
        blockCrc = CRC_INIT
        inBlock = true
    }

    private fun createDecodeTables(
        limit: IntArray,
        base: IntArray,
        perm: IntArray,
        length: IntArray,
        minLen: Int,
        maxLen: Int,
        alphaSize: Int
    ) {
        var pp = 0
        for (i in minLen..maxLen) {
            for (j in 0 until alphaSize) if (length[j] == i) perm[pp++] = j
        }
        base.fill(0)
        for (i in 0 until alphaSize) base[length[i] + 1]++
        for (i in 1 until base.size) base[i] += base[i - 1]
        limit.fill(0)
        var vec = 0
        for (i in minLen..maxLen) {
            vec += base[i + 1] - base[i]
            limit[i] = vec - 1
            vec = vec shl 1
        }
        for (i in minLen + 1..maxLen) base[i] = ((limit[i - 1] + 1) shl 1) - base[i]
    }

    private fun bit(): Boolean = bits(1) != 0

    /** The next [n] bits (1..32), most significant first. */
    private fun bits(n: Int): Int {
        while (bitCount < n) {
            val b = source.read()
            if (b < 0) throw EOFException("bzip2 data ends early")
            bitBuffer = (bitBuffer shl 8) or b.toLong()
            bitCount += 8
        }
        bitCount -= n
        return ((bitBuffer ushr bitCount) and ((1L shl n) - 1)).toInt()
    }

    /** Counts what is read from [input], buffered. */
    private class CountingStream(private val input: InputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var pos = 0
        private var end = 0
        var count = 0L
            private set

        fun read(): Int {
            if (pos == end) {
                end = input.read(buffer, 0, buffer.size)
                pos = 0
                if (end <= 0) {
                    end = 0
                    return -1
                }
            }
            count++
            return buffer[pos++].toInt() and 0xff
        }

        fun close() = input.close()
    }

    companion object {
        private const val BLOCK_UNIT = 100_000
        private const val MAX_SELECTORS = 18_002
        private const val MAX_ALPHA = 258
        private const val MAX_CODE_LEN = 23
        private const val GROUP_SIZE = 50
        private const val RUNA = 0
        private const val RUNB = 1
        private const val CRC_INIT = -1

        /** CRC-32 as bzip2 computes it: polynomial 0x04c11db7, most significant bit first. */
        private val CRC_TABLE = IntArray(256) { i ->
            var c = i shl 24
            repeat(8) { c = if (c and Int.MIN_VALUE != 0) (c shl 1) xor 0x04c11db7 else c shl 1 }
            c
        }

        private fun crcUpdate(crc: Int, b: Int): Int =
            (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor b) and 0xff]
    }
}
