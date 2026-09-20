package com.astracare.di

import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import com.astracare.core.common.Outcome
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Test double for [BeneficiaryRepository], fully under the test's control.
 *
 * A hand-written fake rather than a mock. The difference matters:
 *
 * - A **mock** is told what to return per call. Tests then assert on interactions
 *   ("`upsert` was called once"), which couples them to how the code is written rather than
 *   what it does. Refactor the implementation and the test breaks without the behaviour
 *   changing.
 * - A **fake** is a real, simple implementation. Tests assert on observable state ("after
 *   saving, the record appears in the stream"), so they survive refactoring and read like a
 *   description of the behaviour.
 *
 * MockK still has its place — a network layer with awkward error paths — but for something
 * with a small interface and simple state, a fake is better.
 *
 * [failNextWrite] exists so the storage-error path can be exercised. That branch is otherwise
 * unreachable in a test, and untested error handling is where offline-first apps lose data.
 */
@Singleton
class FakeBeneficiaryRepository @Inject constructor() : BeneficiaryRepository {

    private val records = MutableStateFlow<Map<BeneficiaryId, Beneficiary>>(emptyMap())

    /** Set to true to make the next [upsert] fail, then it resets. */
    var failNextWrite: Boolean = false

    /**
     * `PagingData.from` over the whole fake table.
     *
     * The fake does not page, and does not need to: a UI test asserting that a saved record
     * appears in the list is testing this app's wiring, not Paging's loading machinery. What
     * it must get right is the *shape* of the contract, so the screen exercises the same
     * `collectAsLazyPagingItems` path it does in production.
     *
     * It also does not sort. Ordering moved into SQL on Day 12, so a fake that sorted in
     * Kotlin would make a test pass while the query it stands in for was wrong — the one
     * failure mode a fake must not hide. Ordering is covered by `RecordAttentionOrderTest`
     * and `BeneficiaryDaoTest` against a real database.
     *
     * ## The load states are not optional, and the single-argument overload is a trap
     *
     * `PagingData.from(list)` — the obvious call, and what this used from Day 8 to Day 20 —
     * leaves every `LoadState` as `Loading`, **permanently**. The screen reads
     * `loadState.refresh is LoadState.Loading` to decide between a spinner and the empty state
     * (deliberately, because `itemCount == 0` is also true before the first page arrives), so
     * with that overload the list renders a spinner forever and never shows a record.
     *
     * Nothing caught it for twelve days because no test had ever rendered the screen; the
     * overload is deprecated and the build had been saying so in a warning nobody read. The
     * first run of `CaptureToHistoryTest` timed out waiting for an empty state that could not
     * arrive. DECISION_LOG 14.10.
     *
     * `endOfPaginationReached = true` on all three because this fake holds the whole table:
     * there is no page after it, and claiming otherwise would have the list ask for one.
     */
    override fun pagedRecords(): Flow<PagingData<Beneficiary>> =
        records.map { all ->
            PagingData.from(
                data = all.values.toList(),
                sourceLoadStates = LoadStates(
                    refresh = LoadState.NotLoading(endOfPaginationReached = true),
                    prepend = LoadState.NotLoading(endOfPaginationReached = true),
                    append = LoadState.NotLoading(endOfPaginationReached = true),
                ),
            )
        }

    override fun observeAwaitingSyncCount(): Flow<Int> =
        records.map { all -> all.values.count { it.syncStatus != SyncStatus.SYNCED } }

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> = records.map { it[id] }

    override suspend fun findById(id: BeneficiaryId): Beneficiary? = records.value[id]

    override suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError> {
        if (failNextWrite) {
            failNextWrite = false
            return Outcome.Failure(RepositoryError.StorageFailure(IllegalStateException("test")))
        }
        records.update { it + (beneficiary.id to beneficiary) }
        return Outcome.success()
    }

    override suspend fun updateSyncStatus(
        id: BeneficiaryId,
        unchangedSince: Timestamp,
        status: SyncStatus,
    ): Boolean {
        // Mirrors the real conditional UPDATE: refuse the mark if the record moved. A fake
        // that applied it unconditionally would let the stale-write bug pass a test.
        val unchanged = records.value[id]?.takeIf { it.updatedAt == unchangedSince }
        if (unchanged != null) {
            records.update { it + (id to unchanged.copy(syncStatus = status)) }
        }
        return unchanged != null
    }

    override suspend fun pendingSync(): List<Beneficiary> =
        records.value.values.filter { it.syncStatus != SyncStatus.SYNCED }

    /**
     * Mirrors the two real statements: insert only if absent, replace only if unchanged. A
     * fake that wrote unconditionally would let a pull overwrite an edit that never left the
     * device, and the test would still pass.
     */
    override suspend fun applyRemote(
        record: Beneficiary,
        replacingLocalVersion: Timestamp?,
    ): Boolean {
        val stored = records.value[record.id]
        val mayWrite = if (replacingLocalVersion == null) {
            stored == null
        } else {
            stored?.updatedAt == replacingLocalVersion
        }
        if (mayWrite) {
            records.update { it + (record.id to record) }
        }
        return mayWrite
    }
}
