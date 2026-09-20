package com.astracare.feature.patients

import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.UserRole
import com.astracare.feature.patients.mvi.UiEffect
import com.astracare.feature.patients.mvi.UiIntent
import com.astracare.feature.patients.mvi.UiState

/**
 * The sync banner at the top of the history screen.
 *
 * ## What happened to BeneficiaryListUiState
 *
 * Day 10 gave this screen a sealed `Loading | Empty | Content`, and the argument for it was
 * that the three genuinely exclude one another and a raw `List<Beneficiary>` could not tell
 * "no records" from "not asked yet". That argument was right, and Paging now answers the same
 * question better: `LazyPagingItems.loadState` reports refresh, append and prepend separately,
 * which is strictly more information than one enum could carry. Keeping both would mean two
 * sources of truth about what the screen is doing, and they would disagree during a reload.
 *
 * So the sealed state is gone rather than wrapped. What could not come from Paging is the
 * count of unsynced records — paged data holds only the rows near the viewport, so counting it
 * would report a number that changes as the user scrolls. That is a separate query, and this
 * is the small piece of state left over to carry it.
 *
 * Day 10's note that this screen needed no `Error` case still holds, and for the same reason:
 * the source is a local database. Paging brings an error branch with it, and it stays
 * unhandled until `RemoteMediator` on Days 13-14 makes failure something that can actually
 * happen. See DECISION_LOG 7.3.
 */
data class SyncSummaryUiState(
    val awaitingSync: Int,
) : UiState {

    val isEverythingSynced: Boolean get() = awaitingSync == 0

    companion object {
        /**
         * Before the count query has answered.
         *
         * Zero, not null or a spinner: the banner is a reassurance, not a result, and
         * flickering "0 waiting" to "11 waiting" a frame later is less alarming than an
         * indeterminate state on a screen whose whole job is telling someone their work is
         * safe.
         */
        val Unknown = SyncSummaryUiState(awaitingSync = 0)
    }
}

/** What the history screen reports upward. */
sealed interface BeneficiaryListIntent : UiIntent {

    data class RecordClicked(val id: BeneficiaryId) : BeneficiaryListIntent

    data object AddRecordClicked : BeneficiaryListIntent

    /**
     * The stand-in for signing in as someone else. Day 17.
     *
     * Carries the target role rather than being a `ToggleRole` with no payload, even though
     * there are exactly two roles and a toggle would work today. A third role turns a toggle
     * into a question with no answer, and the intent would have to change shape at the moment
     * the permission model is already changing.
     */
    data class RoleSelected(val role: UserRole) : BeneficiaryListIntent

    data object AuditTrailClicked : BeneficiaryListIntent
}

/** One-shot consequences. All three are navigation, which is the definition of an effect. */
sealed interface BeneficiaryListEffect : UiEffect {

    data class OpenRecord(val id: BeneficiaryId) : BeneficiaryListEffect

    data object OpenCapture : BeneficiaryListEffect

    data object OpenAuditTrail : BeneficiaryListEffect
}
