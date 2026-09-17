package com.astracare.core.designsystem.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * Every gap, pad and inset in the app comes from here. Three reasons, in increasing order of
 * how much they matter:
 *
 *  1. A screen assembled from `16.dp`, `12.dp` and `18.dp` written by whoever was there that
 *     afternoon does not look designed, and the drift is invisible in review because each
 *     individual number looks reasonable.
 *  2. Changing the density of the whole app becomes one edit rather than a search.
 *  3. detekt's `MagicNumber` rule is active project-wide and excludes only the design-system
 *     and theme directories. A literal `16.dp` in a feature module is therefore a build
 *     failure — so the lint configuration and the design system point the same way, and the
 *     rule does work rather than being suppressed. Deliberate; see DECISION_LOG 6.2.
 *
 * Named by role rather than by size. `Gutter` survives a decision to make it 20.dp;
 * `Space16` does not.
 */
object Spacing {

    /** Between tightly related elements — an icon and its label. */
    val Hairline: Dp = 4.dp

    /** Between elements in the same group — stacked form fields. */
    val Tight: Dp = 8.dp

    /** The default gap between distinct elements. */
    val Snug: Dp = 12.dp

    /** Screen edge inset, and the gap between sections. */
    val Gutter: Dp = 16.dp

    /** Between major blocks — a form and its action row. */
    val Loose: Dp = 24.dp

    /** Bottom padding on a scrolling list, so the last row clears a floating action button. */
    val ScrollTail: Dp = 88.dp
}

/**
 * Sizes that are not spacing: corner radii, minimum touch targets, stroke widths.
 *
 * [MinTouchTarget] is 48.dp because that is the Material accessibility minimum, and it is
 * called out rather than left implicit: this app is used one-handed, outdoors, sometimes by
 * someone wearing gloves. A 40.dp tap target that tests fine on a desk fails in a village.
 */
object Sizing {

    val MinTouchTarget: Dp = 48.dp

    val ChipCorner: Dp = 8.dp

    val CardCorner: Dp = 12.dp
}
