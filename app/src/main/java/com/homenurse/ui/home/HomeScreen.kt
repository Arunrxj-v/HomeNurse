package com.homenurse.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send as SendOutlined
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.ai.ModelStatus
import com.homenurse.domain.model.Medication
import com.homenurse.domain.usecase.MedicineSchedule
import com.homenurse.ui.components.DocumentCard
import com.homenurse.ui.components.HomeNurseCard
import com.homenurse.ui.components.HomeNursePrimaryButton
import com.homenurse.ui.components.HomeNurseSecondaryButton
import com.homenurse.ui.components.HomeNurseSectionHeader
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.MedicalStatusCard
import com.homenurse.ui.components.StatusTone
import com.homenurse.ui.components.documentStatusPresentation
import com.homenurse.ui.theme.HomeNurseColors
import java.text.DateFormat
import java.util.Date

/**
 * Home: friendly dashboard in the HomeNurse design language —
 * patient summary, health status, care-plan preview, medication preview,
 * recent documents and the Ask action, ending in big primary buttons.
 */
@Composable
fun HomeScreen(
    onOpenDocuments: () -> Unit,
    onAddDocument: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenMedicines: () -> Unit,
    onOpenCarePlan: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDocument: (String) -> Unit = {},
    onOpenStep: (String) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val pendingReview by viewModel.pendingReviewCount.collectAsStateWithLifecycle()
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val carePlan by viewModel.carePlan.collectAsStateWithLifecycle()
    val openTasks by viewModel.openTasks.collectAsStateWithLifecycle()
    val modelStatus by viewModel.modelStatus.collectAsStateWithLifecycle()
    val patientName by viewModel.patientName.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = HomeNurseColors.TextPrimary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        val allTasks = carePlan?.tasks.orEmpty()
        val doneTasks = allTasks.count { it.completed }
        val recentDocuments = documents.sortedByDescending { it.createdAt }.take(3)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1) Patient / recovery summary.
            HomeNurseCard(
                containerColor = HomeNurseColors.PrimaryLight,
                borderColor = null,
                padding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_summary_label).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = HomeNurseColors.PrimaryDark,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = patientName?.let { stringResource(R.string.home_greeting, it) }
                        ?: stringResource(R.string.home_greeting_no_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = HomeNurseColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (allTasks.isNotEmpty()) {
                        stringResource(R.string.care_plan_progress_steps, doneTasks, allTasks.size)
                    } else {
                        stringResource(R.string.privacy_stays_device)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = HomeNurseColors.TextSecondary,
                )
            }

            // 2) Important health status.
            HomeNurseSectionHeader(
                title = stringResource(R.string.home_section_health),
                icon = Icons.Outlined.FavoriteBorder,
            )
            MedicalStatusCard(
                title = stringResource(R.string.home_status_local_ai),
                status = when (modelStatus) {
                    ModelStatus.Ready, is ModelStatus.UpdateAvailable ->
                        stringResource(R.string.home_ai_ready)
                    is ModelStatus.Downloading ->
                        stringResource(R.string.home_ai_downloading)
                    is ModelStatus.Paused ->
                        stringResource(R.string.home_ai_paused)
                    ModelStatus.Verifying, ModelStatus.Installing,
                    ModelStatus.Initializing,
                    -> stringResource(R.string.home_ai_setting_up)
                    is ModelStatus.Failed -> stringResource(R.string.home_ai_failed)
                    ModelStatus.NotInstalled -> stringResource(R.string.home_ai_missing)
                },
                icon = Icons.Filled.SmartToy,
                tone = when (modelStatus) {
                    ModelStatus.Ready, is ModelStatus.UpdateAvailable -> StatusTone.SUCCESS
                    is ModelStatus.Failed -> StatusTone.ERROR
                    ModelStatus.NotInstalled, is ModelStatus.Paused -> StatusTone.WARNING
                    else -> StatusTone.INFO
                },
            )
            MedicalStatusCard(
                title = stringResource(R.string.home_status_documents),
                status = if (pendingReview > 0) {
                    stringResource(R.string.home_pending_review_hint, pendingReview)
                } else {
                    stringResource(R.string.home_status_nothing)
                },
                icon = if (pendingReview > 0) {
                    Icons.Outlined.Description
                } else {
                    Icons.Filled.CheckCircle
                },
                tone = if (pendingReview > 0) StatusTone.WARNING else StatusTone.SUCCESS,
            )

            // 3) Today's care plan preview.
            HomeNurseSectionHeader(
                title = stringResource(R.string.home_section_plan),
                icon = Icons.Outlined.CalendarMonth,
                actionLabel = stringResource(R.string.home_view_all),
                onAction = onOpenCarePlan,
            )
            HomeNurseCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                when {
                    openTasks.isNotEmpty() -> {
                        openTasks
                            .sortedBy { it.timeOfDayMin ?: Int.MAX_VALUE }
                            .take(3)
                            .forEach { task ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onOpenStep(task.id) }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                ) {
                                    task.timeOfDayMin?.let { minutes ->
                                        Text(
                                            text = MedicineSchedule.format(minutes),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = HomeNurseColors.Primary,
                                            modifier = Modifier.width(72.dp),
                                        )
                                    }
                                    Text(
                                        text = task.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = HomeNurseColors.TextPrimary,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                    }
                    allTasks.isNotEmpty() -> Text(
                        text = stringResource(R.string.home_no_open_tasks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeNurseColors.TextSecondary,
                    )
                    else -> Text(
                        text = stringResource(R.string.care_plan_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeNurseColors.TextSecondary,
                    )
                }
            }

            // 4) Medication preview.
            HomeNurseSectionHeader(
                title = stringResource(R.string.home_section_medicines),
                icon = Icons.Filled.Medication,
                actionLabel = stringResource(R.string.home_view_all),
                onAction = onOpenMedicines,
            )
            HomeNurseCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                if (medications.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_no_medicines),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeNurseColors.TextSecondary,
                    )
                } else {
                    medications.take(3).forEach { medication ->
                        MedicationPreviewRow(medication)
                    }
                }
            }

            // 5) Recent documents.
            HomeNurseSectionHeader(
                title = stringResource(R.string.home_section_documents),
                icon = Icons.Outlined.Description,
                actionLabel = stringResource(R.string.home_view_all),
                onAction = onOpenDocuments,
            )
            if (recentDocuments.isEmpty()) {
                HomeNurseCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                    Text(
                        text = stringResource(R.string.home_no_documents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeNurseColors.TextSecondary,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    recentDocuments.forEach { document ->
                        val (statusLabel, statusTone) = documentStatusPresentation(document.status)
                        DocumentCard(
                            title = document.title,
                            subtitle = DateFormat.getDateInstance(DateFormat.MEDIUM)
                                .format(Date(document.createdAt)),
                            statusLabel = statusLabel,
                            statusTone = statusTone,
                            onClick = { onOpenDocument(document.id) },
                        )
                    }
                }
            }

            // 6) Ask HomeNurse action.
            Spacer(modifier = Modifier.height(4.dp))
            HomeNursePrimaryButton(
                text = stringResource(R.string.home_action_ask),
                onClick = onOpenChat,
                leading = Icons.AutoMirrored.Outlined.SendOutlined,
            )
            HomeNurseSecondaryButton(
                text = stringResource(R.string.home_action_add_document),
                onClick = onAddDocument,
                leading = Icons.Filled.AddPhotoAlternate,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MedicationPreviewRow(medication: Medication) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Medication,
            contentDescription = null,
            tint = HomeNurseColors.Primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = medication.name,
                style = MaterialTheme.typography.titleSmall,
                color = HomeNurseColors.TextPrimary,
            )
            medication.dose?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = HomeNurseColors.TextSecondary,
                )
            }
        }
    }
}
