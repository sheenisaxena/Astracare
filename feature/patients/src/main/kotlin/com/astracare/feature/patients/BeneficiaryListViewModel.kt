package com.astracare.feature.patients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astracare.core.domain.usecase.ObserveBeneficiariesUseCase
import com.astracare.core.model.Beneficiary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Proves the cross-module Hilt graph resolves end to end.
 *
 * The dependency chain assembled here spans five modules:
 *
 * ```
 * :feature:patients  BeneficiaryListViewModel
 *   -> :core:domain    ObserveBeneficiariesUseCase   (@Inject constructor)
 *     -> :core:domain    BeneficiaryRepository        (interface)
 *       -> :core:data      InMemoryBeneficiaryRepository  (bound in DataModule)
 *         -> :core:common    CoroutineDispatcher      (DispatchersModule, @Dispatcher(IO))
 *   -> :core:common    Clock                          (TimeModule)
 * ```
 *
 * None of those modules depends on `:app`, and none of them knows what the others chose. If
 * any binding were missing the build would fail — Hilt validates the graph at compile time,
 * which is the property that makes this worth doing over a service locator.
 *
 * ## Deliberately minimal
 *
 * This is a plain `StateFlow` exposure, not the MVI state machine — sealed `UiState`, `Intent`
 * and a reducer come next, along with a `Channel` for one-shot side effects. Keeping DI and
 * state management as separate steps means a failure in either is attributable.
 *
 * `@HiltViewModel` generates the factory that lets `hiltViewModel()` resolve this from a
 * composable. Without it, constructor injection into a ViewModel fails at runtime rather than
 * compile time — the one place Hilt cannot check for you.
 */
@HiltViewModel
class BeneficiaryListViewModel @Inject constructor(
    observeBeneficiaries: ObserveBeneficiariesUseCase,
) : ViewModel() {

    /**
     * `stateIn` converts the cold use-case Flow into a hot StateFlow scoped to the ViewModel.
     *
     * `WhileSubscribed(STOP_TIMEOUT_MS)` keeps the upstream alive briefly after the last
     * collector disappears, so a configuration change or a brief navigation away does not tear
     * down and re-run the database query. `Lazily` would leak the subscription for the
     * ViewModel's whole life; `Eagerly` would start it before anything is watching.
     */
    val beneficiaries: StateFlow<List<Beneficiary>> = observeBeneficiaries()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
