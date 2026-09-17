package com.astracare.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography.
 *
 * Only the styles this app actually overrides are declared; everything else inherits the
 * Material 3 defaults. The template shipped a commented-out block of every slot, which is a
 * list of decisions nobody made.
 *
 * `bodyLarge` is bumped from the Material default of 16sp to 17sp with looser line height.
 * This is a data-entry app used outdoors, at arm's length, often by someone who has been
 * working since dawn. The extra point costs nothing and the wider leading measurably helps
 * when the screen has glare on it.
 *
 * No custom font family. A bundled font is 200-400KB in an APK destined for handsets on
 * metered connections, and the system font is already tuned for the device's script — which
 * matters here, because these screens will eventually render Devanagari alongside Latin.
 */
internal val AstraCareTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.5.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        // Buttons in this app commit or discard field data. Medium weight rather than the
        // default, so the primary action is unambiguous at a glance.
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
)
