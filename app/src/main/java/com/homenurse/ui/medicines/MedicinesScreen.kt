package com.homenurse.ui.medicines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.domain.model.Medication
import com.homenurse.ui.components.EmptyState
import com.homenurse.ui.components.HomeNurseCard
import com.homenurse.ui.components.HomeNurseSectionHeader
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.MedicationCard
import com.homenurse.ui.components.StatusChip
import com.homenurse.ui.components.StatusTone
import com.homenurse.ui.theme.ChipCornerRadiusPercent
import com.homenurse.ui.theme.HomeNurseColors

/**
 * Medicines list → tap for detail. Everything shown (dose, frequency,
 * timing) was extracted from the patient's own document and confirmed by
 * them during review — HomeNurse never invents or adjusts dosing. The
 * doctor-provided values render inside the tinted "Doctor's instructions"
 * block; the HomeNurse note is styled separately so assistant text is
 * never confused with doctor-provided information.
 */
@Composable
fun MedicinesScreen(
    onBack: () -> Unit,
    viewModel: MedicinesViewModel = viewModel(),
) {
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val detailFact by viewModel.detailFact.collectAsStateWithLifecycle()
    val detailDocumentTitle by viewModel.detailDocumentTitle.collectAsStateWithLifecycle()

    val selected = detail
    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(
                    if (selected == null) R.string.medicines_title else R.string.medicines_detail_title,
                ),
                onBack = if (selected == null) onBack else viewModel::closeDetail,
            )
        },
    ) { padding ->
        if (selected != null) {
            MedicationDetail(
                medication = selected,
                factValue = detailFact?.value,
                sourceDocumentTitle = detailDocumentTitle,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
        } else if (medications.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            ) {
                EmptyState(
                    title = stringResource(R.string.medicines_empty),
                    icon = Icons.Outlined.Info,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                // HomeNurse note — deliberately styled unlike doctor-provided data.
                item {
                    HomeNurseNote(text = stringResource(R.string.medicines_intro))
                }
                items(medications, key = { it.id }) { medication ->
                    MedicationCard(
                        name = medication.name,
                        dose = medication.dose,
                        frequency = medication.frequency,
                        timing = medication.timing,
                        duration = medication.duration,
                        confirmedByUser = medication.confirmedByUser,
                        onClick = { viewModel.openDetail(medication) },
                    )
                }
            }
        }
    }
}

/**
 * Detail: prescribed values, verbatim doctor instructions from the source
 * document, the source document itself and confirmation status. No AI
 * text appears on this screen.
 */
@Composable
private fun MedicationDetail(
    medication: Medication,
    factValue: String?,
    sourceDocumentTitle: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MedicationCard(
            name = medication.name,
            dose = medication.dose,
            frequency = medication.frequency,
            timing = medication.timing,
            duration = medication.duration,
            confirmedByUser = medication.confirmedByUser,
        )

        if (factValue != null) {
            HomeNurseSectionHeader(
                title = stringResource(R.string.doctor_instructions),
                icon = Icons.Outlined.MedicalServices,
            )
            Spacer(modifier = Modifier.height(4.dp))
            HomeNurseCard(
                containerColor = HomeNurseColors.DoctorContainer,
                borderColor = HomeNurseColors.DoctorBorder,
                padding = PaddingValues(16.dp),
            ) {
                Text(
                    text = factValue,
                    style = MaterialTheme.typography.bodyLarge,
                    color = HomeNurseColors.TextPrimary,
                )
            }
        }

        if (sourceDocumentTitle != null) {
            HomeNurseSectionHeader(
                title = stringResource(R.string.medicines_detail_source),
                icon = Icons.Outlined.Description,
            )
            Spacer(modifier = Modifier.height(4.dp))
            HomeNurseCard(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = HomeNurseColors.Primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = sourceDocumentTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = HomeNurseColors.TextPrimary,
                    )
                }
            }
        }

        if (medication.confirmedByUser) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = HomeNurseColors.Success,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.medicines_confirmed_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = HomeNurseColors.TextSecondary,
                )
            }
        } else {
            StatusChip(
                text = stringResource(R.string.medicines_source_badge),
                tone = StatusTone.NEUTRAL,
            )
        }
    }
}

/** Assistant-provided note: neutral tint, distinct from doctor blocks. */
@Composable
private fun HomeNurseNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ChipCornerRadiusPercent))
            .background(HomeNurseColors.SurfaceSoft)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = HomeNurseColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.medicines_note_badge).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = HomeNurseColors.TextSecondary,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = HomeNurseColors.TextSecondary,
            )
        }
    }
}
