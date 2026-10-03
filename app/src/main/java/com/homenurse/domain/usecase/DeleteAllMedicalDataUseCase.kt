package com.homenurse.domain.usecase

import com.homenurse.core.security.MedicalVault
import com.homenurse.data.local.database.HomeNurseDatabase
import com.homenurse.document.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Delete all medical data" — real deletion, in a safe order:
 *
 *  1. encrypted document files removed from the vault directory,
 *  2. every database row cleared (documents, facts, medications, labs,
 *     conditions, allergies, care plans, conversations, analyses),
 *  3. the data-encryption key destroyed (any leftover ciphertext — e.g.
 *     filesystem remnants — becomes permanently unreadable).
 *
 * The downloaded model and the local profile name are NOT medical data and
 * are left intact (Settings exposes model deletion separately).
 */
class DeleteAllMedicalDataUseCase(
    private val database: HomeNurseDatabase,
    private val documentStorage: DocumentStorage,
    private val vault: MedicalVault,
) {

    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        documentStorage.deleteAll()
        database.clearAllTables()
        vault.destroy()
    }
}
