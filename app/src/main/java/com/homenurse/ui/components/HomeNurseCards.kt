package com.homenurse.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homenurse.R
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.ProcessingStatus
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.domain.model.TaskKind
import com.homenurse.ui.theme.CardCornerRadius
import com.homenurse.ui.theme.ChipCornerRadiusPercent
import com.homenurse.ui.theme.HomeNurseColors

/**
 * HomeNurse card set: one card language (white, 20 dp rounded, hairline
 * border, soft shadow) plus the domain-specific cards — medical status,
 * medication, timeline, document, care-plan progress and AI response.
 */

/** Semantic tone used by chips, banners and status cards. */
enum class StatusTone { SUCCESS, WARNING, ERROR, INFO, NEUTRAL }

/** Soft tinted container colour for a [StatusTone]. */
fun toneContainer(tone: StatusTone): Color = when (tone) {
    StatusTone.SUCCESS -> HomeNurseColors.InfoContainer
    StatusTone.WARNING -> HomeNurseColors.WarningContainer
    StatusTone.ERROR -> HomeNurseColors.ErrorContainer
    StatusTone.INFO -> HomeNurseColors.PrimaryLight
    StatusTone.NEUTRAL -> HomeNurseColors.SurfaceSoft
}

/** Readable text/icon colour for a [StatusTone] (contrast-safe on its container). */
fun toneContent(tone: StatusTone): Color = when (tone) {
    StatusTone.SUCCESS -> HomeNurseColors.OnInfoContainer
    StatusTone.WARNING -> HomeNurseColors.OnWarningContainer
    StatusTone.ERROR -> HomeNurseColors.OnErrorContainer
    StatusTone.INFO -> HomeNurseColors.OnInfoContainer
    StatusTone.NEUTRAL -> HomeNurseColors.TextSecondary
}

/** The shared content card: white (or tinted), 20 dp rounded, hairline border. */
@Composable
fun HomeNurseCard(
    modifier: Modifier = Modifier,
    containerColor: Color = HomeNurseColors.Surface,
    contentColor: Color = HomeNurseColors.TextPrimary,
    borderColor: Color? = HomeNurseColors.Border,
    elevation: Dp = 1.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CardCornerRadius)
    val colors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor,
    )
    val border = borderColor?.let { BorderStroke(1.dp, it) }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = colors,
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            border = border,
        ) { HomeNurseCardContent(padding, content) }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            colors = colors,
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            border = border,
        ) { HomeNurseCardContent(padding, content) }
    }
}

@Composable
private fun HomeNurseCardContent(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.padding(padding), content = content)
}

/**
 * Green uppercase section label (optional leading icon and trailing
 * "View all" action) — the app's section header everywhere.
 */
@Composable
fun HomeNurseSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = HomeNurseColors.Primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = HomeNurseColors.Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = HomeNurseColors.PrimaryDark,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Small pill chip for statuses, kinds and durations. */
@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val content = toneContent(tone)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(ChipCornerRadiusPercent))
            .background(toneContainer(tone))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = content,
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Health/attention status card: soft tinted surface, icon medallion,
 * title + status line (+ optional supporting body).
 */
@Composable
fun MedicalStatusCard(
    title: String,
    status: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector = Icons.Outlined.Shield,
    tone: StatusTone = StatusTone.INFO,
) {
    HomeNurseCard(
        modifier = modifier,
        containerColor = toneContainer(tone),
        borderColor = null,
        contentColor = toneContent(tone),
        padding = PaddingValues(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(HomeNurseColors.Surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = toneContent(tone),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = toneContent(tone),
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleMedium,
                    color = HomeNurseColors.TextPrimary,
                )
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = HomeNurseColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * Medication card — every value shown here comes from the patient's own
 * confirmed document, so the doctor-provided block is visually distinct
 * (tinted, labelled "Doctor's instructions") from any HomeNurse note.
 */
@Composable
fun MedicationCard(
    name: String,
    modifier: Modifier = Modifier,
    dose: String? = null,
    frequency: String? = null,
    timing: String? = null,
    duration: String? = null,
    purpose: String? = null,
    confirmedByUser: Boolean = true,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    HomeNurseCard(modifier = modifier, padding = PaddingValues(18.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(HomeNurseColors.PrimaryLight, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Medication,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = HomeNurseColors.Primary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = HomeNurseColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (duration != null) {
                StatusChip(text = duration, tone = StatusTone.INFO)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = HomeNurseColors.TextSecondary,
                    )
                }
            }
        }

        if (purpose != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.medicines_purpose, purpose),
                style = MaterialTheme.typography.bodyMedium,
                color = HomeNurseColors.TextSecondary,
            )
        }

        // Doctor-provided values from the document — visually separated.
        Spacer(modifier = Modifier.height(14.dp))
        HomeNurseDoctorBlock(
            dose = dose,
            frequency = frequency,
            timing = timing,
        )

        if (confirmedByUser) {
            Spacer(modifier = Modifier.height(12.dp))
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
        }
    }
}

@Composable
private fun HomeNurseDoctorBlock(
    dose: String?,
    frequency: String?,
    timing: String?,
) {
    if (dose == null && frequency == null && timing == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(HomeNurseColors.DoctorContainer)
            .border(1.dp, HomeNurseColors.DoctorBorder, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.MedicalServices,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = HomeNurseColors.OnInfoContainer,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.medicines_doctor_block).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = HomeNurseColors.OnInfoContainer,
            )
        }
        if (dose != null || frequency != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                DoctorField(
                    label = stringResource(R.string.field_dose),
                    value = dose,
                    modifier = Modifier.weight(1f),
                )
                DoctorField(
                    label = stringResource(R.string.field_frequency),
                    value = frequency,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (timing != null) {
            DoctorField(
                label = stringResource(R.string.field_timing),
                value = timing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DoctorField(label: String, value: String?, modifier: Modifier = Modifier) {
    if (value == null) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = HomeNurseColors.TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = HomeNurseColors.TextPrimary,
        )
    }
}

/**
 * Timeline row: connected marker on the left (green check when done) and a
 * rounded activity card with time label, title, kind chip and controls.
 * The connecting line is drawn behind the row so it stays continuous.
 */
@Composable
fun TimelineCard(
    timeLabel: String?,
    title: String,
    kindLabel: String,
    completed: Boolean,
    modifier: Modifier = Modifier,
    kindTone: StatusTone = StatusTone.NEUTRAL,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onToggle: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val markerTop = 16.dp
    val markerRadius = 10.dp
    val markerCenterY = markerTop + markerRadius
    val lineColor = HomeNurseColors.Border

    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val x = markerRadius.toPx()
                val startY = if (isFirst) markerCenterY.toPx() else 0f
                val endY = if (isLast) markerCenterY.toPx() else size.height
                if (endY > startY) {
                    drawLine(
                        color = lineColor,
                        start = Offset(x, startY),
                        end = Offset(x, endY),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Marker: solid background so the line passes behind it cleanly.
        Box(
            modifier = Modifier
                .padding(top = markerTop)
                .size(markerRadius * 2)
                .clip(CircleShape)
                .background(
                    if (completed) HomeNurseColors.Success else HomeNurseColors.Surface,
                )
                .border(
                    width = 2.5.dp,
                    color = if (completed) HomeNurseColors.Success else HomeNurseColors.Primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (completed) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = HomeNurseColors.OnPrimary,
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))

        HomeNurseCard(
            modifier = Modifier.weight(1f),
            padding = PaddingValues(14.dp),
            onClick = onClick,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    if (!timeLabel.isNullOrBlank()) {
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = HomeNurseColors.Primary,
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (completed) {
                            HomeNurseColors.TextSecondary
                        } else {
                            HomeNurseColors.TextPrimary
                        },
                        textDecoration = if (completed) TextDecoration.LineThrough else null,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(text = kindLabel, tone = kindTone)
                if (onToggle != null) {
                    Checkbox(
                        checked = completed,
                        onCheckedChange = { onToggle() },
                        enabled = enabled,
                        colors = CheckboxDefaults.colors(
                            checkedColor = HomeNurseColors.Success,
                            uncheckedColor = HomeNurseColors.Primary,
                        ),
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = HomeNurseColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** Document row card: file medallion, title, date, status chip, delete. */
@Composable
fun DocumentCard(
    title: String,
    statusLabel: String,
    statusTone: StatusTone,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector = Icons.Outlined.Description,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    HomeNurseCard(modifier = modifier, onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(HomeNurseColors.PrimaryLight, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = HomeNurseColors.Primary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = HomeNurseColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = HomeNurseColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusChip(text = statusLabel, tone = statusTone)
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = HomeNurseColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/** Maps a document's processing status to its display label and tone. */
@Composable
fun documentStatusPresentation(status: ProcessingStatus): Pair<String, StatusTone> =
    when (status) {
        ProcessingStatus.CAPTURED, ProcessingStatus.PROCESSING ->
            stringResource(R.string.status_processing) to StatusTone.INFO
        ProcessingStatus.REVIEW_REQUIRED ->
            stringResource(R.string.status_review) to StatusTone.WARNING
        ProcessingStatus.CONFIRMED ->
            stringResource(R.string.status_confirmed) to StatusTone.SUCCESS
        ProcessingStatus.ANALYSIS_READY ->
            stringResource(R.string.status_analyzed) to StatusTone.SUCCESS
        ProcessingStatus.FAILED ->
            stringResource(R.string.status_failed) to StatusTone.ERROR
    }

/** Care-plan progress card: soft green, big percentage, rounded progress bar. */@Composable
fun CarePlanCard(
    progress: Float,
    title: String,
    modifier: Modifier = Modifier,
    stepsLabel: String? = null,
) {
    HomeNurseCard(
        modifier = modifier,
        containerColor = HomeNurseColors.PrimaryLight,
        borderColor = null,
        padding = PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = HomeNurseColors.PrimaryDark,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                color = HomeNurseColors.Primary,
            )
        }
        if (stepsLabel != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stepsLabel,
                style = MaterialTheme.typography.labelMedium,
                color = HomeNurseColors.TextSecondary,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(ChipCornerRadiusPercent)),
            color = HomeNurseColors.Primary,
            trackColor = HomeNurseColors.Surface,
            drawStopIndicator = {},
        )
    }
}
