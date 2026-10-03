package com.homenurse.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.homenurse.data.local.database.dao.AnalysisDao
import com.homenurse.data.local.database.dao.CarePlanDao
import com.homenurse.data.local.database.dao.ConversationDao
import com.homenurse.data.local.database.dao.DocumentDao
import com.homenurse.data.local.database.dao.FactDao
import com.homenurse.data.local.database.dao.MedicationDao
import com.homenurse.data.local.database.dao.PatientDao
import com.homenurse.data.local.database.entity.AiAnalysisEntity
import com.homenurse.data.local.database.entity.AllergyEntity
import com.homenurse.data.local.database.entity.CarePlanEntity
import com.homenurse.data.local.database.entity.CareTaskEntity
import com.homenurse.data.local.database.entity.ConditionEntity
import com.homenurse.data.local.database.entity.ConversationEntity
import com.homenurse.data.local.database.entity.ConversationMessageEntity
import com.homenurse.data.local.database.entity.EvidenceEntity
import com.homenurse.data.local.database.entity.LabResultEntity
import com.homenurse.data.local.database.entity.MedicalDocumentEntity
import com.homenurse.data.local.database.entity.MedicalFactEntity
import com.homenurse.data.local.database.entity.MedicationEntity
import com.homenurse.data.local.database.entity.PatientEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * HomeNurse database.
 *
 * * Production: SQLCipher-encrypted file (`homenurse.db`) — passphrase is the
 *   vault DEK, wrapped by the Android Keystore. Schema version 2 with
 *   exported schemas (Room Gradle plugin) so future upgrades ship migrations
 *   instead of destroying user data. No destructive fallback is configured.
 * * Tests: in-memory database with the platform SQLite factory (SQLCipher's
 *   native library cannot load on the JVM).
 */
@Database(
    entities = [
        PatientEntity::class,
        ConditionEntity::class,
        AllergyEntity::class,
        LabResultEntity::class,
        MedicalDocumentEntity::class,
        MedicalFactEntity::class,
        MedicationEntity::class,
        CarePlanEntity::class,
        CareTaskEntity::class,
        ConversationEntity::class,
        ConversationMessageEntity::class,
        AiAnalysisEntity::class,
        EvidenceEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(HomeNurseConverters::class)
abstract class HomeNurseDatabase : RoomDatabase() {

    abstract fun patientDao(): PatientDao
    abstract fun documentDao(): DocumentDao
    abstract fun factDao(): FactDao
    abstract fun medicationDao(): MedicationDao
    abstract fun carePlanDao(): CarePlanDao
    abstract fun conversationDao(): ConversationDao
    abstract fun analysisDao(): AnalysisDao

    companion object {
        const val DB_NAME = "homenurse.db"

        /**
         * v1 → v2: OCR pipeline keeps raw and normalized text separately and
         * stores structured extraction fields (route, instructions, lab unit,
         * lab reference range). Pure additive ALTERs — no data is destroyed.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE medical_documents ADD COLUMN normalizedText TEXT")
                db.execSQL("ALTER TABLE medical_facts ADD COLUMN route TEXT")
                db.execSQL("ALTER TABLE medical_facts ADD COLUMN instructions TEXT")
                db.execSQL("ALTER TABLE medical_facts ADD COLUMN unit TEXT")
                db.execSQL("ALTER TABLE medical_facts ADD COLUMN referenceRange TEXT")
            }
        }

        /** SQLCipher-encrypted database used on real devices. */
        fun encrypted(context: Context, passphrase: ByteArray): HomeNurseDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HomeNurseDatabase::class.java,
                DB_NAME,
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Plain in-memory database for tests. */
        fun inMemory(context: Context): HomeNurseDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                HomeNurseDatabase::class.java,
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
