package com.astracare.feature.patients

import androidx.annotation.StringRes
import com.astracare.core.designsystem.component.StatusTone
import com.astracare.core.model.SyncStatus

/**
 * How a [SyncStatus] should look and read.
 *
 * This mapping lives in the feature module rather than in `:core:designsystem`, and that is
 * the boundary decision behind `StatusChip` taking a [StatusTone] instead of a [SyncStatus].
 * Three separate things are being decided here, none of which belongs to a design system:
 *
 *  - **Urgency.** That CONFLICTED is [StatusTone.Critical] while FAILED is only Warning is a
 *    product judgement about this app — a conflict needs a human, a failure retries itself.
 *  - **Wording.** FAILED reads "Retrying", not "Failed", because the app is already handling
 *    it and telling a health worker something failed when it has not yet given up invites
 *    them to fall back to paper.
 *  - **Language.** These are string resources, so the decision is translatable.
 *
 * A design system that made any of those calls would be one this app could not restyle
 * without changing its clinical vocabulary.
 */
internal data class SyncStatusPresentation(
    @get:StringRes val labelRes: Int,
    @get:StringRes val descriptionRes: Int,
    val tone: StatusTone,
)

/**
 * Exhaustive `when`, so adding a [SyncStatus] fails the build here.
 *
 * The alternative — a `when` with an `else` branch — would let a new status render silently as
 * whatever the fallback was, which on this screen means telling someone their record is fine
 * when the app does not actually know that.
 */
internal fun SyncStatus.presentation(): SyncStatusPresentation = when (this) {
    SyncStatus.PENDING -> SyncStatusPresentation(
        labelRes = R.string.sync_pending,
        descriptionRes = R.string.sync_pending_description,
        tone = StatusTone.Warning,
    )

    SyncStatus.SYNCED -> SyncStatusPresentation(
        labelRes = R.string.sync_synced,
        descriptionRes = R.string.sync_synced_description,
        // Neutral, not a success green. On a screen where most rows are synced, colouring
        // them all draws the eye away from the few that are not — which are the only rows
        // that need attention.
        tone = StatusTone.Neutral,
    )

    SyncStatus.FAILED -> SyncStatusPresentation(
        labelRes = R.string.sync_failed,
        descriptionRes = R.string.sync_failed_description,
        tone = StatusTone.Warning,
    )

    SyncStatus.CONFLICTED -> SyncStatusPresentation(
        labelRes = R.string.sync_conflicted,
        descriptionRes = R.string.sync_conflicted_description,
        tone = StatusTone.Critical,
    )
}
