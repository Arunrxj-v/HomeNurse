package com.homenurse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.homenurse.R
import com.homenurse.domain.model.ConversationMessage
import com.homenurse.domain.model.ConversationRole
import com.homenurse.domain.model.SafetyLevel
import com.homenurse.ui.theme.HomeNurseColors

/**
 * Ask-screen response presentation. This file is **presentation only**: it
 * classifies what the safety engine / local model already produced so the UI
 * can visually distinguish
 *
 *  1. answers grounded in the user's confirmed local documents,
 *  2. general medical information (the model said the records don't say),
 *  3. safety warnings (SafetyEngine output — behaviour unchanged),
 *  4. doctor instructions (the model deferred to a clinician),
 *
 * without changing any grounding or safety logic.
 */

/** What kind of answer a message is, for display purposes. */
enum class AiResponseKind { GROUNDED, GENERAL, SAFETY }

/** Phrases the model uses when the confirmed records do not contain an answer. */
private val NOT_IN_RECORDS = Regex(
    """(?i)(do not have that information|don'?t have that information|"""
        + """not (?:found )?in your (?:confirmed )?(?:records|documents)|"""
        + """no (?:such )?(?:information|detail)s? (?:is |are )?(?:in|found in) your (?:confirmed )?(?:records|documents)|"""
        + """not (?:available|mentioned) in your (?:confirmed )?(?:records|documents))""",
)

/** Sentences where the model defers to a clinician ("doctor instructions"). */
private val DOCTOR_DEFERRAL = Regex(
    """(?i)(discuss this with your doctor|consult (?:your|a) doctor|contact your doctor|"""
        + """see your doctor|talk to your doctor|doctor or clinic|doctor or pharmacist|"""
        + """prescriber|pharmacist)""",
)

/** Classifies a stored message for display. Pure function — no behaviour change. */
fun classifyAiResponse(message: ConversationMessage): AiResponseKind = when (message.role) {
    ConversationRole.SAFETY -> AiResponseKind.SAFETY
    ConversationRole.USER -> AiResponseKind.GENERAL
    ConversationRole.MODEL ->
        if (NOT_IN_RECORDS.containsMatchIn(message.content)) {
            AiResponseKind.GENERAL
        } else {
            AiResponseKind.GROUNDED
        }
}

/** True when the model deferred the question to a doctor/pharmacist. */
fun hasDoctorDeferral(content: String): Boolean = DOCTOR_DEFERRAL.containsMatchIn(content)

@Composable
private fun kindLabel(kind: AiResponseKind): String = when (kind) {
    AiResponseKind.GROUNDED -> stringResource(R.string.ai_badge_grounded)
    AiResponseKind.GENERAL -> stringResource(R.string.ai_badge_general)
    AiResponseKind.SAFETY -> stringResource(R.string.ai_badge_safety)
}

private fun kindIcon(kind: AiResponseKind): ImageVector = when (kind) {
    AiResponseKind.GROUNDED -> Icons.Outlined.Description
    AiResponseKind.GENERAL -> Icons.Outlined.Info
    AiResponseKind.SAFETY -> Icons.Outlined.Shield
}

/**
 * Assistant response card: badge header (grounded / general / safety),
 * body text, warning block, uncertainty line and doctor-deferral note.
 */
@Composable
fun AIResponseCard(
    message: ConversationMessage,
    modifier: Modifier = Modifier,
) {
    val kind = classifyAiResponse(message)

    // Presentation-only tone: urgent/emergency safety output reads red,
    // cautions amber, everything else the calm green identity.
    val tone = when {
        kind == AiResponseKind.SAFETY && message.safetyLevel in
            setOf(SafetyLevel.URGENT, SafetyLevel.EMERGENCY) -> StatusTone.ERROR
        kind == AiResponseKind.SAFETY -> StatusTone.WARNING
        kind == AiResponseKind.GENERAL -> StatusTone.NEUTRAL
        else -> StatusTone.INFO
    }

    val lines = message.content.lines()
    val warningLines = lines
        .map { it.trim() }
        .filter { it.startsWith("! ") }
        .map { it.removePrefix("! ").trim() }
    val bodyLines = lines.filterNot { it.trim().startsWith("! ") }
    val uncertainty = bodyLines.firstOrNull { it.trim().startsWith("Uncertainty:") }
    val body = bodyLines
        .filterNot { it === uncertainty }
        .joinToString("\n")
        .trim()
    val doctorNote = hasDoctorDeferral(message.content)

    HomeNurseCard(
        modifier = modifier,
        containerColor = toneContainer(tone),
        borderColor = when (tone) {
            StatusTone.ERROR -> HomeNurseColors.Error
            StatusTone.WARNING -> HomeNurseColors.Warning
            StatusTone.NEUTRAL -> HomeNurseColors.Border
            else -> HomeNurseColors.DoctorBorder
        },
        contentColor = HomeNurseColors.TextPrimary,
        padding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        // Kind badge (+ doctor badge when the model deferred to a clinician).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = kindIcon(kind),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = toneContent(tone),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = kindLabel(kind).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = toneContent(tone),
                fontWeight = FontWeight.SemiBold,
            )
            if (doctorNote) {
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(
                    text = stringResource(R.string.ai_badge_doctor),
                    tone = StatusTone.INFO,
                    icon = Icons.Outlined.MedicalServices,
                )
            }
        }

        if (body.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                color = HomeNurseColors.TextPrimary,
            )
        }

        if (warningLines.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HomeNurseColors.WarningContainer)
                    .border(1.dp, HomeNurseColors.Warning, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = HomeNurseColors.OnWarningContainer,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.ai_warnings_label).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = HomeNurseColors.OnWarningContainer,
                    )
                }
                warningLines.forEach { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = HomeNurseColors.OnWarningContainer,
                    )
                }
            }
        }

        if (uncertainty != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = uncertainty,
                style = MaterialTheme.typography.bodySmall,
                color = HomeNurseColors.TextSecondary,
            )
        }

        if (doctorNote) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(HomeNurseColors.DoctorContainer)
                    .border(1.dp, HomeNurseColors.DoctorBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.MedicalServices,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = HomeNurseColors.OnInfoContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.ai_doctor_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = HomeNurseColors.OnInfoContainer,
                )
            }
        }
    }
}

/** The user's own message: right-aligned soft green bubble. */
@Composable
fun ChatUserBubble(
    content: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = HomeNurseColors.Primary,
            contentColor = HomeNurseColors.OnPrimary,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 6.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                color = HomeNurseColors.OnPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
