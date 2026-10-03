package com.homenurse.data

import androidx.test.core.app.ApplicationProvider
import com.homenurse.data.local.database.HomeNurseDatabase
import com.homenurse.data.local.database.entity.ConditionEntity
import com.homenurse.data.local.database.entity.ConversationEntity
import com.homenurse.data.local.database.entity.ConversationMessageEntity
import com.homenurse.data.local.database.entity.MedicalDocumentEntity
import com.homenurse.data.local.database.entity.MedicalFactEntity
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.ProcessingStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room layer tests on an in-memory database (platform SQLite — SQLCipher's
 * native library cannot load on the JVM, which does not affect these SQL
 * semantics): CRUD, review-count queries, and — most importantly — foreign
 * key CASCADE so deleting a document removes every derived medical row
 * (the "real delete" guarantee).
 *
 * A plain Application is used instead of HomeNurseApp: the production app
 * eagerly opens the Keystore-wrapped vault at startup (fail-closed on
 * devices without a working Keystore), which cannot work inside Robolectric's
 * JVM sandbox. Nothing here depends on vault wiring — only Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RoomDaoTest {

    private lateinit var db: HomeNurseDatabase

    @Before
    fun setUp() {
        db = HomeNurseDatabase.inMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun document(
        id: String = "doc-1",
        title: String = "Discharge summary",
        status: ProcessingStatus = ProcessingStatus.CAPTURED,
    ) = MedicalDocumentEntity(
        id = id,
        title = title,
        mimeType = "image/jpeg",
        storagePath = "vault/$id.bin",
        sizeBytes = 1_024,
        pageCount = 1,
        status = status,
        extractedText = null,
        failureReason = null,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    private fun fact(
        id: String,
        documentId: String = "doc-1",
        status: FactStatus = FactStatus.PENDING,
        type: FactType = FactType.MEDICATION,
        name: String? = "Metformin",
        value: String = "Metformin 500 mg",
    ) = MedicalFactEntity(
        id = id,
        documentId = documentId,
        type = type,
        status = status,
        name = name,
        dose = "500 mg",
        frequency = "Twice daily",
        timing = null,
        duration = null,
        value = value,
        sourceText = value,
        confidence = 0.9,
        createdAt = 1_000,
        updatedAt = 1_000,
    )

    // --- CRUD -----------------------------------------------------------------

    @Test
    fun `document round trips with status and extracted text`() = runTest {
        db.documentDao().insertDocument(document())
        assertNotNull(db.documentDao().getDocument("doc-1"))

        db.documentDao().updateExtractedText("doc-1", "Name: Asha Rao", null, 2, 2_000)
        val updated = db.documentDao().getDocument("doc-1")!!
        assertEquals("Name: Asha Rao", updated.extractedText)
        assertEquals(2, updated.pageCount)
        assertEquals(2_000, updated.updatedAt)
    }

    @Test
    fun `fact status transitions drive review counters`() = runTest {
        db.documentDao().insertDocument(document())
        db.factDao().insertFacts(
            listOf(
                fact("f1", status = FactStatus.PENDING),
                fact("f2", status = FactStatus.PENDING),
                fact("f3", status = FactStatus.PENDING),
            ),
        )

        assertEquals(3, db.factDao().countPending("doc-1"))
        assertEquals(0, db.factDao().countConfirmed("doc-1"))

        db.factDao().updateFact(fact("f1", status = FactStatus.CONFIRMED))
        db.factDao().updateFact(fact("f2", status = FactStatus.REJECTED))

        assertEquals(1, db.factDao().countPending("doc-1"))
        assertEquals(1, db.factDao().countConfirmed("doc-1"))
        assertEquals(1, db.factDao().getFactsByStatus(FactStatus.REJECTED).size)
        assertEquals(FactStatus.CONFIRMED, db.factDao().getFact("f1")!!.status)
        assertEquals("Metformin 500 mg", db.factDao().getFact("f1")!!.value)
        // Provenance survives the round trip.
        assertEquals("Metformin 500 mg", db.factDao().getFact("f1")!!.sourceText)
    }

    @Test
    fun `deletePendingFacts only removes unreviewed rows`() = runTest {
        db.documentDao().insertDocument(document())
        db.factDao().insertFacts(
            listOf(
                fact("f1", status = FactStatus.CONFIRMED),
                fact("f2", status = FactStatus.PENDING),
            ),
        )

        db.factDao().deletePendingFacts("doc-1")

        assertNotNull(db.factDao().getFact("f1"))
        assertNull(db.factDao().getFact("f2"))
    }

    // --- foreign key cascade --------------------------------------------------

    @Test
    fun `deleting a document cascades to facts and derived profile rows`() = runTest {
        db.documentDao().insertDocument(document())
        db.factDao().insertFacts(
            listOf(
                fact("f1", type = FactType.CONDITION, name = "Diabetes", value = "Type 2 diabetes"),
                fact("f2", type = FactType.MEDICATION),
            ),
        )
        db.patientDao().upsertCondition(
            ConditionEntity(
                id = "c1",
                factId = "f1",
                name = "Type 2 diabetes",
                sourceDocumentId = "doc-1",
                confirmedByUser = true,
                createdAt = 1_000,
            ),
        )
        assertEquals(1, db.patientDao().getAllConditions().size)

        db.documentDao().deleteDocument("doc-1")

        assertNull(db.documentDao().getDocument("doc-1"))
        assertTrue(db.factDao().getAllFacts().isEmpty())
        assertTrue("derived condition must be deleted with its document",
            db.patientDao().getAllConditions().isEmpty())
    }

    @Test
    fun `fact without its parent document cannot be inserted`() = runTest {
        // Foreign keys enforced → inserting an orphan must fail.
        var failed = false
        try {
            db.factDao().insertFacts(listOf(fact("orphan", documentId = "missing-doc")))
        } catch (expected: Exception) {
            failed = true
        }
        assertTrue("orphan fact insert must be rejected by FK enforcement", failed)
        assertTrue(db.factDao().getAllFacts().isEmpty())
    }

    @Test
    fun `deleting a conversation cascades to its messages`() = runTest {
        db.conversationDao().upsertConversation(
            ConversationEntity(id = "main-chat", title = "chat", createdAt = 1, updatedAt = 1),
        )
        db.conversationDao().insertMessage(
            ConversationMessageEntity(
                id = "m1",
                conversationId = "main-chat",
                role = ConversationRole.USER,
                content = "question",
                safetyLevel = null,
                createdAt = 1,
            ),
        )
        db.conversationDao().insertMessage(
            ConversationMessageEntity(
                id = "m2",
                conversationId = "main-chat",
                role = ConversationRole.MODEL,
                content = "answer",
                safetyLevel = null,
                createdAt = 2,
            ),
        )
        assertEquals(2, db.conversationDao().getAllMessages().size)

        db.conversationDao().deleteConversation("main-chat")

        assertTrue(db.conversationDao().getAllMessages().isEmpty())
        assertTrue(db.conversationDao().getAllConversations().isEmpty())
    }

    @Test
    fun `all documents are listed newest data intact`() = runTest {
        db.documentDao().insertDocument(document(id = "doc-1", title = "A"))
        db.documentDao().insertDocument(document(id = "doc-2", title = "B"))

        val all = db.documentDao().getAllDocuments()
        assertEquals(2, all.size)
        assertEquals(setOf("A", "B"), all.map { it.title }.toSet())
    }
}
