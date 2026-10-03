package com.homenurse.ui.documents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.ui.components.EmptyMessage
import com.homenurse.ui.components.HomeNurseCard
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.LargeButton
import com.homenurse.ui.components.LargeOutlinedButton
import com.homenurse.ui.components.NoticeCard
import com.homenurse.ui.components.SectionCard

/**
 * Extraction review: the user sees EVERY candidate with its exact source
 * line and confirms / edits / rejects. Nothing reaches the medication list,
 * profile or AI context before this point.
 */
@Composable
fun FactReviewScreen(
    documentId: String,
    onBack: () -> Unit,
    viewModel: FactReviewViewModel = viewModel(
        factory = FactReviewViewModel.factory(documentId),
    ),
) {
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val document by viewModel.document.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()

    val pending = facts.filter { it.status == FactStatus.PENDING }
    val resolved = facts.filter { it.status != FactStatus.PENDING }

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.review_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.review_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // Honest read status — a poor or failed read is never hidden.
            item {
                when {
                    document?.failureReason == "low_ocr_quality" ->
                        NoticeCard(text = stringResource(R.string.document_read_poor))

                    document?.status == ProcessingStatus.FAILED ->
                        NoticeCard(
                            text = stringResource(
                                when (document?.failureReason) {
                                    "no_text" -> R.string.document_read_no_text
                                    else -> R.string.document_read_failed
                                },
                            ),
                        )
                }
            }

            if (pending.isEmpty() && resolved.isEmpty()) {
                item { EmptyMessage(stringResource(R.string.review_empty)) }
            }

            if (pending.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.review_pending_count, pending.size),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                items(pending, key = { it.id }) { fact ->
                    PendingFactCard(
                        fact = fact,
                        enabled = !working,
                        onConfirm = { viewModel.confirm(fact.id) },
                        onReject = { viewModel.reject(fact.id) },
                        onEditConfirm = { name, dose, frequency, timing, duration, value ->
                            viewModel.editAndConfirm(
                                factId = fact.id,
                                name = name,
                                dose = dose,
                                frequency = frequency,
                                timing = timing,
                                duration = duration,
                                value = value,
                            )
                        },
                    )
                }
            } else {
                item {
                    Text(
                        text = stringResource(R.string.review_all_done),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            items(resolved, key = { it.id }) { fact ->
                ResolvedFactRow(fact)
            }

            // Manual entry — the escape hatch when a document could not be
            // read (or a value is missing from it entirely).
            item {
                ManualAddCard(
                    enabled = !working,
                    onAdd = { type, name, value, dose, frequency ->
                        viewModel.addManualFact(type, name, value, dose, frequency)
                    },
                )
            }

            item { androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp)) }
        }
    }
}

@Composable
private fun PendingFactCard(
    fact: MedicalFact,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onEditConfirm: (
        name: String?,
        dose: String?,
        frequency: String?,
        timing: String?,
        duration: String?,
        value: String,
    ) -> Unit,
) {
    var editing by remember(fact.id) { mutableStateOf(false) }
    var name by remember(fact.id) { mutableStateOf(fact.name.orEmpty()) }
    var dose by remember(fact.id) { mutableStateOf(fact.dose.orEmpty()) }
    var frequency by remember(fact.id) { mutableStateOf(fact.frequency.orEmpty()) }
    var timing by remember(fact.id) { mutableStateOf(fact.timing.orEmpty()) }
    var duration by remember(fact.id) { mutableStateOf(fact.duration.orEmpty()) }

    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = typeLabel(fact.type),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (fact.needsVerification) {
                Box(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            RoundedCornerShape(50),
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.review_needs_verification),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        Text(text = fact.value, style = MaterialTheme.typography.titleMedium)
        fact.route?.let {
            Text(
                text = stringResource(R.string.review_route, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        fact.instructions?.let {
            Text(
                text = stringResource(R.string.review_instructions, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        fact.unit?.takeIf { unit -> !fact.value.contains(unit, ignoreCase = true) }?.let {
            Text(
                text = stringResource(R.string.review_unit, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        fact.referenceRange?.takeIf { range -> !fact.value.contains(range) }?.let {
            Text(
                text = stringResource(R.string.review_reference, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.review_source, fact.sourceText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (editing) {
            if (fact.type == FactType.MEDICATION) {
                EditField(R.string.field_name, name) { name = it }
                EditField(R.string.field_dose, dose) { dose = it }
                EditField(R.string.field_frequency, frequency) { frequency = it }
                EditField(R.string.field_timing, timing) { timing = it }
                EditField(R.string.field_duration, duration) { duration = it }
            }

            LargeButton(
                text = stringResource(R.string.review_confirm_edits),
                enabled = enabled,
                onClick = {
                    val composite = listOfNotNull(
                        name.ifBlank { null },
                        dose.ifBlank { null },
                        frequency.ifBlank { null },
                        timing.ifBlank { null },
                        duration.ifBlank { null },
                    ).joinToString(", ").ifBlank { fact.value }
                    onEditConfirm(
                        name.ifBlank { null },
                        dose.ifBlank { null },
                        frequency.ifBlank { null },
                        timing.ifBlank { null },
                        duration.ifBlank { null },
                        composite,
                    )
                },
            )
            LargeOutlinedButton(
                text = stringResource(R.string.cancel),
                enabled = enabled,
                onClick = { editing = false },
            )
        } else {
            LargeButton(
                text = stringResource(R.string.review_confirm),
                enabled = enabled,
                onClick = onConfirm,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (fact.type == FactType.MEDICATION) {
                    LargeOutlinedButton(
                        text = stringResource(R.string.review_edit),
                        enabled = enabled,
                        onClick = { editing = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                LargeOutlinedButton(
                    text = stringResource(R.string.review_reject),
                    enabled = enabled,
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EditField(labelRes: Int, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Manual entry form. Used when a document could not be read or an item is
 * missing from it — the information is user-asserted and enters as
 * CONFIRMED through [ConfirmFactsUseCase][FactReviewViewModel.addManualFact].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualAddCard(
    enabled: Boolean,
    onAdd: (type: FactType, name: String, value: String, dose: String, frequency: String) -> Unit,
) {
    val types = listOf(
        FactType.MEDICATION,
        FactType.LAB_RESULT,
        FactType.CONDITION,
        FactType.ALLERGY,
        FactType.PATIENT_INFO,
        FactType.INSTRUCTION,
    )
    var type by remember { mutableStateOf(FactType.MEDICATION) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf("") }

    SectionCard {
        Text(
            text = stringResource(R.string.review_add_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.review_add_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            types.forEach { candidate ->
                FilterChip(
                    selected = type == candidate,
                    onClick = { type = candidate },
                    label = { Text(typeLabel(candidate)) },
                )
            }
        }
        EditField(R.string.field_name, name) { name = it }
        EditField(R.string.field_value, value) { value = it }
        if (type == FactType.MEDICATION) {
            EditField(R.string.field_dose, dose) { dose = it }
            EditField(R.string.field_frequency, frequency) { frequency = it }
        }
        val ready = if (type == FactType.MEDICATION) {
            name.isNotBlank()
        } else {
            value.isNotBlank() || name.isNotBlank()
        }
        LargeOutlinedButton(
            text = stringResource(R.string.review_add_confirm),
            enabled = enabled && ready,
            onClick = {
                onAdd(type, name, value, dose, frequency)
                name = ""
                value = ""
                dose = ""
                frequency = ""
            },
        )
    }
}

@Composable
private fun ResolvedFactRow(fact: MedicalFact) {
    HomeNurseCard(padding = PaddingValues(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (fact.status == FactStatus.CONFIRMED) {
                    stringResource(R.string.review_status_confirmed)
                } else {
                    stringResource(R.string.review_status_rejected)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (fact.status == FactStatus.CONFIRMED) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(text = fact.value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun typeLabel(type: FactType): String = when (type) {
    FactType.PATIENT_INFO -> stringResource(R.string.fact_patient_info)
    FactType.MEDICATION -> stringResource(R.string.fact_medication)
    FactType.LAB_RESULT -> stringResource(R.string.fact_lab)
    FactType.CONDITION -> stringResource(R.string.fact_condition)
    FactType.ALLERGY -> stringResource(R.string.fact_allergy)
    FactType.INSTRUCTION -> stringResource(R.string.fact_instruction)
    FactType.WARNING_SIGNS -> stringResource(R.string.fact_warning_signs)
    FactType.FOLLOW_UP -> stringResource(R.string.fact_follow_up)
    FactType.PROCEDURE -> stringResource(R.string.fact_procedure)
}
