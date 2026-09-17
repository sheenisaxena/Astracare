package com.astracare.feature.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astracare.core.domain.usecase.ObserveBeneficiariesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State machine for the record history screen.
 *
 * Still proves what it proved on Day 8 — that the cross-module Hilt graph resolves end to
 * end, from this ViewModel through `:core:domain`'s use case and repository interface to
 * `:core:data`'s Room-backed implementation and `:core:common`'s dispatchers, with none of
 * those modules depending on `:app`. What changed on Day 10 is that it no longer exposes a
 * raw `List<Beneficiary>` for the UI to interpret.
 *
 * ## What a raw list forced onto the UI
 *
 * `StateFlow<List<Beneficiary>>` starting at `emptyList()` cannot distinguish "the database
 * has not answered yet" from "there are no records". A composable given that list has to
 * choose, and whichever it chooses is wrong half the time: show the empty state and it
 * flashes "no records yet" for a frame before the data arrives; show a spinner and it never
 * stops for a health worker who genuinely has no records. The information needed to decide
 * was never in the type. [BeneficiaryListUiState] puts it there.
 *
 * ## Why there is no MutableStateFlow here
 *
 * The state is derived, not owned. It is the database's list, projected — so it is built with
 * `map` and `stateIn` over the upstream Flow, and the only way to change it is to change the
 * database. A `MutableStateFlow` fed by a collector would be a second copy of the truth,
 * free to disagree with Room the moment anything wrote to it directly.
 *
 * This is also the concrete reason `:feature:patients` has no generic `MviViewModel` base
 * class: any base class general enough to hold both this screen and
 * [com.astracare.feature.patients.capture.CaptureViewModel] would have to own a mutable state
 * field, and this screen must not have one. See `mvi/Mvi.kt`.
 */
@HiltViewModel
class BeneficiaryListViewModel @Inject constructor(
    observeBeneficiaries: ObserveBeneficiariesUseCase,
) : ViewModel() {

    /**
     * `WhileSubscribed(STOP_TIMEOUT_MS)` keeps the upstream alive briefly after the last
     * collector disappears, so a configuration change or a brief navigation away does not
     * tear down and re-run the database query. `Lazily` would hold the subscription for the
     * ViewModel's whole life; `Eagerly` would start it before anything is watching.
     *
     * `Loading` is the initial value because that is the truth before the first emission —
     * the alternative, an empty `Content`, is a claim about the database made before asking
     * it.
     */
    val uiState: StateFlow<BeneficiaryListUiState> = observeBeneficiaries()
        .map { it.toUiState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = BeneficiaryListUiState.Loading,
        )

    private val internalEffects = Channel<BeneficiaryListEffect>(Channel.BUFFERED)
    val effects: Flow<BeneficiaryListEffect> = internalEffects.receiveAsFlow()

    /**
     * Every intent on this screen is navigation, so every one produces an effect and none
     * touches state. Routing them through the ViewModel rather than letting the composable
     * call the navigator directly keeps the screen ignorant of where it sits in the graph,
     * which is what lets it be previewed, tested and reused on a tablet two-pane layout
     * without changing it.
     */
    fun dispatch(intent: BeneficiaryListIntent) {
        val effect = when (intent) {
            is BeneficiaryListIntent.RecordClicked -> BeneficiaryListEffect.OpenRecord(intent.id)
            BeneficiaryListIntent.AddRecordClicked -> BeneficiaryListEffect.OpenCapture
        }
        viewModelScope.launch { internalEffects.send(effect) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
