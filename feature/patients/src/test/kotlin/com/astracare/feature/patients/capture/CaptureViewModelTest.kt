package com.astracare.feature.patients.capture

import app.cash.turbine.test
import com.astracare.core.common.Outcome
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.DraftRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.domain.usecase.RestoreDraftUseCase
import com.astracare.core.domain.usecase.SaveBeneficiaryUseCase
import com.astracare.core.domain.usecase.SaveDraftUseCase
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.CaptureDraft
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The parts of the capture screen that the pure reducer tests cannot reach: that the effect
 * channel actually delivers, that autosave fires when it should and not before, and that the
 * ViewModel's two outputs stay in their lanes.
 *
 * Kept deliberately small. Anything expressible as a state transition is tested in
 * [CaptureReducerTest] without a dispatcher, because those tests are faster and cannot race.
 * What is left here is the asynchronous wiring — the only thing that needs a dispatcher, and
 * the only thing worth paying for one.
 *
 * `TimeProvider` is a fixed lambda rather than the system clock, so the record's timestamps
 * are an assertion rather than a tolerance. `UnconfinedTestDispatcher` as Main runs
 * `viewModelScope` work eagerly, so effects and saves need no `advanceUntilIdle()`; the
 * autosave debounce still runs on virtual time, which is what makes "does not save yet"
 * testable at all rather than a sleep.
 *
 * The ViewModel is built in [setUp] and not as a property, because its `init` block touches
 * `viewModelScope` — so constructing it before `Dispatchers.setMain` would throw.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {

    private val repository = RecordingBeneficiaryRepository()
    private val drafts = FakeDraftRepository()
    private val clock = TimeProvider { Timestamp(FIXED_NOW) }

    private lateinit var viewModel: CaptureViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = buildViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = CaptureViewModel(
        saveBeneficiary = SaveBeneficiaryUseCase(repository, clock),
        restoreDraft = RestoreDraftUseCase(drafts),
        saveDraft = SaveDraftUseCase(drafts),
        timeProvider = clock,
    )

    @Test
    fun `a valid form reaches the repository, emits Saved once, and clears itself`() = runTest {
        fillValidForm()

        viewModel.effects.test {
            viewModel.dispatch(CaptureIntent.SaveClicked)

            assertEquals(CaptureEffect.Saved, awaitItem())
            expectNoEvents()
        }

        assertEquals(1, repository.written.size)
        assertEquals("Asha Devi", repository.written.single().name)
        // The use case restamps updatedAt and forces PENDING — asserted here rather than
        // taken on trust, because a record that reaches the database as SYNCED is a record
        // the sync worker will never push.
        assertEquals(Timestamp(FIXED_NOW), repository.written.single().updatedAt)
        assertEquals(SyncStatus.PENDING, repository.written.single().syncStatus)
        assertEquals(CaptureUiState(), viewModel.uiState.value)
    }

    @Test
    fun `a storage failure emits SaveFailed and leaves the typed input on screen`() = runTest {
        fillValidForm()
        repository.failNextWrite = true

        viewModel.effects.test {
            viewModel.dispatch(CaptureIntent.SaveClicked)

            assertTrue(awaitItem() is CaptureEffect.SaveFailed)
        }

        assertEquals("Asha Devi", viewModel.uiState.value.name)
        assertEquals("12.4", viewModel.uiState.value.weightKg)
        assertTrue(viewModel.uiState.value.isSaveEnabled)
    }

    @Test
    fun `a domain violation lands in state and produces no effect at all`() = runTest {
        fillValidForm()
        viewModel.dispatch(CaptureIntent.FieldChanged(CaptureField.AGE, "400"))

        viewModel.effects.test {
            viewModel.dispatch(CaptureIntent.SaveClicked)

            expectNoEvents()
        }

        assertTrue(repository.written.isEmpty())
        assertFalse(viewModel.uiState.value.errorsFor(CaptureField.AGE).isEmpty)
    }

    @Test
    fun `unparseable input never reaches the repository`() = runTest {
        fillValidForm()
        viewModel.dispatch(CaptureIntent.FieldChanged(CaptureField.WEIGHT, "heavy"))

        viewModel.effects.test {
            viewModel.dispatch(CaptureIntent.SaveClicked)

            expectNoEvents()
        }

        assertTrue(repository.written.isEmpty())
        assertEquals(setOf(CaptureField.WEIGHT), viewModel.uiState.value.errors.malformed)
    }

    @Test
    fun `a stored draft is restored into the form when the screen opens`() = runTest {
        drafts.stored = CaptureDraft.Empty.copy(name = "Asha Devi", village = "Kotri")

        val restored = buildViewModel()

        assertEquals("Asha Devi", restored.uiState.value.name)
        assertEquals("Kotri", restored.uiState.value.village)
    }

    @Test
    fun `typing is not written to storage until the pause`() = runTest {
        fillValidForm()

        advanceTimeBy(BELOW_DEBOUNCE_MS)
        runCurrent()

        // Still mid-burst. A write per keystroke is a database transaction per keystroke, on
        // a low-end handset, while someone is typing into it.
        assertNull(drafts.stored)
    }

    @Test
    fun `typing is written to storage once typing stops`() = runTest {
        fillValidForm()

        advanceTimeBy(ABOVE_DEBOUNCE_MS)
        runCurrent()

        assertEquals("Asha Devi", drafts.stored?.name)
        // One write for the whole burst of five fields, not five.
        assertEquals(1, drafts.saveCount)
    }

    @Test
    fun `a successful save clears the draft immediately, not after the debounce`() = runTest {
        fillValidForm()
        advanceTimeBy(ABOVE_DEBOUNCE_MS)
        runCurrent()
        assertNotNull(drafts.stored)

        viewModel.dispatch(CaptureIntent.SaveClicked)
        runCurrent()

        // Without the explicit clear this would still hold the draft for another 400ms, and
        // a process death inside that window would restore a form for a visit already saved.
        assertNull(drafts.stored)
    }

    @Test
    fun `discarding forgets the draft as well as the form`() = runTest {
        fillValidForm()
        advanceTimeBy(ABOVE_DEBOUNCE_MS)
        runCurrent()

        viewModel.effects.test {
            viewModel.dispatch(CaptureIntent.DiscardClicked)

            assertEquals(CaptureEffect.Discarded, awaitItem())
        }

        assertNull(drafts.stored)
        assertEquals(CaptureUiState(), viewModel.uiState.value)
    }

    private fun fillValidForm() {
        viewModel.dispatch(CaptureIntent.FieldChanged(CaptureField.NAME, "Asha Devi"))
        viewModel.dispatch(CaptureIntent.FieldChanged(CaptureField.AGE, "3"))
        viewModel.dispatch(CaptureIntent.FieldChanged(CaptureField.VILLAGE, "Kotri"))
        viewModel.dispatch(CaptureIntent.FieldChanged(CaptureField.WEIGHT, "12.4"))
        viewModel.dispatch(CaptureIntent.FieldChanged(CaptureField.HEIGHT, "91.0"))
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L

        // The ViewModel debounces at 400ms. These bracket it rather than matching it, so the
        // tests assert "before" and "after" instead of depending on boundary behaviour.
        const val BELOW_DEBOUNCE_MS = 200L
        const val ABOVE_DEBOUNCE_MS = 600L
    }
}

/**
 * A fake, not a mock.
 *
 * It is a real implementation with real state, so the assertions above are about what the
 * system did — "the record reached storage, marked PENDING" — rather than about which methods
 * were called on a stub. Those assertions survive a refactor of the ViewModel; interaction
 * assertions would not.
 *
 * [failNextWrite] exists because the storage-failure branch is otherwise unreachable, and
 * untested error handling in an offline-first app is exactly where field data goes missing.
 */
private class RecordingBeneficiaryRepository : BeneficiaryRepository {

    private val records = MutableStateFlow<Map<BeneficiaryId, Beneficiary>>(emptyMap())

    var failNextWrite: Boolean = false

    val written: List<Beneficiary> get() = records.value.values.toList()

    override fun observeAll(): Flow<List<Beneficiary>> = records.map { it.values.toList() }

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> = records.map { it[id] }

    override suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError> {
        if (failNextWrite) {
            failNextWrite = false
            return Outcome.Failure(RepositoryError.StorageFailure(IllegalStateException("disk full")))
        }
        records.update { it + (beneficiary.id to beneficiary) }
        return Outcome.success()
    }

    override suspend fun pendingSync(): List<Beneficiary> =
        written.filter { it.syncStatus == SyncStatus.PENDING }
}

/**
 * [saveCount] is the one interaction these tests do assert on, and deliberately so: the
 * difference between one write and five for the same burst of typing is not observable in the
 * stored state, and it is the entire point of debouncing.
 */
private class FakeDraftRepository : DraftRepository {

    var stored: CaptureDraft? = null
    var saveCount: Int = 0

    override suspend fun load(): CaptureDraft? = stored

    override suspend fun save(draft: CaptureDraft) {
        stored = draft
        saveCount++
    }

    override suspend fun clear() {
        stored = null
    }
}
