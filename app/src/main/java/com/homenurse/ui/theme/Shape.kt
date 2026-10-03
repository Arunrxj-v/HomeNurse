package com.homenurse.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * HomeNurse shape scale: generously rounded cards (16–24 dp) with softer
 * corners on smaller elements. Kept in one place so cards, buttons, chips and
 * dialogs never disagree.
 */
val HomeNurseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Default corner radius for content cards ([com.homenurse.ui.components.HomeNurseCard]). */
val CardCornerRadius = 20.dp

/** Corner radius for primary and secondary buttons. */
val ButtonCornerRadius = 16.dp

/** Corner percentage for pill chips / badges (`RoundedCornerShape(50)`). */
val ChipCornerRadiusPercent = 50
