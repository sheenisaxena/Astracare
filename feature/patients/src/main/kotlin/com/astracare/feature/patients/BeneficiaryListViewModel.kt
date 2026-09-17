package com.astracare.feature.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.astracare.core.domain.usecase.ObserveAwaitingSyncCountUseCase
import com.astracare.core.domain.usecase.ObserveBeneficiariesUseCase
import com.astracare.core.model.Beneficiary
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
 * Still proves what it proved on Day 8 — that the cross-module Hilt graph resolves end to end,
 * from this ViewModel through `:core:domain`'s use cases and repository interface to
 * `:core:data`'s Room-backed implementation, with none of those modules depending on `:app`.
 *
 * Two outputs, for two different kinds of thing:
 *
 *  - [records] is a paged stream. It is deliberately **not** a `StateFlow`: `PagingData` is a
 *    stream of change events that the Compose layer replays into a list, not a value with a
 *    meaningful "current" reading. Holding the latest one in a `StateFlow` would hand a new
 *    collector a snapshot of someone else's scroll position.
 *  - [syncSummary] is a `StateFlow`, because a count is exactly the kind of thing that has a
 *    current value.
 *
 * ## Why cachedIn is not optional
 *
 * `cachedIn(viewModelScope)` makes the paged flow shareable and keeps loaded pages alive for
 * the ViewModel's lifetime. Without it two things break, and only one of them is obvious:
 *
 *  - On a configuration change the Pager restarts from page zero, so the health worker who had
 *    scrolled forty records into their day's work is returned to the top by rotating the phone.
 *  - Collecting the same `PagingData` flow more than once throws at runtime. A screen that
 *    renders the list and also, say, reads its load state elsewhere would crash — and it would
 *    crash in whichever build someone first wrote that second collector, not in this one.
 *
 * It must be the last operator applied. Anything after it runs per collector and defeats it.
 */
@HiltViewModel
class BeneficiaryListViewModel @Inject constructor(
    observeBeneficiaries: ObserveBeneficiariesUseCase,
    observeAwaitingSyncCount: ObserveAwaitingSyncCountUseCase,
) : ViewModel() {

    val records: Flow<PagingData<Beneficiary>> = observeBeneficiaries()
        .cachedIn(viewModelScope)

    /**
     * `WhileSubscribed(STOP_TIMEOUT_MS)` keeps the count query alive briefly after the last
     * collector disappears, so a configuration change or a short trip to the capture screen
     * does not tear it down and re-run it. `Lazily` would hold the subscription for the
     * ViewModel's whole life; `Eagerly` would start it before anything is watching.
     */
    val syncSummary: StateFlow<SyncSummaryUiState> = observeAwaitingSyncCount()
        .map(::SyncSummaryUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SyncSummaryUiState.Unknown,
        )

    private val internalEffects = Channel<BeneficiaryListEffect>(Channel.BUFFERED)
    val effects: Flow<BeneficiaryListEffect> = internalEffects.receiveAsFlow()

    /**
     * Every intent on this screen is navigation, so every one produces an effect and none
     * touches state. Routing them through the ViewModel rather than letting the composable
     * call the navigator directly keeps the screen ignorant of where it sits in the graph,
     * which is what lets it be previewed, tested and reused in a tablet two-pane layout
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
