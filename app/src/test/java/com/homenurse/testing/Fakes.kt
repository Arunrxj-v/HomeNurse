package com.homenurse.testing

import android.net.Uri
import com.homenurse.domain.model.AiAnalysis
import com.homenurse.domain.model.CarePlan
import com.homenurse.domain.model.CareTask
import com.homenurse.domain.model.Condition
import com.homenurse.domain.model.Allergy
import com.homenurse.domain.model.Conversation
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.LabResult
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.Medication
import com.homenurse.domain.model.Patient
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.domain.model.TaskSource
import com.homenurse.domain.repository.CarePlanRepository
import com.homenurse.domain.repository.ConversationRepository
import com.homenurse.domain.repository.DocumentRepository
import com.homenurse.domain.repository.FactRepository
import com.homenurse.domain.repository.MedicationRepository
import com.homenurse.domain.repository.ProfileRepository
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** Deterministic clock for tests. */
class FixedClock(private var value: Long = 1_700_000_000_000L) : Clock {
    override fun now(): Long = value
    fun advanceTo(newValue: Long) { value = newValue }
}

/** Sequential, unique ids. */
class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var counter = 0
    override fun newId(): String = "$prefix-${counter++}"
}

/**
 * In-memory FactRepository. `confirmedFactsReturnsAll` simulates a buggy
 * data source returning every row — used to prove BuildAiContextUseCase
 * filters by status itself (defense in depth at the choke point).
 */
class FakeFactRepository(
    var confirmedFactsReturnsAll: Boolean = false,
) : FactRepository {

    private val state = MutableStateFlow<List<MedicalFact>>(emptyList())

    val all: List<MedicalFact> get() = state.value

    fun seed(vararg facts: MedicalFact) {
        state.value = state.value + facts
    }

    override fun observeFacts(documentId: String): Flow<List<MedicalFact>> =
        state.map { list -> list.filter { it.documentId == documentId } }

    override fun observePendingCount(documentId: String): Flow<Int> =
        observeFacts(documentId).map { list -> list.count { it.status == FactStatus.PENDING } }

    override suspend fun pendingCount(documentId: String): Int =
        state.value.count { it.documentId == documentId && it.status == FactStatus.PENDING }

    override suspend fun getFact(id: String): MedicalFact? =
        state.value.firstOrNull { it.id == id }

    override suspend fun addFacts(facts: List<MedicalFact>) {
        val incoming = facts.associateBy { it.id }
        state.value = state.value.filterNot { it.id in incoming.keys } + facts
    }

    override suspend fun setStatus(id: String, status: FactStatus) {
        state.value = state.value.map { if (it.id == id) it.copy(status = status) else it }
    }

    override suspend fun updateFields(
        id: String,
        name: String?,
        dose: String?,
        frequency: String?,
        timing: String?,
        duration: String?,
        value: String,
    ) {
        state.value = state.value.map {
            if (it.id == id) {
                it.copy(
                    name = name, dose = dose, frequency = frequency,
                    timing = timing, duration = duration, value = value,
                )
            } else it
        }
    }

    override suspend fun confirmedFacts(): List<MedicalFact> =
        if (confirmedFactsReturnsAll) {
            state.value
        } else {
            state.value.filter { it.status == FactStatus.CONFIRMED }
        }

    override suspend fun deletePending(documentId: String) {
        state.value = state.value.filterNot {
            it.documentId == documentId && it.status == FactStatus.PENDING
        }
    }
}

/** Derives [Medication] rows from confirmed medication facts (like the real repo). */
class FakeMedicationRepository(
    private val facts: FakeFactRepository,
) : MedicationRepository {

    private val state = MutableStateFlow<List<Medication>>(emptyList())

    override fun observeMedications(): Flow<List<Medication>> = state.asStateFlow()

    override suspend fun all(): List<Medication> = state.value

    override suspend fun upsertFromFact(factId: String) {
        val fact = facts.getFact(factId) ?: return
        if (fact.type != FactType.MEDICATION) return
        val medication = Medication(
            id = "med-$factId",
            factId = fact.id,
            documentId = fact.documentId,
            name = fact.name ?: fact.value,
            dose = fact.dose,
            frequency = fact.frequency,
            timing = fact.timing,
            duration = fact.duration,
            confirmedByUser = fact.status == FactStatus.CONFIRMED,
        )
        state.value = state.value.filterNot { it.factId == factId } + medication
    }

    override suspend fun removeForFact(factId: String) {
        state.value = state.value.filterNot { it.factId == factId }
    }
}

/** Profile rows derived from confirmed facts. */
class FakeProfileRepository : ProfileRepository {

    private val patientState = MutableStateFlow<Patient?>(null)
    private val conditionsState = MutableStateFlow<List<Condition>>(emptyList())
    private val allergiesState = MutableStateFlow<List<Allergy>>(emptyList())
    private val labsState = MutableStateFlow<List<LabResult>>(emptyList())

    val conditions: List<Condition> get() = conditionsState.value
    val allergies: List<Allergy> get() = allergiesState.value
    val labResults: List<LabResult> get() = labsState.value

    override fun observePatient(): Flow<Patient?> = patientState.asStateFlow()

    override suspend fun saveProfile(
        displayName: String,
        dateOfBirth: String?,
        sex: String?,
        emergencyRegion: String?,
    ) {
        patientState.value = Patient("local", displayName, dateOfBirth, sex, emergencyRegion)
    }

    override fun observeConditions(): Flow<List<Condition>> = conditionsState.asStateFlow()
    override fun observeAllergies(): Flow<List<Allergy>> = allergiesState.asStateFlow()
    override fun observeLabResults(): Flow<List<LabResult>> = labsState.asStateFlow()

    override suspend fun upsertConditionFromFact(factId: String, name: String, sourceDocumentId: String) {
        conditionsState.value = conditionsState.value.filterNot { it.factId == factId } +
            Condition("cond-$factId", factId, name, sourceDocumentId)
    }

    override suspend fun upsertAllergyFromFact(factId: String, name: String, sourceDocumentId: String) {
        allergiesState.value = allergiesState.value.filterNot { it.factId == factId } +
            Allergy("alg-$factId", factId, name, sourceDocumentId)
    }

    override suspend fun upsertLabFromFact(
        factId: String,
        testName: String,
        value: String,
        unit: String?,
        referenceRange: String?,
        sourceDocumentId: String,
    ) {
        labsState.value = labsState.value.filterNot { it.factId == factId } +
            LabResult("lab-$factId", factId, testName, value, unit, referenceRange, sourceDocumentId)
    }

    override suspend fun removeDerived(factId: String) {
        conditionsState.value = conditionsState.value.filterNot { it.factId == factId }
        allergiesState.value = allergiesState.value.filterNot { it.factId == factId }
        labsState.value = labsState.value.filterNot { it.factId == factId }
    }
}

/** Document metadata + bytes store; records every status transition. */
class FakeDocumentRepository : DocumentRepository {

    private val state = MutableStateFlow<List<MedicalDocument>>(emptyList())
    private val bytes = mutableMapOf<String, ByteArray>()

    /** (status, failureReason) for every setStatus call, in order. */
    val statusLog = mutableListOf<Pair<ProcessingStatus, String?>>()

    fun put(document: MedicalDocument, payload: ByteArray = ByteArray(0)) {
        state.value = state.value.filterNot { it.id == document.id } + document
        bytes[document.id] = payload
    }

    override fun observeDocuments(): Flow<List<MedicalDocument>> = state.asStateFlow()

    override fun observeDocument(id: String): Flow<MedicalDocument?> =
        state.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun importFromUri(uri: Uri): String =
        throw UnsupportedOperationException("not used in tests")

    override suspend fun storeCapture(title: String, payload: ByteArray): String =
        throw UnsupportedOperationException("not used in tests")

    override suspend fun openBytes(documentId: String): ByteArray =
        bytes[documentId] ?: throw java.io.FileNotFoundException(documentId)

    override suspend fun setStatus(id: String, status: ProcessingStatus, failureReason: String?) {
        statusLog += status to failureReason
        state.value = state.value.map {
            if (it.id == id) it.copy(status = status, failureReason = failureReason) else it
        }
    }

    override suspend fun updateExtractedText(
        id: String,
        text: String,
        normalizedText: String?,
        pageCount: Int,
    ) {
        state.value = state.value.map {
            if (it.id == id) {
                it.copy(
                    extractedText = text,
                    normalizedText = normalizedText,
                    pageCount = pageCount,
                )
            } else it
        }
    }

    override suspend fun deleteDocument(id: String) {
        state.value = state.value.filterNot { it.id == id }
        bytes.remove(id)
    }

    override suspend fun getDocument(id: String): MedicalDocument? =
        state.value.firstOrNull { it.id == id }
}

/** Care plan that preserves USER tasks across generated-task replacement. */
class FakeCarePlanRepository : CarePlanRepository {

    private val state = MutableStateFlow<CarePlan?>(null)

    val currentTasks: List<CareTask> get() = state.value?.tasks.orEmpty()

    override fun observePlan(): Flow<CarePlan?> = state.asStateFlow()

    override fun observeTasks(planId: String): Flow<List<CareTask>> =
        state.map { plan -> plan?.tasks.orEmpty() }

    override suspend fun ensurePlan(): CarePlan =
        state.value ?: CarePlan(id = "plan-1", title = "Care plan", tasks = emptyList())
            .also { state.value = it }

    override suspend fun replaceGeneratedTasks(planId: String, tasks: List<CareTask>) {
        val plan = ensurePlan()
        val userTasks = plan.tasks.filter { it.source == TaskSource.USER }
        state.value = plan.copy(tasks = tasks + userTasks)
    }

    override suspend fun setCompleted(taskId: String, completed: Boolean) {
        val plan = ensurePlan()
        state.value = plan.copy(
            tasks = plan.tasks.map { if (it.id == taskId) it.copy(completed = completed) else it },
        )
    }

    override suspend fun setTime(taskId: String, timeOfDayMin: Int) {
        val plan = ensurePlan()
        state.value = plan.copy(
            tasks = plan.tasks.map { if (it.id == taskId) it.copy(timeOfDayMin = timeOfDayMin) else it },
        )
    }

    override suspend fun addUserTask(title: String, timeOfDayMin: Int?): CareTask {
        val plan = ensurePlan()
        val task = CareTask(
            id = "user-${plan.tasks.size}",
            planId = plan.id,
            title = title,
            kind = com.homenurse.domain.model.TaskKind.USER,
            source = TaskSource.USER,
            timeOfDayMin = timeOfDayMin,
            dueDate = null,
            documentId = null,
            factId = null,
            completed = false,
        )
        state.value = plan.copy(tasks = plan.tasks + task)
        return task
    }

    override suspend fun updateUserTask(taskId: String, title: String, timeOfDayMin: Int?) {
        val plan = ensurePlan()
        state.value = plan.copy(
            tasks = plan.tasks.map {
                if (it.id == taskId) it.copy(title = title, timeOfDayMin = timeOfDayMin) else it
            },
        )
    }

    override suspend fun deleteUserTask(taskId: String) {
        val plan = ensurePlan() ?: return
        state.value = plan.copy(tasks = plan.tasks.filterNot { it.id == taskId })
    }
}

/** Chat history capture (single canonical thread, like production). */
class FakeConversationRepository(
    private val ids: IdGenerator,
    private val clock: Clock,
) : ConversationRepository {

    private val messagesState = MutableStateFlow<List<ConversationMessage>>(emptyList())
    private val analysesState = MutableStateFlow<List<AiAnalysis>>(emptyList())

    val allMessages: List<ConversationMessage> get() = messagesState.value
    val allAnalyses: List<AiAnalysis> get() = analysesState.value

    override fun observeConversations(): Flow<List<Conversation>> =
        MutableStateFlow(listOf(Conversation("main-chat", "HomeNurse chat", clock.now())))

    override fun observeMessages(conversationId: String): Flow<List<ConversationMessage>> =
        messagesState.asStateFlow()

    override suspend fun getOrCreateActive(): Conversation =
        Conversation("main-chat", "HomeNurse chat", clock.now())

    override suspend fun addMessage(
        conversationId: String,
        role: ConversationRole,
        content: String,
        safetyLevel: SafetyLevel?,
    ): ConversationMessage {
        val message = ConversationMessage(
            id = ids.newId(),
            conversationId = conversationId,
            role = role,
            content = content,
            safetyLevel = safetyLevel,
            createdAt = clock.now(),
        )
        messagesState.value = messagesState.value + message
        return message
    }

    override suspend fun saveAnalysis(analysis: AiAnalysis) {
        analysesState.value = analysesState.value + analysis
    }

    override fun observeAnalysisForDocument(documentId: String): Flow<List<AiAnalysis>> =
        analysesState.map { list -> list.filter { it.documentId == documentId } }
}

/**
 * Fake local model: records every request, returns a canned structured
 * response or throws a configured [com.homenurse.ai.AiProviderException].
 * Proves the chat pipeline works without any real model or network.
 */
class FakeAiProvider(
    var response: com.homenurse.domain.model.AiResponse? = null,
    var failure: Throwable? = null,
) : com.homenurse.ai.AiProvider {

    val requests = mutableListOf<com.homenurse.domain.model.AiRequest>()

    override val isReady: Boolean get() = failure == null

    override suspend fun generate(request: com.homenurse.domain.model.AiRequest): com.homenurse.domain.model.AiResponse {
        requests += request
        failure?.let { throw it }
        return response ?: throw com.homenurse.ai.AiProviderException.MalformedResponse()
    }
}
