package com.homenurse.app

import android.content.Context
import com.homenurse.R
import com.homenurse.ai.HttpModelDownloader
import com.homenurse.ai.LocalGemmaAiProvider
import com.homenurse.ai.ModelManager
import com.homenurse.ai.ModelManifest
import com.homenurse.core.model.DeviceCapabilityChecker
import com.homenurse.core.auth.AuthApi
import com.homenurse.core.auth.GoogleIdentityProvider
import com.homenurse.core.auth.RemoteAuthRepository
import com.homenurse.core.auth.SessionManager
import com.homenurse.core.network.NetworkClient
import com.homenurse.core.security.AndroidKeyStoreKeyManager
import com.homenurse.core.security.EncryptedSecureStorage
import com.homenurse.core.security.KeyManager
import com.homenurse.core.security.MedicalVault
import com.homenurse.core.security.SecureStorage
import com.homenurse.data.local.database.HomeNurseDatabase
import com.homenurse.data.repository.CarePlanRepositoryImpl
import com.homenurse.data.repository.ConversationRepositoryImpl
import com.homenurse.data.repository.DocumentRepositoryImpl
import com.homenurse.data.repository.FactRepositoryImpl
import com.homenurse.data.repository.MedicationRepositoryImpl
import com.homenurse.data.repository.ProfileRepositoryImpl
import com.homenurse.document.DocumentImporter
import com.homenurse.document.DocumentProcessor
import com.homenurse.document.DocumentStorage
import com.homenurse.document.EncryptedDocumentStorage
import com.homenurse.document.MlKitOcrProvider
import com.homenurse.domain.repository.AuthRepository
import com.homenurse.domain.repository.CarePlanRepository
import com.homenurse.domain.repository.ConversationRepository
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.repository.MedicationRepository
import com.homenurse.domain.repository.ProfileRepository
import com.homenurse.domain.usecase.AskHomeNurseUseCase
import com.homenurse.domain.usecase.BuildAiContextUseCase
import com.homenurse.domain.usecase.ConfirmFactsUseCase
import com.homenurse.domain.usecase.DeleteAllMedicalDataUseCase
import com.homenurse.domain.usecase.ExportMedicalDataUseCase
import com.homenurse.domain.usecase.ExplainDocumentUseCase
import com.homenurse.domain.usecase.GenerateCarePlanUseCase
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import com.homenurse.domain.util.SystemClock
import com.homenurse.reminder.ReminderScheduler
import com.homenurse.safety.SafetyEngine
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manual dependency container (no DI framework).
 *
 * Construction order is the wiring order: security primitives → database →
 * storage → repositories → AI → use cases → reminders. Every medical-data
 * component receives only local dependencies; [NetworkClient] is reachable
 * solely by [ModelManager] for model downloads.
 */
class AppContainer(private val context: Context) {

    // --- security -------------------------------------------------------------
    val keyManager: KeyManager = AndroidKeyStoreKeyManager()
    val secureStorage: SecureStorage = EncryptedSecureStorage(context, keyManager)
    val vault: MedicalVault = MedicalVault(keyManager, secureStorage)

    // --- network --------------------------------------------------------------
    // Reachable ONLY by authentication and the model-download path.
    // Medical repositories never see this client (enforced by tests).
    private val networkClient = NetworkClient()

    // --- authentication (account/session only — never medical data) -----------
    val authApi: AuthApi = AuthApi(networkClient)
    val sessionManager: SessionManager = SessionManager(secureStorage, authApi)
    val authRepository: AuthRepository = RemoteAuthRepository(sessionManager, authApi)
    val googleIdentityProvider: GoogleIdentityProvider = GoogleIdentityProvider(context)

    // --- time / ids -----------------------------------------------------------
    val clock: Clock = SystemClock
    val ids: IdGenerator = object : IdGenerator {
        override fun newId(): String = keyManager.randomUuid()
    }

    // --- database -------------------------------------------------------------
    val database: HomeNurseDatabase = HomeNurseDatabase.encrypted(context, vault.dek())

    // --- document pipeline ----------------------------------------------------
    val documentStorage: DocumentStorage =
        EncryptedDocumentStorage(File(context.filesDir, "medical"), vault)
    val ocrProvider = MlKitOcrProvider()
    val documentImporter = DocumentImporter(File(context.cacheDir, "doc_render"))

    // --- repositories ---------------------------------------------------------
    val profileRepository: ProfileRepository =
        ProfileRepositoryImpl(database.patientDao(), clock)
    val factRepository: FactRepository = FactRepositoryImpl(database.factDao(), clock)
    val medicationRepository: MedicationRepository =
        MedicationRepositoryImpl(database.medicationDao(), database.factDao(), clock)
    val documentRepository: DocumentRepository = DocumentRepositoryImpl(
        database = database,
        documentDao = database.documentDao(),
        documentStorage = documentStorage,
        contentResolver = context.contentResolver,
        clock = clock,
        ids = ids,
    )
    val carePlanRepository: CarePlanRepository = CarePlanRepositoryImpl(
        database = database,
        carePlanDao = database.carePlanDao(),
        clock = clock,
        ids = ids,
        defaultTitle = context.getString(R.string.care_plan_default_title),
    )
    val conversationRepository: ConversationRepository = ConversationRepositoryImpl(
        conversationDao = database.conversationDao(),
        analysisDao = database.analysisDao(),
        clock = clock,
        ids = ids,
        defaultTitle = context.getString(R.string.chat_default_title),
    )

    val documentProcessor = DocumentProcessor(
        documents = documentRepository,
        facts = factRepository,
        ocrProvider = ocrProvider,
        renderer = documentImporter,
        ids = ids,
        clock = clock,
    )

    // --- safety + AI ----------------------------------------------------------
    val safetyEngine: SafetyEngine = SafetyEngine()

    val modelManifest: ModelManifest = run {
        val json = Json { ignoreUnknownKeys = true }
        val text = context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }
        json.decodeFromString(ModelManifest.serializer(), text)
    }

    val modelManager: ModelManager = ModelManager(
        modelsDir = File(context.filesDir, "models"),
        engineCacheDir = File(context.cacheDir, "engine"),
        manifest = modelManifest,
        downloader = HttpModelDownloader(networkClient),
        capabilities = { DeviceCapabilityChecker.current(context) },
        // Model downloads and manifest reads carry only the session's
        // account/device token — never medical identifiers (privacy tests
        // assert this end-to-end against a recording server).
        accessTokenProvider = { sessionManager.validAccessToken() },
    )

    val aiProvider = LocalGemmaAiProvider(modelManager)

    // --- use cases ------------------------------------------------------------
    val buildAiContext = BuildAiContextUseCase(
        facts = factRepository,
        medications = medicationRepository,
        carePlan = carePlanRepository,
        documents = documentRepository,
    )

    val generateCarePlan = GenerateCarePlanUseCase(
        medications = medicationRepository,
        facts = factRepository,
        carePlan = carePlanRepository,
        ids = ids,
    )

    val confirmFacts = ConfirmFactsUseCase(
        facts = factRepository,
        medications = medicationRepository,
        profiles = profileRepository,
        documents = documentRepository,
        generateCarePlan = generateCarePlan,
        clock = clock,
    )

    val askHomeNurse = AskHomeNurseUseCase(
        safetyEngine = safetyEngine,
        conversations = conversationRepository,
        buildContext = buildAiContext,
        aiProvider = aiProvider,
        ids = ids,
        clock = clock,
    )

    val explainDocument = ExplainDocumentUseCase(
        documents = documentRepository,
        facts = factRepository,
        buildContext = buildAiContext,
        aiProvider = aiProvider,
        safetyEngine = safetyEngine,
        conversations = conversationRepository,
        ids = ids,
        clock = clock,
    )

    val deleteAllMedicalData = DeleteAllMedicalDataUseCase(
        database = database,
        documentStorage = documentStorage,
        vault = vault,
    )

    val exportMedicalData = ExportMedicalDataUseCase(database = database)

    // --- reminders ------------------------------------------------------------
    val reminderScheduler = ReminderScheduler(context)

    companion object {
        const val MANIFEST_ASSET = "models.json"
    }
}
