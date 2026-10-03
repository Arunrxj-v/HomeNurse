package com.homenurse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * HomeNurse design tokens — the single source of colour truth.
 *
 * Every screen and component reads these through [androidx.compose.material3.MaterialTheme]
 * or the named tokens below; no screen hardcodes a colour. The palette is a
 * calm medical green: white surfaces, very light green-tinted backgrounds,
 * dark green/charcoal text — never dark or heavy.
 *
 * All values are also mirrored into `MaterialTheme.colorScheme` in Theme.kt so
 * plain Material 3 defaults (fields, dialogs, dividers) inherit the identity.
 */
object HomeNurseColors {
    /** Primary action / brand green. */
    val Primary = Color(0xFF2E8B57)

    /** Pressed / darker green — emphasis and hover states. */
    val PrimaryDark = Color(0xFF247148)

    /** Soft green fill for tinted cards, chips and selected states. */
    val PrimaryLight = Color(0xFFDFF3E5)

    /** App background — near-white with a green whisper. */
    val Background = Color(0xFFF8FBF8)

    /** Card / sheet surface. */
    val Surface = Color(0xFFFFFFFF)

    /** Secondary surface: quiet green tinted blocks (grouped rows, zebra cards). */
    val SurfaceSoft = Color(0xFFEEF8F0)

    /** Primary text — dark green charcoal, high contrast on white. */
    val TextPrimary = Color(0xFF183326)

    /** Supporting text — muted green grey. */
    val TextSecondary = Color(0xFF617268)

    /** Hairline borders and dividers. */
    val Border = Color(0xFFD8E8DC)

    /** Positive state (done, verified, ready). */
    val Success = Color(0xFF3E9B62)

    /** Attention state (pending, paused, needs review). */
    val Warning = Color(0xFFC99632)

    /** Failure / destructive state. */
    val Error = Color(0xFFC85C5C)

    /** White (text on green, card surface — kept here so nothing hardcodes). */
    val OnPrimary = Color(0xFFFFFFFF)

    /** Error container tint for destructive notices. */
    val ErrorContainer = Color(0xFFFBECEC)

    /** Text colour on [ErrorContainer]. */
    val OnErrorContainer = Color(0xFF8E3B3B)

    /** Warning container tint for caution notices. */
    val WarningContainer = Color(0xFFFBF3E2)

    /** Text colour on [WarningContainer]. */
    val OnWarningContainer = Color(0xFF7A5A17)

    /** Green container tint for grounded / informational notices. */
    val InfoContainer = Color(0xFFE7F5EC)

    /** Text colour on [InfoContainer]. */
    val OnInfoContainer = Color(0xFF1F5E3B)

    /** Container colour for doctor-provided (from your document) blocks. */
    val DoctorContainer = Color(0xFFF1F8F3)

    /** Border for doctor-provided blocks — slightly stronger than [Border]. */
    val DoctorBorder = Color(0xFFCBE4D4)
}
