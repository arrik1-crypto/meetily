package com.meetily.mobile.security

import java.io.IOException
import java.security.MessageDigest

/**
 * Integrity checks for downloaded model files, applied before a download is
 * renamed into place.
 *
 * Every model file ends up parsed by native code (llama.cpp, whisper.cpp,
 * onnxruntime) inside the same process that can read every meeting, and the
 * catalogues fetch them from mutable upstream branches. A size heuristic
 * alone accepts a truncated file today and a replaced one tomorrow. So the
 * download is hashed as it streams (no second pass over a multi-GB file),
 * checked against the server's declared length, and — for any catalogue
 * entry that carries one — compared against a pinned SHA-256.
 *
 * Pure JVM, no Android — unit-tested.
 */
object ModelIntegrity {

    class IntegrityException(message: String) : IOException(message)

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    /**
     * Throws [IntegrityException] unless the download is complete and, when
     * [expectedSha256] is known, byte-for-byte the pinned file.
     *
     * [contentLength] is the server's declared length, or a value <= 0 when
     * it sent none (chunked); only a declared length can be checked.
     */
    fun verify(
        label: String,
        received: Long,
        contentLength: Long,
        expectedSha256: String?,
        digest: MessageDigest
    ) {
        if (received <= 0L) {
            throw IntegrityException("Download of $label was empty")
        }
        if (contentLength > 0L && received != contentLength) {
            throw IntegrityException(
                "Download of $label was incomplete: got $received of $contentLength bytes"
            )
        }
        val expected = expectedSha256?.trim()?.lowercase()
        if (expected.isNullOrEmpty()) return
        val actual = toHex(digest.digest())
        if (actual != expected) {
            throw IntegrityException(
                "Download of $label does not match the expected file " +
                    "(SHA-256 mismatch) and was discarded"
            )
        }
    }

    /**
     * A Hugging Face revision that can be pinned into a `/resolve/<rev>/`
     * URL: a commit sha, or a branch/tag name made of URL-safe characters.
     * Anything with a slash, space or `..` would change the path shape.
     */
    fun isValidRevision(revision: String): Boolean =
        revision.isNotEmpty() && revision.length <= 128 &&
            revision.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } &&
            !revision.contains("..")

    /**
     * [url] with its `/resolve/main/` segment swapped for `/resolve/<revision>/`
     * when a revision is pinned; unchanged when [revision] is null or blank
     * (the catalogues' current behaviour: follow the mutable main branch).
     *
     * Pinning a commit makes the bytes behind a URL immutable, which is what
     * a pinned [verify] SHA-256 needs to stay valid across upstream pushes.
     */
    fun pinRevision(url: String, revision: String?): String {
        val rev = revision?.trim()
        if (rev.isNullOrEmpty()) return url
        require(isValidRevision(rev)) { "Invalid model revision: $rev" }
        val marker = "/resolve/main/"
        val i = url.indexOf(marker)
        require(i >= 0) { "Not a /resolve/main/ URL: $url" }
        return url.substring(0, i) + "/resolve/" + rev + "/" + url.substring(i + marker.length)
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
