package com.homenurse.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homenurse.ui.theme.ButtonCornerRadius
import com.homenurse.ui.theme.HomeNurseColors

/**
 * HomeNurse button set — 56 dp tall, 16 dp rounded, big readable labels.
 *
 *  * [HomeNursePrimaryButton]   — soft green filled (main action).
 *  * [HomeNurseSecondaryButton] — white with green border/text (alternate action).
 *  * [HomeNurseOutlinedButton]  — white with neutral border (quiet/cancel action).
 */

/** Primary action — green filled, white label. */
@Composable
fun HomeNursePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(ButtonCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = HomeNurseColors.Primary,
            contentColor = HomeNurseColors.OnPrimary,
            disabledContainerColor = HomeNurseColors.Border,
            disabledContentColor = HomeNurseColors.TextSecondary,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 2.dp,
            disabledElevation = 0.dp,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        ButtonContent(text, leading, trailing)
    }
}

/** Secondary action — white surface, green border and green label. */
@Composable
fun HomeNurseSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(ButtonCornerRadius),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.5.dp,
            color = if (enabled) HomeNurseColors.Primary else HomeNurseColors.Border,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (enabled) HomeNurseColors.Primary else HomeNurseColors.TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        ButtonContent(text, leading, trailing)
    }
}

/** Quiet action — white surface, neutral border and dark label (cancel/close). */
@Composable
fun HomeNurseOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        shape = RoundedCornerShape(ButtonCornerRadius),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (enabled) HomeNurseColors.Border else HomeNurseColors.Border,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (enabled) HomeNurseColors.TextPrimary else HomeNurseColors.TextSecondary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) {
        ButtonContent(text, leading, trailing)
    }
}

@Composable
private fun ButtonContent(text: String, leading: ImageVector?, trailing: ImageVector?) {
    if (leading != null) {
        Icon(imageVector = leading, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(10.dp))
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (trailing != null) {
        Spacer(modifier = Modifier.width(10.dp))
        Icon(imageVector = trailing, contentDescription = null, modifier = Modifier.size(22.dp))
    }
}
