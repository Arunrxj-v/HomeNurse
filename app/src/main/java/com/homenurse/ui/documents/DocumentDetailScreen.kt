package com.homenurse.ui.documents

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.homenurse.R
import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.ui.components.EmptyMessage
import com.homenurse.ui.components.HomeNurseTopBar
import com.homenurse.ui.components.LargeButton
import com.homenurse.ui.components.LargeOutlinedButton
import com.homenurse.ui.components.NoticeCard
import com.homenurse.ui.components.SectionCard
import java.text.DateFormat
import java.util.Date

/**
 * Document detail: preview, extracted text (inside the encrypted DB),
 * item review shortcut, local-AI explanation (with honest safety/error
 * outcomes) and stored analyses with their evidence.
 */
@Composable
fun DocumentDetailScreen(
    documentId: String,
    onBack: () -> Unit,
    onReviewFacts: () -> Unit,
    viewModel: DocumentDetailViewModel = viewModel(
        factory = DocumentDetailViewModel.factory(documentId),
    ),
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val analyses by viewModel.analyses.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val explainState by viewModel.explainState.collectAsStateWithLifecycle()
    val retrying by viewModel.retrying.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            HomeNurseTopBar(
                title = document?.title ?: stringResource(R.string.document_detail_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val doc = document
            if (doc == null) {
                EmptyMessage(stringResource(R.string.explain_not_found))
                return@Column
            }

            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(doc.createdAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Honest read status: never pretend a poor/failed read worked.
            when {
                doc.status == ProcessingStatus.FAILED -> {
                    NoticeCard(
                        text = stringResource(
                            when (doc.failureReason) {
                                "no_text" -> R.string.document_read_no_text
                                "low_ocr_quality" -> R.string.document_read_poor
                                else -> R.string.document_read_failed
                            },
                        ),
                    )
                    LargeOutlinedButton(
                        text = stringResource(R.string.document_retry_read),
                        enabled = !retrying,
                        onClick = viewModel::retry,
                    )
                }

                doc.failureReason == "low_ocr_quality" -> {
                    NoticeCard(text = stringResource(R.string.document_read_poor))
                    LargeOutlinedButton(
                        text = stringResource(R.string.document_retry_read),
                        enabled = !retrying,
                        onClick = viewModel::retry,
                    )
                }

                doc.status == ProcessingStatus.PROCESSING -> {
                    val progress by viewModel.processingProgress.collectAsStateWithLifecycle()
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

            preview?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.document_preview),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                )
            }

            val pendingCount = facts.count { it.status == FactStatus.PENDING }
            if (pendingCount > 0) {
                LargeButton(
                    text = stringResource(R.string.document_review_items, pendingCount),
                    onClick = onReviewFacts,
                )
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.document_tab_text)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.document_tab_analysis)) },
                )
            }

            when (selectedTab) {
                0 -> SectionCard {
                    Text(
                        text = doc.extractedText
                            ?: stringResource(R.string.document_no_text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.heightIn(max = 400.dp),
                    )
                }

                else -> {
                    // Local AI explanation controls + stored analyses.
                    LargeButton(
                        text = stringResource(R.string.document_explain),
                        onClick = viewModel::explain,
                        enabled = explainState !is ExplainState.Loading,
                    )

                    when (val state = explainState) {
                        ExplainState.Loading -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                            Text(
                                text = stringResource(R.string.document_explaining),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        is ExplainState.Error -> NoticeCard(text = stringResource(state.messageRes))
                        is ExplainState.SafetyBlocked -> NoticeCard(text = state.message)
                        ExplainState.Success -> {
                            Text(
                                text = stringResource(R.string.document_analysis_saved),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            viewModel.consumeExplainResult()
                        }
                        ExplainState.Idle -> Unit
                    }

                    if (analyses.isEmpty()) {
                        EmptyMessage(stringResource(R.string.document_no_analysis))
                    } else {
                        analyses.forEach { analysis ->
                            SectionCard {
                                Text(
                                    text = analysis.summary,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = analysis.explanation,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                analysis.warnings.forEach { warning ->
                                    Text(
                                        text = "! $warning",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (analysis.suggestedNextSteps.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.document_next_steps),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    analysis.suggestedNextSteps.forEach { step ->
                                        Text(
                                            text = "• $step",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                if (analysis.evidence.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.document_evidence),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    analysis.evidence.forEach { evidence ->
                                        Text(
                                            text = "• ${evidence.label}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
