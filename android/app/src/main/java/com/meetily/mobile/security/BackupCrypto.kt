package com.meetily.mobile.security

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase encryption for backup archives: AES-256-GCM with the key derived
 * by PBKDF2-HMAC-SHA256.
 *
 * Written in frames (RECAPBK2). Layout: 8-byte magic, 16-byte salt, 12-byte
 * base nonce, then a sequence of frames, each a 1-byte last-frame flag, a
 * 4-byte big-endian ciphertext length, and the ciphertext. Frame *n* uses the
 * base nonce with *n* mixed into its low 8 bytes, so every frame gets a unique
 * nonce under the same key — the one thing GCM must never repeat. The frame
 * index and the flag are authenticated as associated data, so frames cannot be
 * reordered, duplicated, or dropped, and a stream that ends without a frame
 * marked last is rejected as truncated. That last part matters: independent
 * frames would otherwise each verify happily while the archive as a whole had
 * its tail cut off.
 *
 * The plaintext begins with an 8-byte sentinel in frame 0, so a wrong
 * passphrase fails immediately and unambiguously rather than surfacing as a
 * corrupt zip.
 *
 * Why frames at all: the previous format (RECAPBK1) ran the entire archive
 * through one CipherOutputStream. GCM is an AEAD, and the platform provider
 * cannot release ciphertext before doFinal, so the whole backup — every
 * meeting, every photo, every audio file, easily hundreds of megabytes — was
 * held in the cipher's internal buffer. On a real library that is an
 * OutOfMemoryError reported as nothing more than "Backup failed", and restore
 * of such a file failed the same way. Framing bounds both directions to one
 * frame at a time.
 *
 * RECAPBK1 archives still restore: [decryptingStream] dispatches on the magic
 * it was handed. Only the writer moved on.
 */
object BackupCrypto {

    class WrongPassphraseException :
        IOException("Wrong passphrase (or the backup file is damaged).")

    /** Legacy single-stream format. Still read, never written. */
    val MAGIC: ByteArray = "RECAPBK1".toByteArray(Charsets.US_ASCII)

    /** Framed format. */
    val MAGIC_V2: ByteArray = "RECAPBK2".toByteArray(Charsets.US_ASCII)

    private val SENTINEL = "RECAP_OK".toByteArray(Charsets.US_ASCII)

    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Plaintext bytes per frame. Peak memory is roughly twice this. */
    private const val FRAME_BYTES = 1 shl 20

    /** Refuses an absurd declared length before allocating anything. */
    private const val MAX_FRAME_CIPHERTEXT = FRAME_BYTES + 1024

    fun isEncryptedHeader(header: ByteArray): Boolean =
        matches(header, MAGIC) || matches(header, MAGIC_V2)

    private fun matches(header: ByteArray, magic: ByteArray): Boolean =
        header.size >= magic.size && magic.indices.all { header[it] == magic[it] }

    /** Writes magic + salt + base nonce to [out], then returns the frame writer. */
    fun encryptingStream(passphrase: CharArray, out: OutputStream): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val nonce = ByteArray(IV_LEN).also(random::nextBytes)
        out.write(MAGIC_V2)
        out.write(salt)
        out.write(nonce)
        val stream = FramedOutputStream(out, deriveKey(passphrase, salt), nonce)
        stream.write(SENTINEL)
        return stream
    }

    /**
     * Reads salt + nonce from [input] (the magic must already be consumed and
     * passed as [header]), verifies the sentinel, and returns the decrypting
     * stream. Throws [WrongPassphraseException] when the passphrase doesn't
     * match.
     */
    fun decryptingStream(
        passphrase: CharArray,
        input: InputStream,
        header: ByteArray = MAGIC_V2
    ): InputStream {
        val salt = readFully(input, SALT_LEN)
        val iv = readFully(input, IV_LEN)
        val key = deriveKey(passphrase, salt)
        val stream: InputStream = if (matches(header, MAGIC)) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            CipherInputStream(input, cipher)
        } else {
            FramedInputStream(input, key, iv)
        }
        val check = ByteArray(SENTINEL.size)
        var got = 0
        try {
            while (got < check.size) {
                val n = stream.read(check, got, check.size - got)
                if (n < 0) break
                got += n
            }
        } catch (e: IOException) {
            throw WrongPassphraseException()
        }
        if (got != SENTINEL.size || !SENTINEL.indices.all { check[it] == SENTINEL[it] }) {
            throw WrongPassphraseException()
        }
        return stream
    }

    /**
     * Nonce for frame [index]: the base nonce with the index mixed into its
     * low 8 bytes. The base is random per archive, so nonces are unique both
     * within an archive and across them.
     */
    private fun frameNonce(base: ByteArray, index: Long): ByteArray {
        val nonce = base.copyOf()
        for (i in 0 until 8) {
            val shift = 8 * (7 - i)
            val byte = ((index ushr shift) and 0xFF).toByte()
            nonce[IV_LEN - 8 + i] = (nonce[IV_LEN - 8 + i].toInt() xor byte.toInt()).toByte()
        }
        return nonce
    }

    /** Frame index and last-frame flag, authenticated but not encrypted. */
    private fun frameAad(index: Long, last: Boolean): ByteArray {
        val aad = ByteArray(9)
        for (i in 0 until 8) {
            aad[i] = ((index ushr (8 * (7 - i))) and 0xFF).toByte()
        }
        aad[8] = if (last) 1 else 0
        return aad
    }

    private fun frameCipher(
        mode: Int,
        key: SecretKeySpec,
        base: ByteArray,
        index: Long,
        last: Boolean
    ): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, frameNonce(base, index)))
        cipher.updateAAD(frameAad(index, last))
        return cipher
    }

    private class FramedOutputStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val base: ByteArray
    ) : OutputStream() {

        private val buffer = ByteArray(BackupCrypto.FRAME_BYTES)
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var start = off
            var left = len
            while (left > 0) {
                val take = minOf(left, buffer.size - filled)
                System.arraycopy(b, start, buffer, filled, take)
                filled += take
                start += take
                left -= take
                if (filled == buffer.size) emit(last = false)
            }
        }

        private fun emit(last: Boolean) {
            val cipher = BackupCrypto.frameCipher(Cipher.ENCRYPT_MODE, key, base, index, last)
            val sealed = cipher.doFinal(buffer, 0, filled)
            out.write(if (last) 1 else 0)
            writeInt(sealed.size)
            out.write(sealed)
            filled = 0
            index++
        }

        private fun writeInt(value: Int) {
            out.write((value ushr 24) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        override fun flush() {
            // Deliberately NOT a frame boundary: flushing mid-archive would
            // emit short frames for no benefit. Only the header and completed
            // frames are pushed down.
            out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            // Always a final frame, even when empty — its flag is what tells
            // the reader the archive ended where the writer meant it to.
            emit(last = true)
            out.flush()
            out.close()
        }
    }

    private class FramedInputStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val base: ByteArray
    ) : InputStream() {

        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var sawLast = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= plain.size && !nextFrame()) return -1
            val take = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, take)
            pos += take
            return take
        }

        /** False once the final frame has been consumed. */
        private fun nextFrame(): Boolean {
            while (true) {
                if (sawLast) return false
                val flag = input.read()
                if (flag < 0) {
                    // Ran out before any frame said it was the last one. The
                    // archive was cut short — an export that died partway, or
                    // a deliberately truncated file.
                    throw IOException("Backup file is truncated")
                }
                if (flag != 0 && flag != 1) throw IOException("Backup file is damaged")
                val last = flag == 1
                val size = readInt()
                if (size < 0 || size > BackupCrypto.MAX_FRAME_CIPHERTEXT) {
                    throw IOException("Backup file is damaged")
                }
                val sealed = BackupCrypto.readFully(input, size)
                val cipher = BackupCrypto.frameCipher(Cipher.DECRYPT_MODE, key, base, index, last)
                plain = try {
                    cipher.doFinal(sealed)
                } catch (e: Exception) {
                    // A tag mismatch here is a wrong key, a tampered frame, or
                    // frames moved around — all of which the AAD catches.
                    throw IOException("Backup file is damaged", e)
                }
                pos = 0
                index++
                sawLast = last
                // A frame can legitimately be empty (an archive whose length is
                // an exact multiple of the frame size ends with an empty final
                // frame), so keep going rather than reporting end-of-stream.
                if (plain.isNotEmpty()) return true
                if (last) return false
            }
        }

        private fun readInt(): Int {
            val b = BackupCrypto.readFully(input, 4)
            return ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
        }

        override fun close() {
            input.close()
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    private fun readFully(input: InputStream, len: Int): ByteArray {
        val bytes = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = input.read(bytes, got, len - got)
            if (n < 0) throw IOException("Backup file is truncated")
            got += n
        }
        return bytes
    }
}
