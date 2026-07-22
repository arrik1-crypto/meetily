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
 * by PBKDF2-HMAC-SHA256. File layout: 8-byte magic, 16-byte salt, 12-byte IV,
 * then ciphertext whose plaintext begins with an 8-byte sentinel followed by
 * the zip archive. The sentinel lets a wrong passphrase fail immediately and
 * unambiguously; GCM's tag authenticates the whole stream against tampering.
 */
object BackupCrypto {

    class WrongPassphraseException :
        IOException("Wrong passphrase (or the backup file is damaged).")

    val MAGIC: ByteArray = "RECAPBK1".toByteArray(Charsets.US_ASCII)
    private val SENTINEL = "RECAP_OK".toByteArray(Charsets.US_ASCII)

    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12

    fun isEncryptedHeader(header: ByteArray): Boolean =
        header.size >= MAGIC.size &&
            MAGIC.indices.all { header[it] == MAGIC[it] }

    /** Writes magic + salt + IV to [out], then returns the cipher stream. */
    fun encryptingStream(passphrase: CharArray, out: OutputStream): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        out.write(MAGIC)
        out.write(salt)
        out.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        val stream = CipherOutputStream(out, cipher)
        stream.write(SENTINEL)
        return stream
    }

    /**
     * Reads salt + IV from [input] (the magic must already be consumed),
     * verifies the sentinel, and returns the decrypting stream. Throws
     * [WrongPassphraseException] when the passphrase doesn't match.
     */
    fun decryptingStream(passphrase: CharArray, input: InputStream): InputStream {
        val salt = readFully(input, SALT_LEN)
        val iv = readFully(input, IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(128, iv))
        val stream = CipherInputStream(input, cipher)
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
