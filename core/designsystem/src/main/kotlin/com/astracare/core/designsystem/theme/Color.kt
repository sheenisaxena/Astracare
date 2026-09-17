package com.astracare.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette.
 *
 * Deliberately not the template's Purple80/Pink40 set. Those names describe a hue, which is
 * the one thing a token should not encode — change the colour and every name lies. These are
 * named for their role in the scheme instead.
 *
 * Teal rather than purple for the primary, for a reason specific to this app: the status
 * colours below carry clinical meaning and must stay distinguishable from the brand colour on
 * a cheap panel in daylight. A purple primary sits too close to the amber-to-red attention
 * range once the screen washes out.
 */
internal val BrandPrimaryLight = Color(0xFF00696E)
internal val BrandPrimaryDark = Color(0xFF4FD8DF)
internal val BrandSecondaryLight = Color(0xFF4A6365)
internal val BrandSecondaryDark = Color(0xFFB1CBCD)
internal val BrandTertiaryLight = Color(0xFF4B607C)
internal val BrandTertiaryDark = Color(0xFFB3C8E8)

/**
 * A container colour and the colour of content drawn on it.
 *
 * Paired, so contrast is settled where the colours are defined rather than by whoever uses
 * them. A component that takes a background and lets the caller pick the foreground is a
 * component that will eventually be given an unreadable combination.
 */
data class ToneColors(val container: Color, val content: Color)

/**
 * The four semantic tones available to status components.
 *
 * Kept out of the Material colour scheme deliberately. `colorScheme.error` is a *theming*
 * slot — its meaning is "this looks like an error in this palette". A tone here means "this
 * record has not reached the server", which is a clinical signal that must survive any
 * repalette. Mapping one onto the other would couple the two, and they would drift the first
 * time anyone adjusted the brand colours.
 */
data class StatusColors(
    val neutral: ToneColors,
    val info: ToneColors,
    val warning: ToneColors,
    val critical: ToneColors,
)

internal val LightStatusColors = StatusColors(
    neutral = ToneColors(Color(0xFFE1E3E3), Color(0xFF1A1C1D)),
    info = ToneColors(Color(0xFFCCE8E9), Color(0xFF00363A)),
    warning = ToneColors(Color(0xFFFFDEA6), Color(0xFF271900)),
    critical = ToneColors(Color(0xFFFFDAD6), Color(0xFF410002)),
)

internal val DarkStatusColors = StatusColors(
    neutral = ToneColors(Color(0xFF3F4849), Color(0xFFDEE3E4)),
    info = ToneColors(Color(0xFF004F53), Color(0xFF9FF0F5)),
    warning = ToneColors(Color(0xFF5C4300), Color(0xFFFFDEA6)),
    critical = ToneColors(Color(0xFF93000A), Color(0xFFFFDAD6)),
)
