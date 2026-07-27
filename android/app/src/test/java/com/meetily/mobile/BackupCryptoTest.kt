package com.meetily.mobile

import com.meetily.mobile.security.BackupCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {

    private val payload = ByteArray(70_000) { (it % 251).toByte() }

    private fun encrypt(passphrase: String): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(passphrase.toCharArray(), out).use {
            it.write(payload)
        }
        return out.toByteArray()
    }

    @Test
    fun roundTripRestoresExactBytes() {
        val blob = encrypt("correct horse")
        assertTrue(BackupCrypto.isEncryptedHeader(blob))

        val input = ByteArrayInputStream(blob)
        input.skip(BackupCrypto.MAGIC.size.toLong()) // caller consumes magic
        val decrypted = BackupCrypto.decryptingStream("correct horse".toCharArray(), input)
            .readBytes()
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun wrongPassphraseFailsImmediately() {
        val blob = encrypt("correct horse")
        val input = ByteArrayInputStream(blob)
        input.skip(BackupCrypto.MAGIC.size.toLong())
        try {
            BackupCrypto.decryptingStream("battery staple".toCharArray(), input)
            fail("expected WrongPassphraseException")
        } catch (e: BackupCrypto.WrongPassphraseException) {
            // expected
        }
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val blob = encrypt("correct horse")
        blob[blob.size - 20] = (blob[blob.size - 20].toInt() xor 0x40).toByte()
        val input = ByteArrayInputStream(blob)
        input.skip(BackupCrypto.MAGIC.size.toLong())
        try {
            BackupCrypto.decryptingStream("correct horse".toCharArray(), input).readBytes()
            fail("expected an authentication failure")
        } catch (e: IOException) {
            // expected: GCM tag mismatch surfaces as an IOException
        }
    }

    @Test
    fun plainZipHeaderIsNotMistakenForEncrypted() {
        assertFalse(BackupCrypto.isEncryptedHeader(byteArrayOf(0x50, 0x4B, 0x03, 0x04)))
        assertFalse(BackupCrypto.isEncryptedHeader(ByteArray(0)))
    }

    // --- Framing ---------------------------------------------------------
    //
    // The old format ran the whole archive through one GCM cipher, which
    // buffers everything until doFinal. A 70 KB payload never showed it; a
    // real library with audio is hundreds of megabytes.

    private fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        val header = blob.copyOf(BackupCrypto.MAGIC.size)
        val input = ByteArrayInputStream(blob)
        input.skip(BackupCrypto.MAGIC.size.toLong())
        return BackupCrypto.decryptingStream(passphrase.toCharArray(), input, header)
            .readBytes()
    }

    @Test
    fun roundTripsAPayloadSpanningManyFrames() {
        // Several megabytes: comfortably more than one 1 MiB frame, and a
        // size that is not a whole multiple of the frame either.
        val big = ByteArray(5_000_003) { ((it * 31) % 251).toByte() }
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream("correct horse".toCharArray(), out).use { it.write(big) }
        assertArrayEquals(big, decrypt(out.toByteArray(), "correct horse"))
    }

    @Test
    fun roundTripsAPayloadThatEndsExactlyOnAFrameBoundary() {
        // The 8-byte sentinel shares frame 0, so this is the payload size that
        // makes sentinel + payload an exact frame multiple. The archive then
        // closes with an EMPTY final frame, which the reader has to treat as
        // "the archive ended here", not as "no data, stop early".
        val exact = ByteArray((1 shl 20) - 8) { (it % 97).toByte() }
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream("correct horse".toCharArray(), out).use { it.write(exact) }
        assertArrayEquals(exact, decrypt(out.toByteArray(), "correct horse"))
    }

    @Test
    fun emptyArchiveRoundTrips() {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream("correct horse".toCharArray(), out).use { }
        assertArrayEquals(ByteArray(0), decrypt(out.toByteArray(), "correct horse"))
    }

    /**
     * Frames authenticate individually, so without the last-frame flag in the
     * associated data a truncated archive would decrypt cleanly right up to
     * the cut and look complete.
     */
    @Test
    fun droppedTrailingFramesAreRejected() {
        val big = ByteArray(3_000_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream("correct horse".toCharArray(), out).use { it.write(big) }
        val blob = out.toByteArray()
        // Cut the tail off: whole frames survive at the front.
        val cut = blob.copyOf(blob.size / 2)
        try {
            decrypt(cut, "correct horse")
            fail("expected a truncated archive to be rejected")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun reorderedFramesAreRejected() {
        // Frame 0 carries the sentinel. Its index is authenticated, so
        // replaying it as frame 1 must not verify.
        val big = ByteArray(2_500_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream("correct horse".toCharArray(), out).use { it.write(big) }
        val blob = out.toByteArray()
        val headerLen = BackupCrypto.MAGIC.size + 16 + 12
        val firstFrameLen = 1 + 4 + readInt(blob, headerLen + 1)
        val doubled = blob.copyOf(headerLen) +
            blob.copyOfRange(headerLen, headerLen + firstFrameLen) +
            blob.copyOfRange(headerLen, blob.size)
        try {
            decrypt(doubled, "correct horse")
            fail("expected a replayed frame to be rejected")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun legacySingleStreamArchivesStillRestore() {
        // Written the way RECAPBK1 wrote them, byte for byte, and read back
        // through the current reader — the compatibility claim, actually run.
        val body = ByteArray(50_000) { (it % 241).toByte() }
        val blob = legacyEncrypt("correct horse", body)
        assertTrue(BackupCrypto.isEncryptedHeader(blob))
        assertArrayEquals(body, decrypt(blob, "correct horse"))
    }

    @Test
    fun legacyArchiveWithTheWrongPassphraseStillFailsCleanly() {
        val blob = legacyEncrypt("correct horse", ByteArray(1000))
        try {
            decrypt(blob, "battery staple")
            fail("expected WrongPassphraseException")
        } catch (e: BackupCrypto.WrongPassphraseException) {
            // expected
        }
    }

    /** Reproduces the RECAPBK1 layout: magic, salt, IV, one GCM stream. */
    private fun legacyEncrypt(passphrase: String, body: ByteArray): ByteArray {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val iv = ByteArray(12) { (it + 100).toByte() }
        val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), salt, 310_000, 256)
        val key = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        val out = ByteArrayOutputStream()
        out.write(BackupCrypto.MAGIC)
        out.write(salt)
        out.write(iv)
        javax.crypto.CipherOutputStream(out, cipher).use {
            it.write("RECAP_OK".toByteArray(Charsets.US_ASCII))
            it.write(body)
        }
        return out.toByteArray()
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
