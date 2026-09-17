package com.astracare.feature.patients

import androidx.paging.PagingData
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.astracare.core.common.Outcome
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.domain.usecase.ObserveAwaitingSyncCountUseCase
import com.astracare.core.domain.usecase.ObserveBeneficiariesUseCase
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The history screen's ViewModel, over a fake repository.
 *
 * ## What this deliberately does not test, and why that is not laziness
 *
 * There is no assertion here about which records appear or in what order, and that is a
 * considered boundary rather than a gap left by accident.
 *
 * Ordering moved into SQL on Day 12. Any unit-level fake stands in for the query, so a fake
 * that returned records in the right order would prove only that the fake was written
 * correctly — it would go on passing while the real `ORDER BY` was wrong, which is the one
 * thing a test must never do. The two halves of that contract are covered where they can
 * actually be checked: `RecordAttentionOrderTest` pins the domain's declaration, and the SQL
 * needs an instrumented test against a real database, which is listed as a gap.
 *
 * `paging-testing`'s `asSnapshot()` would let a unit test drive a real `Pager` to completion,
 * and it is worth revisiting on the testing days. It still would not test the query.
 *
 * What is left is what this class genuinely owns: the count projection, the effect channel,
 * and the fact that intents produce navigation rather than performing it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BeneficiaryListViewModelTest {

    private val repository = FakePagedBeneficiaryRepository()

    private lateinit var viewModel: BeneficiaryListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = BeneficiaryListViewModel(
            observeBeneficiaries = ObserveBeneficiariesUseCase(repository),
            observeAwaitingSyncCount = ObserveAwaitingSyncCountUseCase(repository),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the sync summary counts what the server has not acknowledged`() = runTest {
        repository.setRecords(
            record("1", "Asha Devi", SyncStatus.PENDING),
            record("2", "Ramesh Kumar", SyncStatus.SYNCED),
            record("3", "Sunita Bai", SyncStatus.CONFLICTED),
        )

        viewModel.syncSummary.test {
            assertFalse(awaitCount(2).isEverythingSynced)
        }
    }

    @Test
    fun `a fully synced table reports everything synced rather than hiding the banner`() =
        runTest {
            repository.setRecords(record("1", "Asha Devi", SyncStatus.SYNCED))

            viewModel.syncSummary.test {
                assertTrue(awaitCount(0).isEverythingSynced)
            }
        }

    @Test
    fun `the count follows the database rather than being computed once`() = runTest {
        repository.setRecords(record("1", "Asha Devi", SyncStatus.SYNCED))

        viewModel.syncSummary.test {
            awaitCount(0)

            // A record captured on another screen must move this number without the list
            // screen asking. That is the property the whole single-source-of-truth design
            // exists for, and it is cheap to assert here.
            repository.setRecords(
                record("1", "Asha Devi", SyncStatus.SYNCED),
                record("2", "Ramesh Kumar", SyncStatus.PENDING),
            )

            assertEquals(1, awaitCount(1).awaitingSync)
        }
    }

    /**
     * Consumes emissions until the count settles on [expected].
     *
     * Needed because `stateIn` replays only its latest value, and whether the seeded
     * [SyncSummaryUiState.Unknown] is ever observed depends on how fast the upstream answers —
     * which under `UnconfinedTestDispatcher` is "immediately". Asserting on a fixed number of
     * emissions would make these tests pass or fail on dispatcher scheduling rather than on
     * behaviour, which is precisely the flakiness injected dispatchers exist to avoid.
     *
     * Turbine's own 3-second timeout turns a value that never arrives into a clear failure.
     */
    private suspend fun ReceiveTurbine<SyncSummaryUiState>.awaitCount(
        expected: Int,
    ): SyncSummaryUiState {
        while (true) {
            val summary = awaitItem()
            if (summary.awaitingSync == expected) return summary
        }
    }

    @Test
    fun `tapping a record asks to open it rather than navigating itself`() = runTest {
        viewModel.effects.test {
            viewModel.dispatch(BeneficiaryListIntent.RecordClicked(BeneficiaryId("1")))

            assertEquals(BeneficiaryListEffect.OpenRecord(BeneficiaryId("1")), awaitItem())
        }
    }

    @Test
    fun `tapping add asks to open the capture screen`() = runTest {
        viewModel.effects.test {
            viewModel.dispatch(BeneficiaryListIntent.AddRecordClicked)

            assertEquals(BeneficiaryListEffect.OpenCapture, awaitItem())
        }
    }

    private fun record(id: String, name: String, syncStatus: SyncStatus) = Beneficiary(
        id = BeneficiaryId(id),
        name = name,
        ageYears = 3,
        village = "Kotri",
        measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
        syncStatus = syncStatus,
    )
}

/**
 * A fake that hands back a single page and does not sort.
 *
 * Not sorting is deliberate — see the class documentation above. `PagingData.from` rather than
 * a real `PagingSource` for the same reason: Paging's loading machinery is Google's to test,
 * and building a `PagingSource` here would test this file.
 */
private class FakePagedBeneficiaryRepository : BeneficiaryRepository {

    private val records = MutableStateFlow<List<Beneficiary>>(emptyList())

    fun setRecords(vararg beneficiaries: Beneficiary) {
        records.value = beneficiaries.toList()
    }

    override fun pagedRecords(): Flow<PagingData<Beneficiary>> =
        records.map { PagingData.from(it) }

    override fun observeAwaitingSyncCount(): Flow<Int> =
        records.map { all -> all.count { it.syncStatus != SyncStatus.SYNCED } }

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> =
        records.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError> {
        records.value = records.value.filterNot { it.id == beneficiary.id } + beneficiary
        return Outcome.success()
    }

    override suspend fun pendingSync(): List<Beneficiary> =
        records.value.filter { it.syncStatus != SyncStatus.SYNCED }
}
