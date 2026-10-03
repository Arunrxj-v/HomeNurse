package com.homenurse.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Legacy component names kept as thin wrappers over the HomeNurse design
 * system so every existing screen (settings, capture, fact review, licenses,
 * auth…) inherits the new look without changing its call sites.
 */

/** Primary action button — min 56 dp tall, full width. */
@Composable
fun LargeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) {
    HomeNursePrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leading = leading,
    )
}

/** Secondary action button — same size, outlined green style. */
@Composable
fun LargeOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    HomeNurseSecondaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    )
}

/** Elevated content card used across screens. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    HomeNurseCard(modifier = modifier, content = content)
}

/** Centered spinner for blocking operations. */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    LoadingState(modifier = modifier)
}

/** Honest, friendly empty-state message. */
@Composable
fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    EmptyState(title = text, modifier = modifier)
}

/** Error/notice strip (also used for safety messages). */
@Composable
fun NoticeCard(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
) {
    HomeNurseCard(
        modifier = modifier,
        containerColor = containerColor,
        contentColor = contentColor,
        borderColor = null,
        elevation = 0.dp,
        padding = PaddingValues(16.dp),
    ) {
        androidx.compose.material3.Text(
            text = text,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )
    }
}

/** Human-readable byte size for storage/model figures. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 ->
        String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L ->
        String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
