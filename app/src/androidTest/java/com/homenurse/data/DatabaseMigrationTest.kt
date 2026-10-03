package com.homenurse.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.homenurse.data.local.database.HomeNurseDatabase
import com.homenurse.data.local.database.HomeNurseDatabase.Companion.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 → v2 migration contract, run on a real device against a real SQLite
 * file: five additive ALTER TABLEs. A v1 database with actual extracted
 * text and a real pending fact must survive the upgrade with every value
 * intact — no destructive fallback, no lost review data.
 *
 * [MigrationTestHelper] validates the migrated schema against the exported
 * v2 JSON (the schemas ship in the androidTest assets).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HomeNurseDatabase::class.java,
    )

    @Test
    fun migrate1To2_preservesExtractedTextAndFacts() {
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                """
                INSERT INTO medical_documents
                    (id, title, mimeType, storagePath, sizeBytes, pageCount, status,
                     extractedText, failureReason, createdAt, updatedAt)
                VALUES
                    ('doc-1', 'Lab report', 'image/jpeg', 'captures/x.jpg', 1024, 1,
                     'REVIEW_REQUIRED', 'Hemoglobin 13.2 g/dL', NULL, 100, 200)
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO medical_facts
                    (id, documentId, type, status, name, dose, frequency, timing, duration,
                     value, sourceText, confidence, createdAt, updatedAt)
                VALUES
                    ('fact-1', 'doc-1', 'LAB_RESULT', 'PENDING', 'Hemoglobin', NULL,
                     NULL, NULL, NULL, '13.2 g/dL', 'Hemoglobin 13.2 g/dL', 0.85, 100, 100)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(testDb, 2, true, MIGRATION_1_2).use { db ->
            // Existing document data survives; the new column is additive (NULL).
            db.query(
                "SELECT extractedText, normalizedText, status FROM medical_documents WHERE id = 'doc-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Hemoglobin 13.2 g/dL", cursor.getString(0))
                assertTrue("normalizedText must default to NULL", cursor.isNull(1))
                assertEquals("REVIEW_REQUIRED", cursor.getString(2))
            }

            // Existing fact data survives; every new structured column is NULL.
            db.query(
                """
                SELECT name, value, confidence, status, route, instructions, unit, referenceRange
                FROM medical_facts WHERE id = 'fact-1'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Hemoglobin", cursor.getString(0))
                assertEquals("13.2 g/dL", cursor.getString(1))
                assertEquals(0.85, cursor.getDouble(2), 0.001)
                assertEquals("PENDING", cursor.getString(3))
                assertTrue(cursor.isNull(4)) // route
                assertTrue(cursor.isNull(5)) // instructions
                assertTrue(cursor.isNull(6)) // unit
                assertTrue(cursor.isNull(7)) // referenceRange
            }

            // All four new fact columns exist in the migrated schema.
            db.query(
                "SELECT COUNT(*) FROM pragma_table_info('medical_facts') " +
                    "WHERE name IN ('route','instructions','unit','referenceRange')",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate1To2_handlesEmptyDocumentWithoutLosingAnything() {
        helper.createDatabase(testDb, 1).apply {
            execSQL(
                """
                INSERT INTO medical_documents
                    (id, title, mimeType, storagePath, sizeBytes, pageCount, status,
                     extractedText, failureReason, createdAt, updatedAt)
                VALUES
                    ('doc-2', 'Camera capture', 'image/jpeg', 'captures/y.jpg', 2048, 1,
                     'CAPTURED', NULL, NULL, 100, 100)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(testDb, 2, true, MIGRATION_1_2).use { db ->
            db.query(
                "SELECT status, extractedText, normalizedText FROM medical_documents WHERE id = 'doc-2'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CAPTURED", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }
}
