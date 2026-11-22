package com.meetily.mobile.diag

import java.util.Locale

/**
 * Decodes the parts of an Android tombstone a developer needs to place a
 * native crash: the signal, the abort message and stated causes, and the
 * crashing thread's backtrace with library names and build IDs.
 *
 * From API 31, ApplicationExitInfo.getTraceInputStream returns the tombstone
 * of a REASON_CRASH_NATIVE exit as a protobuf (AOSP
 * system/core/debuggerd/proto/tombstone.proto). The report Recap offers to
 * share is plain text, so the raw file stays on the phone and this summary
 * goes in the text instead. Registers, memory dumps, memory maps, open files
 * and the log buffers are left out on purpose: they can hold fragments of
 * whatever the process had in memory, and a backtrace plus build IDs is what
 * symbolication needs.
 *
 * A small hand-written reader rather than a protobuf dependency: it walks
 * the wire format, keeps only the fields named below, and stops quietly at
 * the first malformed or truncated field, since the saved copy is capped
 * and may end mid-message.
 */
object TombstoneText {

    const val MAX_FRAMES = 64
    private const val TEXT_CAP = 200
    private const val ABORT_CAP = 500

    // Tombstone
    private const val T_ARCH = 1
    private const val T_TID = 6
    private const val T_SIGNAL = 10
    private const val T_ABORT = 14
    private const val T_CAUSES = 15
    private const val T_THREADS = 16

    // Signal
    private const val S_NUMBER = 1
    private const val S_NAME = 2
    private const val S_CODE = 3
    private const val S_CODE_NAME = 4
    private const val S_HAS_FAULT = 8
    private const val S_FAULT_ADDR = 9

    // Thread
    private const val TH_ID = 1
    private const val TH_NAME = 2
    private const val TH_BACKTRACE = 4

    // BacktraceFrame
    private const val F_REL_PC = 1
    private const val F_FUNCTION = 4
    private const val F_FUNCTION_OFFSET = 5
    private const val F_FILE = 6
    private const val F_BUILD_ID = 8

    private val ARCH_NAMES = mapOf(0L to "arm", 1L to "arm64", 2L to "x86", 3L to "x86_64", 4L to "riscv64")

    /** One wire-format field; [start] until [end] is the payload of a length-delimited one. */
    private class Field(val number: Int, val wire: Int, val value: Long, val start: Int, val end: Int)

    /**
     * The summary for the first [length] bytes of [bytes], or null when
     * nothing recognisable is there.
     */
    fun summarise(bytes: ByteArray, length: Int = bytes.size): String? {
        val end = length.coerceIn(0, bytes.size)
        val top = fields(bytes, 0, end)
        if (top.isEmpty()) return null
        // proto3 leaves zero values out, so an absent arch is ARM32.
        var arch = 0L
        var tid = 0L
        var signal: Field? = null
        var abort: String? = null
        val causes = mutableListOf<String>()
        val threads = HashMap<Long, Field>()
        for (f in top) {
            when (f.number) {
                T_ARCH -> if (f.wire == 0) arch = f.value
                T_TID -> if (f.wire == 0) tid = f.value
                T_SIGNAL -> if (f.wire == 2) signal = f
                T_ABORT -> if (f.wire == 2) abort = text(bytes, f, ABORT_CAP)
                T_CAUSES -> if (f.wire == 2) {
                    fields(bytes, f.start, f.end)
                        .firstOrNull { it.number == 1 && it.wire == 2 }
                        ?.let { causes += text(bytes, it, TEXT_CAP) }
                }
                T_THREADS -> if (f.wire == 2) {
                    // A map entry: key 1 is the tid, value 2 the Thread.
                    val entry = fields(bytes, f.start, f.end)
                    val key = entry.firstOrNull { it.number == 1 && it.wire == 0 }?.value ?: 0L
                    entry.firstOrNull { it.number == 2 && it.wire == 2 }?.let { threads[key] = it }
                }
            }
        }
        if (signal == null && abort == null && threads.isEmpty()) return null
        val wide = arch != 0L && arch != 2L
        return buildString {
            append("Tombstone summary (registers, memory, maps, open files and logs left out)\n")
            append("Arch: ").append(ARCH_NAMES[arch] ?: "arch $arch").append('\n')
            signal?.let { append(signalLine(bytes, it)).append('\n') }
            causes.forEach { append("Cause: ").append(it).append('\n') }
            abort?.takeIf { it.isNotBlank() }?.let {
                append("Abort message: '").append(it).append("'\n")
            }
            val thread = threads[tid]
            if (thread == null) {
                append("Crashing thread: ").append(tid)
                append(" (not in the saved part of the tombstone)\n")
            } else {
                appendThread(this, bytes, thread, wide)
            }
        }
    }

    private fun signalLine(bytes: ByteArray, signal: Field): String {
        var number = 0L
        var name = ""
        var code = 0L
        var codeName = ""
        var hasFault = false
        var fault = 0L
        for (f in fields(bytes, signal.start, signal.end)) {
            when (f.number) {
                S_NUMBER -> if (f.wire == 0) number = f.value
                S_NAME -> if (f.wire == 2) name = text(bytes, f, TEXT_CAP)
                S_CODE -> if (f.wire == 0) code = f.value
                S_CODE_NAME -> if (f.wire == 2) codeName = text(bytes, f, TEXT_CAP)
                S_HAS_FAULT -> if (f.wire == 0) hasFault = f.value != 0L
                S_FAULT_ADDR -> if (f.wire == 0) fault = f.value
            }
        }
        return buildString {
            // int32 values arrive sign-extended to 64 bits; toInt restores them.
            append("Signal ").append(number.toInt())
            if (name.isNotEmpty()) append(" (").append(name).append(')')
            append(", code ").append(code.toInt())
            if (codeName.isNotEmpty()) append(" (").append(codeName).append(')')
            if (hasFault) append(", fault addr 0x").append(java.lang.Long.toHexString(fault))
        }
    }

    private fun appendThread(out: StringBuilder, bytes: ByteArray, thread: Field, wide: Boolean) {
        val parts = fields(bytes, thread.start, thread.end)
        val id = parts.firstOrNull { it.number == TH_ID && it.wire == 0 }?.value?.toInt() ?: 0
        val name = parts.firstOrNull { it.number == TH_NAME && it.wire == 2 }
            ?.let { text(bytes, it, TEXT_CAP) }.orEmpty()
        out.append("Crashing thread: ").append(id)
        if (name.isNotEmpty()) out.append(" \"").append(name).append('"')
        out.append('\n')
        val frames = parts.filter { it.number == TH_BACKTRACE && it.wire == 2 }
        if (frames.isEmpty()) {
            out.append("Backtrace: none recorded\n")
            return
        }
        out.append("Backtrace:\n")
        frames.take(MAX_FRAMES).forEachIndexed { index, frame ->
            out.append(frameLine(bytes, frame, index, wide)).append('\n')
        }
        if (frames.size > MAX_FRAMES) {
            out.append("    ... ").append(frames.size - MAX_FRAMES).append(" more frames\n")
        }
    }

    private fun frameLine(bytes: ByteArray, frame: Field, index: Int, wide: Boolean): String {
        var relPc = 0L
        var function = ""
        var offset = 0L
        var file = ""
        var buildId = ""
        for (f in fields(bytes, frame.start, frame.end)) {
            when (f.number) {
                F_REL_PC -> if (f.wire == 0) relPc = f.value
                F_FUNCTION -> if (f.wire == 2) function = text(bytes, f, TEXT_CAP)
                F_FUNCTION_OFFSET -> if (f.wire == 0) offset = f.value
                F_FILE -> if (f.wire == 2) file = text(bytes, f, TEXT_CAP)
                F_BUILD_ID -> if (f.wire == 2) buildId = text(bytes, f, TEXT_CAP)
            }
        }
        return buildString {
            append("    #").append(String.format(Locale.US, "%02d", index))
            append(" pc ").append(String.format(Locale.US, if (wide) "%016x" else "%08x", relPc))
            // The library name is what matters; the install path around it
            // is a random directory name.
            append("  ").append(file.substringAfterLast('/').ifEmpty { "<unknown>" })
            if (function.isNotEmpty()) {
                append(" (").append(function).append('+').append(offset).append(')')
            }
            if (buildId.isNotEmpty()) append(" (BuildId: ").append(buildId).append(')')
        }
    }

    private fun text(bytes: ByteArray, f: Field, cap: Int): String =
        String(bytes, f.start, f.end - f.start, Charsets.UTF_8)
            .filter { it >= ' ' || it == '\t' }
            .take(cap)

    /** The fields in bytes[from, to), up to the first malformed or truncated one. */
    private fun fields(bytes: ByteArray, from: Int, to: Int): List<Field> {
        val out = ArrayList<Field>()
        var pos = from
        while (pos < to) {
            val tag = varint(bytes, pos, to) ?: break
            pos = tag.second
            val number = (tag.first ushr 3).toInt()
            val wire = (tag.first and 7L).toInt()
            if (number <= 0) break
            when (wire) {
                0 -> {
                    val v = varint(bytes, pos, to) ?: break
                    out += Field(number, wire, v.first, v.second, v.second)
                    pos = v.second
                }
                1 -> {
                    if (to - pos < 8) break
                    out += Field(number, wire, fixed(bytes, pos, 8), pos, pos + 8)
                    pos += 8
                }
                2 -> {
                    val len = varint(bytes, pos, to) ?: break
                    val start = len.second
                    if (len.first < 0L || len.first > (to - start).toLong()) break
                    val stop = start + len.first.toInt()
                    out += Field(number, wire, len.first, start, stop)
                    pos = stop
                }
                5 -> {
                    if (to - pos < 4) break
                    out += Field(number, wire, fixed(bytes, pos, 4), pos, pos + 4)
                    pos += 4
                }
                else -> break
            }
        }
        return out
    }

    /** (value, position after it), or null when the varint runs off the end. */
    private fun varint(bytes: ByteArray, from: Int, to: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = from
        while (pos < to && shift < 64) {
            val b = bytes[pos].toInt() and 0xff
            pos++
            result = result or ((b and 0x7f).toLong() shl shift)
            if ((b and 0x80) == 0) return result to pos
            shift += 7
        }
        return null
    }

    private fun fixed(bytes: ByteArray, from: Int, size: Int): Long {
        var v = 0L
        for (i in size - 1 downTo 0) {
            v = (v shl 8) or (bytes[from + i].toLong() and 0xff)
        }
        return v
    }
}
