package com.astracare.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.astracare.core.designsystem.theme.AstraCareTheme
import com.astracare.core.designsystem.theme.Sizing
import com.astracare.core.designsystem.theme.Spacing
import com.astracare.core.designsystem.theme.ToneColors

/**
 * A small pill showing a piece of status.
 *
 * [contentDescription] is a separate parameter rather than being derived from [text] because
 * the two should differ: the chip reads "3 pending" in four characters of screen space, while
 * a screen reader should say "3 records waiting to sync". Making it required rather than
 * optional means the accessible wording is a decision at every call site instead of an
 * omission at most of them.
 */
@Composable
fun StatusChip(
    text: String,
    tone: StatusTone,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val colors = tone.colors()

    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = colors.content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            // clearAndSetSemantics rather than an added contentDescription: without it a
            // screen reader announces the visible text as well, so the user hears the
            // abbreviation and then the expansion.
            .clearAndSetSemantics { this.contentDescription = contentDescription }
            .clip(RoundedCornerShape(Sizing.ChipCorner))
            .background(colors.container)
            .padding(horizontal = Spacing.Tight, vertical = Spacing.Hairline),
    )
}

@Composable
private fun StatusTone.colors(): ToneColors = when (this) {
    StatusTone.Neutral -> AstraCareTheme.statusColors.neutral
    StatusTone.Info -> AstraCareTheme.statusColors.info
    StatusTone.Warning -> AstraCareTheme.statusColors.warning
    StatusTone.Critical -> AstraCareTheme.statusColors.critical
}

@Preview
@Composable
internal fun StatusChipPreview() {
    AstraCareTheme {
        StatusChip(text = "Pending", tone = StatusTone.Warning, contentDescription = "Pending sync")
    }
}
