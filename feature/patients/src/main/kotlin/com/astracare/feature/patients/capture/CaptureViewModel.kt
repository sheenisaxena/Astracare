package com.astracare.feature.patients.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astracare.core.common.Outcome
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.usecase.RestoreDraftUseCase
import com.astracare.core.domain.usecase.SaveBeneficiaryUseCase
import com.astracare.core.domain.usecase.SaveDraftUseCase
import com.astracare.core.domain.usecase.SaveError
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.CaptureDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * State machine for the beneficiary capture form.
 *
 * Holds two outputs, and the difference between them is the point of the MVI work:
 *
 *  - [uiState] is a `StateFlow`. It always has a current value, it is replayed to every new
 *    collector, and it survives configuration changes untouched. That is correct for "what
 *    does this screen look like".
 *  - [effects] is a `Channel` exposed as a `Flow`. Each element is delivered to exactly one
 *    collector, exactly once, and is gone. That is correct for "navigate back", which must
 *    not happen a second time because the device was rotated.
 *
 * ## Why a Channel rather than a SharedFlow for effects
 *
 * `MutableSharedFlow(replay = 0)` is the usual alternative and it drops events emitted while
 * nothing is collecting. There is a real window here: the save completes, the composable is
 * mid-recomposition or the app is briefly backgrounded, and the `Saved` event is discarded —
 * leaving the health worker on a form whose record has already been written, with no
 * indication either way. A `BUFFERED` channel holds the event until someone collects it.
 *
 * Raising `replay` to 1 does not fix it, it inverts it: the event is then re-delivered to the
 * next collector, so returning to the screen navigates away again.
 *
 * `receiveAsFlow()`, not `consumeAsFlow()`: the latter cancels the channel when its first
 * collector is cancelled, which happens on every configuration change, so the second
 * collector would receive nothing for the rest of the ViewModel's life.
 *
 * ## Why SaveBeneficiaryUseCase is injected here and not into the list ViewModel
 *
 * It was removed from `BeneficiaryListViewModel` on Day 8 when detekt flagged it as injected
 * but unused. That was the right call and this is the reason: a list screen does not save.
 * Suppressing the warning would have kept a dependency whose only justification was that it
 * would be needed eventually — on the wrong class. It is needed here, where it is used.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val saveBeneficiary: SaveBeneficiaryUseCase,
    private val restoreDraft: RestoreDraftUseCase,
    private val saveDraft: SaveDraftUseCase,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val internalState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = internalState.asStateFlow()

    private val internalEffects = Channel<CaptureEffect>(Channel.BUFFERED)
    val effects: Flow<CaptureEffect> = internalEffects.receiveAsFlow()

    /**
     * Restore, then autosave — in that order, in one coroutine.
     *
     * The ordering is not stylistic. If autosave began collecting first it would immediately
     * observe the blank initial state, write it, and the restore would then be overwriting a
     * draft it had already destroyed. Sequencing them in a single coroutine makes that
     * impossible rather than unlikely: `collect` never returns, so nothing after the restore
     * can run before it completes.
     */
    init {
        viewModelScope.launch {
            restore()
            autosave()
        }
    }

    /**
     * The screen's single entry point.
     *
     * One function rather than `onNameChanged`, `onSaveClicked` and four more: the composable
     * needs one parameter instead of six, and adding an interaction later does not change its
     * signature. It also means every state change in this screen passes through one line,
     * which is the line to put a breakpoint or an analytics hook on.
     */
    fun dispatch(intent: CaptureIntent) {
        // getAndUpdate applies the reducer atomically and hands back what the state was
        // beforehand. The previous value is what decides whether work should start — asking
        // the new state would see isSaving already true and never launch anything.
        val previous = internalState.getAndUpdate { it.reduce(intent) }

        when (intent) {
            is CaptureIntent.FieldChanged -> Unit
            CaptureIntent.SaveClicked -> if (!previous.isSaving) save()
            CaptureIntent.DiscardClicked -> discard()
        }
    }

    private suspend fun restore() {
        val draft = restoreDraft() ?: return
        // Assigned rather than reduced: this is not a transition. There is no previous state
        // to build on, because nothing has happened to the form yet — the screen has not
        // accepted a keystroke. `toDraft`/`toUiState` are pure and round-trip, which is what
        // keeps this a seeding step rather than state arithmetic in the ViewModel.
        internalState.value = draft.toUiState()
    }

    /**
     * Persists the form a short pause after typing stops.
     *
     * `debounce` rather than writing on every keystroke: a write per character is a database
     * transaction per character, on a low-end handset, while someone is typing into it. 400ms
     * is long enough to coalesce a burst of typing and short enough that anything lost to a
     * sudden power cut is a word rather than a form.
     *
     * `distinctUntilChanged` **before** the debounce, on the mapped draft rather than on the
     * state: focus changes, save attempts and error clearing all produce a new
     * [CaptureUiState] without changing a single character of typed text. Comparing drafts
     * means those do not restart the timer or trigger a redundant write.
     *
     * This collector never completes, which is deliberate — it is cancelled with
     * `viewModelScope` when the ViewModel is cleared.
     */
    private suspend fun autosave() {
        internalState
            .map { it.toDraft() }
            .distinctUntilChanged()
            .debounce(AUTOSAVE_DEBOUNCE_MS)
            .collect { saveDraft(it) }
    }

    private fun save() {
        viewModelScope.launch {
            val outcome = attemptSave()
            internalState.update { it.reduce(outcome) }

            when (outcome) {
                SaveOutcome.Succeeded -> {
                    // Cleared explicitly rather than left to the debounced autosave, which
                    // would not fire for another 400ms. If the process died inside that
                    // window the draft would outlive the record it produced, and the next
                    // launch would restore a form for a visit that is already saved — an
                    // invitation to enter the same child twice.
                    saveDraft(CaptureDraft.Empty)
                    internalEffects.send(CaptureEffect.Saved)
                }

                is SaveOutcome.Failed -> internalEffects.send(CaptureEffect.SaveFailed(outcome.error))

                // No effect. A rejected form is not an event that happened once — it is a
                // condition the screen is now in, and the reducer has already put it in state
                // where it will still be after a rotation.
                is SaveOutcome.Rejected -> Unit
            }
        }
    }

    /**
     * Discarding throws away the draft as well as the form.
     *
     * Without this the record would reappear on the next launch, which is the opposite of
     * what "discard" means — and the health worker would reasonably conclude the app cannot
     * be trusted to forget something either.
     */
    private fun discard() {
        viewModelScope.launch {
            saveDraft(CaptureDraft.Empty)
            internalState.value = CaptureUiState()
            internalEffects.send(CaptureEffect.Discarded)
        }
    }

    /**
     * Parses, then delegates. Note that this method contains no validation rules — it maps
     * the form to a domain record and hands it to the use case, which owns the rules.
     *
     * The ID is minted on the device, not requested from a server. An offline-first client
     * that waited for a server-assigned key could not create a record without connectivity,
     * which is the one thing it exists to do. `UUID.randomUUID()` is 122 bits of randomness —
     * collision across handsets is not a risk worth engineering against.
     */
    private suspend fun attemptSave(): SaveOutcome {
        val form = internalState.value
        val id = BeneficiaryId(UUID.randomUUID().toString())

        return when (val mapped = form.toBeneficiary(id = id, now = timeProvider.now())) {
            is Outcome.Failure -> SaveOutcome.Rejected(CaptureErrors(malformed = mapped.error))
            is Outcome.Success -> persist(mapped.value)
        }
    }

    private suspend fun persist(beneficiary: Beneficiary): SaveOutcome =
        when (val result = saveBeneficiary(beneficiary)) {
            is Outcome.Success -> SaveOutcome.Succeeded
            is Outcome.Failure -> when (val error = result.error) {
                is SaveError.Invalid -> SaveOutcome.Rejected(CaptureErrors(violations = error.violations))
                is SaveError.Storage -> SaveOutcome.Failed(error.error)
            }
        }

    private companion object {
        const val AUTOSAVE_DEBOUNCE_MS = 400L
    }
}
