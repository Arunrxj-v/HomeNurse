package com.homenurse.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * AES-256-GCM file-vault tests: round-trip fidelity, unique nonces per
 * encryption, tamper detection and atomic write behaviour.
 * Pure JVM — no Android runtime required.
 */
class FileCryptoTest {

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `encrypt decrypt round trip preserves bytes exactly`() {
        val plaintext = ByteArray(1024) { it.toByte() }
        val blob = FileCrypto.encrypt(key, plaintext)
        assertArrayEquals(plaintext, FileCrypto.decrypt(key, blob))
    }

    @Test
    fun `ciphertext does not contain plaintext bytes`() {
        val plaintext = "CONFIDENTIAL-PATIENT-DATA".toByteArray()
        val blob = FileCrypto.encrypt(key, plaintext)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("CONFIDENTIAL"))
    }

    @Test
    fun `same plaintext encrypts to different ciphertext each time`() {
        val plaintext = "same input".toByteArray()
        val a = FileCrypto.encrypt(key, plaintext)
        val b = FileCrypto.encrypt(key, plaintext)
        assertFalse(a.contentEquals(b)) // fresh nonce per encryption
    }

    @Test
    fun `wrong key fails loudly instead of returning garbage`() {
        val blob = FileCrypto.encrypt(key, "data".toByteArray())
        try {
            FileCrypto.decrypt(otherKey, blob)
            fail("Decryption with the wrong key must throw")
        } catch (expected: FileCrypto.CorruptedFileException) {
            // expected: GCM tag mismatch
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val blob = FileCrypto.encrypt(key, "patient record".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        try {
            FileCrypto.decrypt(key, blob)
            fail("Tampered data must be rejected")
        } catch (expected: FileCrypto.CorruptedFileException) {
            // expected
        }
    }

    @Test
    fun `truncated blob is rejected`() {
        try {
            FileCrypto.decrypt(key, ByteArray(4))
            fail("Truncated blob must be rejected")
        } catch (expected: FileCrypto.CorruptedFileException) {
            // expected
        }
    }

    @Test
    fun `writeEncrypted then readEncrypted round trips through the filesystem`() {
        val dir = Files.createTempDirectory("vault-test").toFile()
        try {
            val target = File(dir, "doc-1.enc")
            val payload = ByteArray(4096) { (it * 7).toByte() }
            FileCrypto.writeEncrypted(target, key, payload)

            assertTrue(target.exists())
            assertFalse(File(dir, "doc-1.enc.tmp").exists()) // tmp cleaned up
            assertArrayEquals(payload, FileCrypto.readEncrypted(target, key))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing file raises IOException not silent empty data`() {
        val dir = Files.createTempDirectory("vault-test").toFile()
        try {
            FileCrypto.readEncrypted(File(dir, "absent.enc"), key)
            fail("Missing file must raise")
        } catch (expected: java.io.IOException) {
            // expected
        } finally {
            dir.deleteRecursively()
        }
    }
}
