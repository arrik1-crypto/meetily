package com.meetily.mobile.data

import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Takes a fixed set of files out of a tar stream, for the one optional
 * model published only as a .tar.bz2 (AlignerModels). Neither the platform
 * nor the app's libraries read tar.
 *
 * The archive is untrusted: it is downloaded with no pinned hash. So no
 * name from the archive is ever used as a path. Each file written is one of
 * the caller's [Wanted] names, chosen by comparing an entry's last path
 * component with them, and written into the caller's own directory under
 * that name. On top of that, an archive is refused as a whole when any
 * entry names an absolute path, a parent directory ("..") or a backslash,
 * is a link or a device, a wanted file appears twice or above its size
 * cap, an entry it does not want is above [MAX_OTHER_BYTES], or a header's
 * checksum is wrong; files it already wrote are then the caller's to
 * delete (it extracts into a fresh directory). Entries it does not want
 * are read past, not written, and a cancel is heard while they are.
 *
 * Reads POSIX ustar, GNU (long names) and pax (path records) headers.
 * Pure JVM; unit-tested.
 */
object TarExtract {

    /** The archive is not one this reads safely; nothing in it is to be trusted. */
    class UnsafeArchiveException(message: String) : IOException(message)

    /**
     * A wanted file could not be written: no space left, say, which a
     * write, its sync or the file's creation reports. The archive is not at
     * fault, so a caller keeps it for another try. Its message is the
     * cause's, so what the user is told does not change.
     */
    class WriteFailedException(name: String, cause: IOException) :
        IOException(cause.message ?: "Could not write $name", cause)

    /** Runs [block], one step of writing [name]; its IOException becomes a [WriteFailedException]. */
    private inline fun <T> writing(name: String, block: () -> T): T =
        try {
            block()
        } catch (e: WriteFailedException) {
            throw e
        } catch (e: IOException) {
            throw WriteFailedException(name, e)
        }

    /** A file to take out: entries whose last path component is [name], at most [maxBytes]. */
    data class Wanted(val name: String, val maxBytes: Long)

    private const val BLOCK = 512

    /** Long names and pax records beyond this are refused rather than read. */
    private const val MAX_META_BYTES = 64 * 1024

    /**
     * An entry not asked for (a directory, a test recording) above this is
     * refused rather than read past: decompressing it would keep the
     * caller busy for nothing, and no archive this reads needs one.
     */
    const val MAX_OTHER_BYTES = 64L * 1024 * 1024

    /** How much is read at a time, and so how often a cancel is heard. */
    private const val CHUNK = 64 * 1024

    /**
     * The path components of entry name [path] with "." and empty ones
     * dropped, or null when it is unsafe: absolute, with a ".." component,
     * a backslash, a NUL or a drive letter.
     */
    fun components(path: String): List<String>? {
        if (path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return null
        if (path.length >= 2 && path[1] == ':') return null
        val out = mutableListOf<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> return null
                else -> out.add(part)
            }
        }
        return out
    }

    /**
     * Reads the tar stream [input] to its end, writing each [wanted] file
     * found into [outDir] under its wanted name. Wanted files may sit at
     * the top or one directory down (an archive's own folder); anything
     * else is read past. [cancelled] is asked before every header and
     * every 64 KB of an entry, written or read past: true stops with
     * InterruptedException. [onEntryBytes] hears the data bytes read so
     * far. Returns the names written.
     */
    fun extract(
        input: InputStream,
        outDir: File,
        wanted: List<Wanted>,
        cancelled: () -> Boolean = { false },
        onEntryBytes: (Long) -> Unit = {}
    ): Set<String> {
        val byName = wanted.associateBy { it.name }
        val written = LinkedHashSet<String>()
        val header = ByteArray(BLOCK)
        val buffer = ByteArray(CHUNK)
        var longName: String? = null
        var paxPath: String? = null
        var dataBytes = 0L
        while (true) {
            if (cancelled()) throw InterruptedException("Extraction cancelled")
            if (!readBlock(input, header)) throw EOFException("tar archive ends early")
            if (header.all { it == 0.toByte() }) {
                // The end: one zero block, normally two. Whatever follows is padding.
                return written
            }
            checkChecksum(header)
            val type = header[156].toInt().toChar()
            val size = parseSize(header)
            val name = paxPath ?: longName ?: headerName(header)
            when (type) {
                // A GNU long name, or a pax record set, for the entry after it.
                'L' -> {
                    longName = readMeta(input, size, buffer, cancelled).trimEnd('\u0000')
                    continue
                }
                'x' -> {
                    paxPath = paxRecord(readMeta(input, size, buffer, cancelled), "path") ?: paxPath
                    continue
                }
                // Global pax records and GNU long link names change no path read here.
                'g', 'K' -> {
                    if (size > MAX_META_BYTES) throw UnsafeArchiveException("tar header record too large")
                    skip(input, padded(size), buffer, cancelled)
                    continue
                }
            }
            longName = null
            paxPath = null
            val parts = components(name)
                ?: throw UnsafeArchiveException("tar entry with an unsafe name")
            when (type) {
                '0', '\u0000', '7' -> Unit
                '5' -> {
                    if (size > MAX_OTHER_BYTES) throw UnsafeArchiveException("tar directory entry larger than expected")
                    skip(input, padded(size), buffer, cancelled)
                    continue
                }
                else -> throw UnsafeArchiveException("tar entry of type '$type' is not a plain file")
            }
            val want = parts.lastOrNull()?.takeIf { parts.size <= 2 }?.let { byName[it] }
            if (want == null) {
                if (size > MAX_OTHER_BYTES) throw UnsafeArchiveException("tar entry larger than expected")
                skip(input, padded(size), buffer, cancelled)
                dataBytes += size
                onEntryBytes(dataBytes)
                continue
            }
            if (want.name in written) throw UnsafeArchiveException("${want.name} appears twice")
            if (size > want.maxBytes) throw UnsafeArchiveException("${want.name} is larger than expected")
            // Only the writing side is wrapped: a read that fails is the
            // archive's own damage, and is reported as such.
            writing(want.name) { FileOutputStream(File(outDir, want.name)) }.use { output ->
                var left = size
                while (left > 0L) {
                    if (cancelled()) throw InterruptedException("Extraction cancelled")
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException("tar archive ends inside ${want.name}")
                    writing(want.name) { output.write(buffer, 0, n) }
                    left -= n
                    dataBytes += n
                    onEntryBytes(dataBytes)
                }
                writing(want.name) { output.fd.sync() }
            }
            written.add(want.name)
            skip(input, padded(size) - size, buffer, cancelled)
        }
    }

    private fun padded(size: Long): Long = (size + BLOCK - 1) / BLOCK * BLOCK

    /** One whole block; false at a clean end of input before it. */
    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < block.size) {
            val n = input.read(block, read, block.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("tar archive ends inside a header")
            }
            read += n
        }
        return true
    }

    /** Reads past [count] bytes into [scratch], asking [cancelled] before every read. */
    private fun skip(input: InputStream, count: Long, scratch: ByteArray, cancelled: () -> Boolean) {
        var left = count
        while (left > 0L) {
            if (cancelled()) throw InterruptedException("Extraction cancelled")
            val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("tar archive ends inside an entry")
            left -= n
        }
    }

    private fun readMeta(input: InputStream, size: Long, scratch: ByteArray, cancelled: () -> Boolean): String {
        if (size < 0L || size > MAX_META_BYTES) throw UnsafeArchiveException("tar header record too large")
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) throw EOFException("tar archive ends inside a header record")
            read += n
        }
        skip(input, padded(size) - size, scratch, cancelled)
        return String(bytes, Charsets.UTF_8)
    }

    /** The value of [key] in pax records "<length> <key>=<value>\n", the last one winning. */
    internal fun paxRecord(records: String, key: String): String? {
        var found: String? = null
        for (line in records.split('\n')) {
            val space = line.indexOf(' ')
            if (space < 0) continue
            val record = line.substring(space + 1)
            val eq = record.indexOf('=')
            if (eq > 0 && record.substring(0, eq) == key) found = record.substring(eq + 1)
        }
        return found
    }

    /** The name field, with the ustar prefix when the header is POSIX ustar. */
    private fun headerName(header: ByteArray): String {
        val name = field(header, 0, 100)
        val posix = String(header, 257, 6, Charsets.ISO_8859_1) == "ustar\u0000"
        val prefix = if (posix) field(header, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end] != 0.toByte()) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    /** The size field: octal digits, or GNU's base-256 for large files. */
    internal fun parseSize(header: ByteArray): Long {
        val first = header[124].toInt() and 0xff
        if (first and 0x80 != 0) {
            if (first != 0x80) throw UnsafeArchiveException("negative tar entry size")
            var value = 0L
            for (i in 125 until 136) {
                if (value ushr 55 != 0L) throw UnsafeArchiveException("tar entry size out of range")
                value = (value shl 8) or (header[i].toLong() and 0xff)
            }
            return value
        }
        var value = 0L
        var digits = 0
        for (i in 124 until 136) {
            val c = header[i].toInt().toChar()
            when (c) {
                in '0'..'7' -> {
                    value = value * 8 + (c - '0')
                    digits++
                }
                ' ', '\u0000' -> if (digits > 0) break
                else -> throw IOException("tar header is damaged")
            }
        }
        return value
    }

    /** The header checksum: the sum of its bytes, with the checksum field read as spaces. */
    private fun checkChecksum(header: ByteArray) {
        var stored = 0L
        var digits = 0
        for (i in 148 until 156) {
            val c = header[i].toInt().toChar()
            when (c) {
                in '0'..'7' -> {
                    stored = stored * 8 + (c - '0')
                    digits++
                }
                ' ', '\u0000' -> if (digits > 0) break
                else -> throw IOException("tar header is damaged")
            }
        }
        var sum = 0L
        for (i in header.indices) {
            sum += if (i in 148 until 156) ' '.code else header[i].toInt() and 0xff
        }
        if (digits == 0 || sum != stored) throw IOException("tar header checksum mismatch")
    }
}
