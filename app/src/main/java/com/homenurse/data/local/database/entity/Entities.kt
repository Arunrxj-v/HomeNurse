package com.homenurse.data.local.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.domain.model.TaskKind
import com.homenurse.domain.model.TaskSource

/**
 * Room entities for the on-device medical vault.
 *
 * Every table lives inside an SQLCipher-encrypted database file; the
 * encryption key is managed by the Android Keystore (see MedicalVault).
 * Documents (PDFs/images) are stored as separately encrypted files and only
 * their metadata/storage path lives here.
 */

@Entity(tableName = "patient")
data class PatientEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val dateOfBirth: String?,
    val sex: String?,
    val emergencyRegion: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "conditions",
    indices = [Index("sourceDocumentId"), Index("factId")],
    foreignKeys = [
        ForeignKey(
            entity = MedicalFactEntity::class,
            parentColumns = ["id"],
            childColumns = ["factId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MedicalDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ConditionEntity(
    @PrimaryKey val id: String,
    val factId: String?,
    val name: String,
    val sourceDocumentId: String?,
    val confirmedByUser: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "allergies",
    indices = [Index("sourceDocumentId"), Index("factId")],
    foreignKeys = [
        ForeignKey(
            entity = MedicalFactEntity::class,
            parentColumns = ["id"],
            childColumns = ["factId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MedicalDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AllergyEntity(
    @PrimaryKey val id: String,
    val factId: String?,
    val name: String,
    val sourceDocumentId: String?,
    val confirmedByUser: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "lab_results",
    indices = [Index("sourceDocumentId"), Index("factId")],
    foreignKeys = [
        ForeignKey(
            entity = MedicalFactEntity::class,
            parentColumns = ["id"],
            childColumns = ["factId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MedicalDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LabResultEntity(
    @PrimaryKey val id: String,
    val factId: String?,
    val testName: String,
    val value: String,
    val unit: String?,
    val referenceRange: String?,
    val sourceDocumentId: String?,
    val confirmedByUser: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "medical_documents")
data class MedicalDocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mimeType: String,
    /** Relative file name inside the encrypted vault directory. */
    val storagePath: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val status: ProcessingStatus,
    /** Full OCR text of the document (inside the encrypted database). */
    val extractedText: String?,
    /** Coarse failure category — never medical content. */
    val failureReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Conservatively normalized OCR text; raw text stays untouched in extractedText. */
    val normalizedText: String? = null,
)

@Entity(
    tableName = "medical_facts",
    foreignKeys = [
        ForeignKey(
            entity = MedicalDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("status")],
)
data class MedicalFactEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val type: FactType,
    val status: FactStatus,
    /** Medication name / test name / condition name (verbatim from the document). */
    val name: String?,
    /** Structured fields — all extracted verbatim, never invented. */
    val dose: String?,
    val frequency: String?,
    val timing: String?,
    val duration: String?,
    /** Single-line human summary of the fact. */
    val value: String,
    /** The exact source text this fact came from (provenance). */
    val sourceText: String,
    /** Pattern-match confidence in [0,1]; always reviewed by the user. */
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long,
    /** Route of administration, verbatim from the document (medications). */
    val route: String? = null,
    /** Standing instruction fragments, verbatim (medications). */
    val instructions: String? = null,
    /** Lab unit, verbatim (lab results). */
    val unit: String? = null,
    /** Lab reference range, verbatim (lab results). */
    val referenceRange: String? = null,
)

@Entity(
    tableName = "medications",
    foreignKeys = [
        ForeignKey(
            entity = MedicalDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MedicalFactEntity::class,
            parentColumns = ["id"],
            childColumns = ["factId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("factId")],
)
data class MedicationEntity(
    @PrimaryKey val id: String,
    /** The confirmed fact this medication was derived from (provenance). */
    val factId: String,
    val documentId: String,
    val name: String,
    val dose: String?,
    val frequency: String?,
    val timing: String?,
    val duration: String?,
    val confirmedByUser: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "care_plans",
)
data class CarePlanEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "care_tasks",
    foreignKeys = [
        ForeignKey(
            entity = CarePlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("planId")],
)
data class CareTaskEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val title: String,
    val kind: TaskKind,
    val source: TaskSource,
    /** Minutes since midnight, or null when the document did not specify a time. */
    val timeOfDayMin: Int?,
    /** ISO date (yyyy-MM-dd) this task is due, or null for recurring daily tasks. */
    val dueDate: String?,
    val documentId: String?,
    val factId: String?,
    val completed: Boolean,
    val completedAt: Long?,
    val sortOrder: Int,
)

@Entity(
    tableName = "conversations",
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: ConversationRole,
    val content: String,
    val safetyLevel: SafetyLevel?,
    val createdAt: Long,
)

@Entity(
    tableName = "ai_analyses",
    indices = [Index("documentId")],
    foreignKeys = [
        ForeignKey(
            entity = MedicalDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AiAnalysisEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val question: String?,
    val documentId: String?,
    val summary: String,
    val explanation: String,
    val warnings: List<String>,
    val suggestedNextSteps: List<String>,
    val uncertainty: String?,
    val requiresClinician: Boolean,
    val safetyLevel: SafetyLevel,
    val createdAt: Long,
)

@Entity(
    tableName = "evidence",
    foreignKeys = [
        ForeignKey(
            entity = AiAnalysisEntity::class,
            parentColumns = ["id"],
            childColumns = ["analysisId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("analysisId")],
)
data class EvidenceEntity(
    @PrimaryKey val id: String,
    val analysisId: String,
    val factId: String?,
    val documentId: String?,
    val label: String,
    val sourceText: String,
)
