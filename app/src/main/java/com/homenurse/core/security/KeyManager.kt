package com.homenurse.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wraps (encrypts) small secrets with a master key so that plaintext keys are
 * never written to disk and never live in SharedPreferences.
 *
 * Production implementation keeps a non-exportable AES-256 key inside the
 * Android Keystore (hardware-backed when available). Tests use
 * [InMemoryKeyManager] because Robolectric cannot emulate the Keystore.
 */
interface KeyManager {

    /** Encrypt [plaintext], returning a self-describing blob (iv||ciphertext). */
    fun wrap(plaintext: ByteArray): ByteArray

    /** Decrypt a blob produced by [wrap]. Throws on tampering. */
    fun unwrap(blob: ByteArray): ByteArray

    /** Cryptographically strong random bytes (e.g. data-encryption keys). */
    fun randomBytes(size: Int): ByteArray

    fun randomUuid(): String
}

/** AES-256-GCM key that never leaves the Android Keystore. */
class AndroidKeyStoreKeyManager(
    private val keyAlias: String = "homenurse_master_v1",
) : KeyManager {

    private val secureRandom = SecureRandom()

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        require(blob.size > 1) { "Malformed blob" }
        val ivSize = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivSize)
        val ciphertext = blob.copyOfRange(1 + ivSize, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    override fun randomUuid(): String = java.util.UUID.randomUUID().toString()

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

/**
 * Test double: same wrap/unwrap contract with an in-memory key.
 * Also usable as a fallback on devices without a working Android Keystore.
 */
class InMemoryKeyManager : KeyManager {

    private val secureRandom = SecureRandom()
    private val key: SecretKey = ByteArray(32).also(secureRandom::nextBytes)
        .let { SecretKeySpec(it, "AES") }

    override fun wrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val ivSize = blob[0].toInt() and 0xFF
        val iv = blob.copyOfRange(1, 1 + ivSize)
        val ciphertext = blob.copyOfRange(1 + ivSize, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

    override fun randomUuid(): String = java.util.UUID.randomUUID().toString()
}
