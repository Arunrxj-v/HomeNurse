package com.homenurse.core.storage

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM file encryption for the medical vault.
 *
 * Format: [nonce(12 bytes)][ciphertext + tag]
 * Each document's key is derived per file by [com.homenurse.core.security.MedicalVault].
 * GCM authenticates the data: decryption fails loudly on any tampering.
 */
object FileCrypto {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128

    class CorruptedFileException(cause: Throwable? = null) :
        IOException("Vault file failed authentication", cause)

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    @Throws(CorruptedFileException::class)
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        if (blob.size <= NONCE_SIZE) throw CorruptedFileException()
        val nonce = blob.copyOfRange(0, NONCE_SIZE)
        val ciphertext = blob.copyOfRange(NONCE_SIZE, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            throw CorruptedFileException(error)
        } catch (error: GeneralSecurityException) {
            throw CorruptedFileException(error)
        }
    }

    /** Encrypt and write atomically (tmp file + rename). */
    fun writeEncrypted(target: File, key: ByteArray, plaintext: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeBytes(encrypt(key, plaintext))
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Could not install vault file")
            }
        } finally {
            tmp.delete()
        }
    }

    @Throws(CorruptedFileException::class)
    fun readEncrypted(target: File, key: ByteArray): ByteArray {
        if (!target.exists()) throw IOException("Vault file missing")
        return decrypt(key, target.readBytes())
    }
}
