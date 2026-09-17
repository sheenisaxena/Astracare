package com.astracare.feature.patients

import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.SyncStatus
import com.astracare.feature.patients.mvi.UiEffect
import com.astracare.feature.patients.mvi.UiIntent
import com.astracare.feature.patients.mvi.UiState

/**
 * What the record history screen can look like.
 *
 * A sealed hierarchy, where the capture form is a data class, and the reason is that these
 * three states genuinely exclude one another. Modelled as a data class this would be
 * `isLoading: Boolean` plus `records: List<Beneficiary>`, which admits `isLoading = true`
 * with forty records loaded, and `isLoading = false` with an empty list that might mean
 * "no records yet" or might mean "the query has not run". Both are states the UI then has to
 * guess about, and the guesses are where empty screens flash before content appears.
 *
 * Here `when (state)` is exhaustive, every branch renders one thing, and a fourth state
 * cannot be added without the compiler pointing at every screen that has not handled it.
 *
 * Note there is no `Error`. Reads come from Room, which is the single source of truth and is
 * local — there is no network call behind this Flow that could fail. Adding an error state
 * for a failure that cannot occur would mean writing UI nobody can ever see, and testing it
 * would mean faking a condition the real system does not produce. When Day 12 puts Paging
 * behind this, Paging's own `LoadState` arrives with the error case it actually needs.
 */
sealed interface BeneficiaryListUiState : UiState {

    /** The first emission from the database has not arrived. Genuinely unknown, not empty. */
    data object Loading : BeneficiaryListUiState

    /** The database has answered, and there are no records. */
    data object Empty : BeneficiaryListUiState

    data class Content(
        val records: List<Beneficiary>,
        /**
         * How many records are not yet on the server.
         *
         * Computed once here rather than by the composable, because a count recalculated
         * during composition is recalculated on every frame of a scroll. It is also the
         * number the screen exists to communicate: at the end of a day without signal, the
         * health worker's question is how much of their work is still only on this handset.
         */
        val awaitingSync: Int,
    ) : BeneficiaryListUiState
}

/** What the history screen reports upward. */
sealed interface BeneficiaryListIntent : UiIntent {

    data class RecordClicked(val id: BeneficiaryId) : BeneficiaryListIntent

    data object AddRecordClicked : BeneficiaryListIntent
}

/** One-shot consequences. Both are navigation, which is the definition of an effect. */
sealed interface BeneficiaryListEffect : UiEffect {

    data class OpenRecord(val id: BeneficiaryId) : BeneficiaryListEffect

    data object OpenCapture : BeneficiaryListEffect
}

/**
 * Projects the domain stream onto the screen's state.
 *
 * This screen's "reducer" is a projection rather than a fold, and that is worth naming
 * instead of hiding. A reducer computes the next state from the previous state and an event;
 * here there is no previous state to build on — Room pushes the complete, ordered list on
 * every change, and the entire UI state is a function of that one list. Keeping a
 * `MutableStateFlow` and folding emissions into it would introduce a second copy of the truth
 * that could fall out of step with the database, which is the exact bug offline-first
 * single-source-of-truth exists to prevent.
 *
 * So the transition function is `List<Beneficiary> -> UiState`, it is pure, and it is tested
 * the same way a reducer would be. The intents on this screen change no state at all: both
 * are navigation, so both produce only effects. Inventing a state change for them to justify
 * the word "reducer" would be architecture as costume.
 */
internal fun List<Beneficiary>.toUiState(): BeneficiaryListUiState =
    if (isEmpty()) {
        BeneficiaryListUiState.Empty
    } else {
        BeneficiaryListUiState.Content(
            records = this,
            awaitingSync = count { it.syncStatus != SyncStatus.SYNCED },
        )
    }
