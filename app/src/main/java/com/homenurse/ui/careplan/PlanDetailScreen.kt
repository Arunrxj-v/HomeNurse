package com.homenurse.ui.careplan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.domain.model.CareTask
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.TaskKind
import com.homenurse.domain.model.TaskSource
import com.homenurse.domain.usecase.MedicineSchedule
import com.homenurse.ui.components.EmptyState
import com.homenurse.ui.components.ErrorState
import com.homenurse.ui.components.HomeNurseCard
import com.homenurse.ui.components.HomeNursePrimaryButton
import com.homenurse.ui.components.HomeNurseSecondaryButton
import com.homenurse.ui.components.HomeNurseSectionHeader
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.MedicationCard
import com.homenurse.ui.components.StatusChip
import com.homenurse.ui.components.StatusTone
import com.homenurse.ui.theme.HomeNurseColors

/**
 * Progressive disclosure for one care-plan step: the timeline stays short
 * and this screen carries the full instructions — time, source, the
 * confirmed medication (doctor-provided values in their own block) and the
 * verbatim doctor instructions behind the step. Nothing here is AI text;
 * every value comes from the patient's confirmed document or from them.
 */
@Composable
fun PlanDetailScreen(
    taskId: String,
    onBack: () -> Unit,
    viewModel: PlanDetailViewModel = viewModel(factory = PlanDetailViewModel.factory(taskId)),
) {
    val task by viewModel.task.collectAsStateWithLifecycle()
    val medication by viewModel.medication.collectAsStateWithLifecycle()
    val fact by viewModel.fact.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.care_plan_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        val current = task
        when {
            current == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                ErrorState(
                    message = stringResource(R.string.plan_detail_error),
                    actionLabel = stringResource(R.string.back),
                    onAction = onBack,
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                item { StepSummaryCard(task = current, working = working, onToggle = viewModel::toggle) }
                if (current.source == TaskSource.USER) {
                    item { SourceNote(text = stringResource(R.string.plan_added_by_you)) }
                }

                val med = medication
                if (med != null) {
                    item {
                        HomeNurseSectionHeader(
                            title = stringResource(R.string.plan_step_medication_title),
                            icon = Icons.Filled.Medication,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        MedicationCard(
                            name = med.name,
                            dose = med.dose,
                            frequency = med.frequency,
                            timing = med.timing,
                            duration = med.duration,
                            confirmedByUser = med.confirmedByUser,
                        )
                    }
                }

                val medicalFact = fact
                if (medicalFact != null) {
                    val doctorStyle = medicalFact.type in DOCTOR_INSTRUCTION_TYPES
                    item {
                        HomeNurseSectionHeader(
                            title = if (doctorStyle) {
                                stringResource(R.string.plan_step_doctor_instructions)
                            } else {
                                stringResource(factLabelRes(medicalFact.type))
                            },
                            icon = if (doctorStyle) {
                                Icons.Outlined.MedicalServices
                            } else {
                                Icons.Outlined.Info
                            },
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        HomeNurseCard(padding = PaddingValues(16.dp)) {
                            Text(
                                text = medicalFact.value,
                                style = MaterialTheme.typography.bodyLarge,
                                color = HomeNurseColors.TextPrimary,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SourceNote(text = stringResource(R.string.plan_step_source_note))
                        }
                    }
                }

                if (current.source == TaskSource.USER) {
                    item {
                        HomeNurseSecondaryButton(
                            text = stringResource(R.string.delete),
                            enabled = !working,
                            leading = Icons.Filled.Delete,
                            onClick = viewModel::deleteSelf,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

private val DOCTOR_INSTRUCTION_TYPES = setOf(
    FactType.INSTRUCTION,
    FactType.WARNING_SIGNS,
    FactType.FOLLOW_UP,
)

@Composable
private fun StepSummaryCard(
    task: CareTask,
    working: Boolean,
    onToggle: () -> Unit,
) {
    HomeNurseCard(padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.timeOfDayMin?.let { MedicineSchedule.format(it) }
                        ?: stringResource(R.string.care_plan_time_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = HomeNurseColors.Primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = HomeNurseColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(
                        text = stringResource(
                            if (task.kind == TaskKind.MEDICATION) {
                                R.string.care_plan_kind_medication
                            } else {
                                R.string.care_plan_task_label
                            },
                        ),
                        tone = if (task.kind == TaskKind.MEDICATION) {
                            StatusTone.INFO
                        } else {
                            StatusTone.NEUTRAL
                        },
                    )
                    if (task.completed) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusChip(
                            text = stringResource(R.string.plan_completed_badge),
                            tone = StatusTone.SUCCESS,
                            icon = Icons.Filled.Check,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        HomeNursePrimaryButton(
            text = stringResource(
                if (task.completed) R.string.plan_mark_undone else R.string.plan_mark_done,
            ),
            enabled = !working,
            onClick = onToggle,
        )
    }
}

@Composable
private fun SourceNote(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = HomeNurseColors.TextSecondary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = HomeNurseColors.TextSecondary,
        )
    }
}

private fun factLabelRes(type: FactType): Int = when (type) {
    FactType.PATIENT_INFO -> R.string.fact_patient_info
    FactType.MEDICATION -> R.string.fact_medication
    FactType.LAB_RESULT -> R.string.fact_lab
    FactType.CONDITION -> R.string.fact_condition
    FactType.ALLERGY -> R.string.fact_allergy
    FactType.INSTRUCTION -> R.string.fact_instruction
    FactType.WARNING_SIGNS -> R.string.fact_warning_signs
    FactType.FOLLOW_UP -> R.string.fact_follow_up
    FactType.PROCEDURE -> R.string.fact_procedure
}
