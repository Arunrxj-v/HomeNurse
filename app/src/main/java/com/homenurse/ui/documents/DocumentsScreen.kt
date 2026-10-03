package com.homenurse.ui.documents

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.domain.model.MedicalDocument
import com.homenurse.ui.components.DocumentCard
import com.homenurse.ui.components.EmptyState
import com.homenurse.ui.components.HomeNursePrimaryButton
import com.homenurse.ui.components.HomeNurseSectionHeader
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.NoticeCard
import com.homenurse.ui.components.documentStatusPresentation
import com.homenurse.ui.theme.CardCornerRadius
import com.homenurse.ui.theme.HomeNurseColors
import java.text.DateFormat
import java.util.Date

/**
 * Upload tab: documents library with an on-device privacy note, camera
 * capture, dashed import zone (PDF/image), per-document status cards,
 * open (detail/review) and real delete (encrypted file + rows).
 */
@Composable
fun DocumentsScreen(
    onBack: () -> Unit,
    onOpenCapture: () -> Unit,
    onOpenDocument: (String) -> Unit,
    viewModel: DocumentsViewModel = viewModel(),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<MedicalDocument?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importFromUri) }

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = stringResource(R.string.documents_title),
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
            contentPadding = PaddingValues(vertical = 14.dp),
        ) {
            // On-device privacy note.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(HomeNurseColors.InfoContainer)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = HomeNurseColors.OnInfoContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.documents_info),
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeNurseColors.OnInfoContainer,
                    )
                }
            }

            error?.let { kind ->
                item {
                    NoticeCard(
                        text = stringResource(
                            when (kind) {
                                DocumentError.TOO_LARGE -> R.string.error_document_too_large
                                DocumentError.STORAGE -> R.string.error_storage
                                DocumentError.UNKNOWN -> R.string.error_unknown
                            },
                        ),
                    )
                    viewModel.consumeError()
                }
            }

            // Camera capture — primary action.
            item {
                HomeNursePrimaryButton(
                    text = stringResource(R.string.documents_capture),
                    onClick = onOpenCapture,
                    enabled = !busy,
                )
            }

            // Dashed import zone.
            item {
                ImportZone(
                    enabled = !busy,
                    onImport = { importLauncher.launch(IMPORT_TYPES) },
                )
            }

            if (busy) {
                item {
                    val progress by viewModel.processingProgress.collectAsStateWithLifecycle()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (progress != null) {
                                stringResource(
                                    R.string.documents_reading_page,
                                    progress!!.page,
                                    progress!!.total,
                                )
                            } else {
                                stringResource(R.string.documents_processing)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress!!.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            item {
                HomeNurseSectionHeader(
                    title = stringResource(R.string.documents_section_files),
                    icon = Icons.Outlined.FileUpload,
                )
            }

            if (documents.isEmpty() && !busy) {
                item {
                    EmptyState(title = stringResource(R.string.documents_empty))
                }
            } else {
                items(documents, key = { it.id }) { document ->
                    val (statusLabel, statusTone) = documentStatusPresentation(document.status)
                    DocumentCard(
                        title = document.title,
                        subtitle = DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(document.createdAt)),
                        statusLabel = statusLabel,
                        statusTone = statusTone,
                        onClick = { onOpenDocument(document.id) },
                        onDelete = { pendingDelete = document },
                    )
                }
            }
        }
    }

    pendingDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.documents_delete_title)) },
            text = { Text(stringResource(R.string.documents_delete_message, document.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(document.id)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Dashed-border upload zone that opens the system file picker. */
@Composable
private fun ImportZone(enabled: Boolean, onImport: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CardCornerRadius))
            .background(HomeNurseColors.Surface)
            .drawBehind {
                drawRoundRect(
                    color = HomeNurseColors.Primary.copy(alpha = if (enabled) 0.6f else 0.25f),
                    cornerRadius = CornerRadius(CardCornerRadius.toPx()),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(20f, 14f),
                            0f,
                        ),
                    ),
                )
            }
            .clickable(enabled = enabled, onClick = onImport)
            .padding(vertical = 26.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(HomeNurseColors.PrimaryLight, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileUpload,
                    contentDescription = null,
                    tint = HomeNurseColors.Primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = stringResource(R.string.documents_zone_title),
                style = MaterialTheme.typography.titleMedium,
                color = HomeNurseColors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.documents_zone_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = HomeNurseColors.TextSecondary,
            )
        }
    }
}

private val IMPORT_TYPES = arrayOf("application/pdf", "image/*")
