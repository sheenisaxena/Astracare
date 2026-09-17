package com.astracare.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val AstraLightColorScheme = lightColorScheme(
    primary = BrandPrimaryLight,
    secondary = BrandSecondaryLight,
    tertiary = BrandTertiaryLight,
)

private val AstraDarkColorScheme = darkColorScheme(
    primary = BrandPrimaryDark,
    secondary = BrandSecondaryDark,
    tertiary = BrandTertiaryDark,
)

/**
 * Status colours for the theme currently in effect.
 *
 * Carried in a `CompositionLocal` alongside the Material scheme rather than passed down, for
 * the same reason the scheme is: a component several levels deep needs them, and threading
 * them through every intermediate signature couples composables to a concern they do not
 * have.
 *
 * `staticCompositionLocalOf`, not `compositionLocalOf`. The static variant skips per-read
 * change tracking and recomposes the whole subtree when the value is replaced — cheaper for
 * something that changes only when the entire theme does, and the wrong choice for anything
 * that changes more often.
 */
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/**
 * The app's theme. Moved here from `:app` on Day 11 — which is where the README had claimed
 * it lived since Day 1. See DECISION_LOG 6.1.
 *
 * ## Dynamic colour is deliberately absent
 *
 * Material You's wallpaper-derived palette is a good default for a consumer app and the wrong
 * one here. This app uses colour to carry clinical meaning — whether a record has reached the
 * server — and that signal has to be legible on a low-end panel in direct sunlight. A palette
 * generated from whatever photograph the user set as a wallpaper cannot be contrast-checked
 * at build time, or by anyone reviewing the app, or at all.
 *
 * It is also Android 12+ only, and a meaningful share of the handsets this app targets predate
 * that (minSdk is 24). Supporting it would mean validating two palettes rather than one, for a
 * feature whose entire benefit is aesthetic.
 *
 * Removed rather than parameterised: an unused opt-in is still a code path someone has to
 * reason about, and the status colours above already sit outside the scheme, so dynamic colour
 * would not reach the part of the UI it is supposed to make cohesive.
 */
@Composable
fun AstraCareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AstraDarkColorScheme else AstraLightColorScheme
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AstraCareTypography,
            content = content,
        )
    }
}

/**
 * Accessor object, so status colours read the same way the Material ones do:
 * `AstraCareTheme.statusColors.warning` alongside `MaterialTheme.colorScheme.primary`.
 *
 * A function and an object may share a name in Kotlin — they occupy different namespaces —
 * which is the same trick `MaterialTheme` itself uses.
 */
object AstraCareTheme {
    val statusColors: StatusColors
        @Composable
        @ReadOnlyComposable
        get() = LocalStatusColors.current
}
