package com.homenurse.data.local.database

import androidx.room.TypeConverter
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.domain.model.TaskKind
import com.homenurse.domain.model.TaskSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class HomeNurseConverters {

    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun statusToString(value: ProcessingStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): ProcessingStatus =
        ProcessingStatus.valueOf(value)

    @TypeConverter fun factTypeToString(value: FactType): String = value.name
    @TypeConverter fun stringToFactType(value: String): FactType = FactType.valueOf(value)

    @TypeConverter fun factStatusToString(value: FactStatus): String = value.name
    @TypeConverter fun stringToFactStatus(value: String): FactStatus = FactStatus.valueOf(value)

    @TypeConverter fun roleToString(value: ConversationRole): String = value.name
    @TypeConverter fun stringToRole(value: String): ConversationRole = ConversationRole.valueOf(value)

    @TypeConverter fun kindToString(value: TaskKind): String = value.name
    @TypeConverter fun stringToKind(value: String): TaskKind = TaskKind.valueOf(value)

    @TypeConverter fun sourceToString(value: TaskSource): String = value.name
    @TypeConverter fun stringToSource(value: String): TaskSource = TaskSource.valueOf(value)

    @TypeConverter fun safetyToString(value: SafetyLevel): String = value.name
    @TypeConverter fun stringToSafety(value: String): SafetyLevel = SafetyLevel.valueOf(value)

    @TypeConverter fun stringListToJson(value: List<String>): String = json.encodeToString(value)

    @TypeConverter fun jsonToStringList(value: String): List<String> = try {
        json.decodeFromString(value)
    } catch (error: Exception) {
        emptyList()
    }
}
