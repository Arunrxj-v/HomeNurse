package com.homenurse.document

import com.homenurse.core.security.MedicalVault
import com.homenurse.core.storage.FileCrypto
import java.io.File
import java.io.IOException

data class StoredFile(val fileName: String, val sizeBytes: Long)

/**
 * Encrypted document file storage (application-private vault directory).
 * Files are AES-256-GCM encrypted with keys derived from the medical vault DEK
 * (which is itself wrapped by the Android Keystore). Nothing is ever written
 * to public storage (Downloads/Documents/MediaStore).
 */
interface DocumentStorage {
    suspend fun save(documentId: String, bytes: ByteArray): StoredFile
    fun read(fileName: String): ByteArray
    fun delete(fileName: String)
    fun deleteAll()
    fun totalBytes(): Long
}

class EncryptedDocumentStorage(
    vaultDir: File,
    private val vault: MedicalVault,
) : DocumentStorage {

    private val dir: File = File(vaultDir, "vault").apply { mkdirs() }

    override suspend fun save(documentId: String, bytes: ByteArray): StoredFile {
        val fileName = "$documentId.enc"
        val target = File(dir, fileName)
        FileCrypto.writeEncrypted(target, keyFor(fileName), bytes)
        return StoredFile(fileName = fileName, sizeBytes = target.length())
    }

    override fun read(fileName: String): ByteArray {
        val target = File(dir, fileName)
        return FileCrypto.readEncrypted(target, keyFor(fileName))
    }

    override fun delete(fileName: String) {
        File(dir, fileName).delete()
    }

    override fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    override fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun keyFor(fileName: String): ByteArray =
        vault.deriveKey(PURPOSE, fileName)

    companion object {
        private const val PURPOSE = "document"
    }
}

/** Fails loudly when the vault cannot be read (never returns partial data). */
class VaultAccessException(cause: Throwable? = null) : IOException("Cannot read vault file", cause)
