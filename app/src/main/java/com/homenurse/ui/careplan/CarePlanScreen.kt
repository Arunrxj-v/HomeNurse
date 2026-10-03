package com.homenurse.ui.careplan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.domain.model.CareTask
import com.homenurse.domain.model.TaskKind
import com.homenurse.domain.model.TaskSource
import com.homenurse.domain.usecase.MedicineSchedule
import com.homenurse.ui.components.CarePlanCard
import com.homenurse.ui.components.EmptyState
import com.homenurse.ui.components.HomeNurseSecondaryButton
import com.homenurse.ui.components.HomeNurseSectionHeader
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.StatusTone
import com.homenurse.ui.components.TimelineCard

/**
 * Plan: recovery progress card on top, then the complete timeline of care
 * tasks (time, activity card, medication/task label). Tasks are generated
 * ONLY from confirmed medications and confirmed follow-up facts, plus
 * user-added rows; users can complete, time and delete their own tasks and
 * regeneration never touches user-added rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarePlanScreen(
    onBack: () -> Unit,
    onOpenStep: (String) -> Unit = {},
    viewModel: CarePlanViewModel = viewModel(),
) {
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.care_plan_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.care_plan_add_task),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val tasks = plan?.tasks.orEmpty()
        val doneCount = tasks.count { it.completed }
        val progress = if (tasks.isEmpty()) 0f else doneCount.toFloat() / tasks.size

        if (tasks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                EmptyState(
                    title = stringResource(R.string.care_plan_empty),
                    icon = Icons.Outlined.CalendarMonth,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                item {
                    CarePlanCard(
                        progress = progress,
                        title = stringResource(R.string.care_plan_progress_title),
                        stepsLabel = stringResource(
                            R.string.care_plan_progress_steps,
                            doneCount,
                            tasks.size,
                        ),
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    HomeNurseSectionHeader(
                        title = stringResource(R.string.care_plan_section_timeline),
                        icon = Icons.Outlined.CalendarMonth,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                    TimelineCard(
                        timeLabel = task.timeOfDayMin?.let { MedicineSchedule.format(it) },
                        title = task.title,
                        kindLabel = stringResource(
                            if (task.kind == TaskKind.MEDICATION) {
                                R.string.care_plan_kind_medication
                            } else {
                                R.string.care_plan_task_label
                            },
                        ),
                        kindTone = if (task.kind == TaskKind.MEDICATION) {
                            StatusTone.INFO
                        } else {
                            StatusTone.NEUTRAL
                        },
                        completed = task.completed,
                        isFirst = index == 0,
                        isLast = index == tasks.lastIndex,
                        enabled = !working,
                        onClick = { onOpenStep(task.id) },
                        onToggle = { viewModel.toggle(task) },
                        onDelete = if (task.source == TaskSource.USER) {
                            { viewModel.deleteUserTask(task.id) }
                        } else {
                            null
                        },
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    HomeNurseSecondaryButton(
                        text = stringResource(R.string.care_plan_regenerate),
                        enabled = !working,
                        onClick = viewModel::regenerate,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { title, time ->
                viewModel.addUserTask(title, time)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun AddTaskDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, timeOfDayMin: Int?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.care_plan_add_task)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.care_plan_task_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it },
                    label = { Text(stringResource(R.string.care_plan_time_label)) },
                    placeholder = { Text(stringResource(R.string.care_plan_time_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val minutes = parseTime(time)
                    onConfirm(title.trim(), minutes)
                },
            ) { Text(stringResource(R.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Accepts "HH:MM" (24h); blank/invalid → no reminder time (never guessed). */
private fun parseTime(raw: String): Int? {
    val match = Regex("""^(\d{1,2}):(\d{2})$""").find(raw.trim()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
