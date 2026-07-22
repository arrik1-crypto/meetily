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
}
