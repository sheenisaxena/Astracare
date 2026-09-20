package com.astracare.core.data.repository

import androidx.paging.PagingSource
import com.astracare.core.data.database.dao.BeneficiaryDao
import com.astracare.core.data.database.entity.BeneficiaryEntity
import com.astracare.core.domain.sync.SyncScheduler
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a write asks for a sync, and when it must not.
 *
 * ## The one place MockK earns its keep
 *
 * Every other test in this project uses a hand-written fake, and DECISION_LOG 13.3 sets out the
 * policy: a fake asserts on *state* — "the record reached storage, marked PENDING" — which
 * survives refactoring, where an interaction assertion couples the test to how the code is
 * written.
 *
 * This file is the exception, because here the interaction **is** the behaviour. There is no
 * state to inspect: `SyncScheduler.requestSync()` returns nothing, enqueues work somewhere
 * else, and the only observable fact is whether it was called. Specifically:
 *
 *  - A local save must request a sync, or a captured record sits on the handset until the
 *    hourly pass notices it.
 *  - `applyRemote` must **not**, or every pull enqueues a push of everything it just received
 *    — the app in a conversation with itself, on a metered connection, on a handset whose
 *    battery has to last a working day.
 *
 * That second one is a *negative* interaction, and negative interactions are what a fake is
 * genuinely bad at: a recording fake can only prove a list is empty, which is the same
 * assertion written longhand and with a class to maintain. `verify(exactly = 0)` says it once.
 *
 * Until Day 19 it was defended by a comment in `OfflineFirstBeneficiaryRepository` and by
 * nothing else. It was also the single most plausible thing for a future refactor to break,
 * because "both of these write a record, why do they differ?" is a reasonable question to ask
 * and a wrong one to act on.
 *
 * The DAO is still a fake rather than a mock: it has real state, the tests read it back, and
 * that is exactly the case where a fake wins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulingTest {

    private val dao = FakeBeneficiaryDao()
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val repository = OfflineFirstBeneficiaryRepository(dao, scheduler, UnconfinedTestDispatcher())

    @Test
    fun `saving a record locally asks for a sync`() = runTest {
        repository.upsert(record("1"))

        // Exactly once. Twice would double-enqueue on every save; `ExistingWorkPolicy.KEEP`
        // makes that harmless today, which is precisely why it would go unnoticed.
        verify(exactly = 1) { scheduler.requestSync() }
    }

    @Test
    fun `applying a record that came from the server does not ask for a sync`() = runTest {
        repository.applyRemote(record("1"), replacingLocalVersion = null)

        // The app-talking-to-itself bug. A record the server just sent does not need sending
        // back, and a pull of forty records would otherwise enqueue a push of all forty.
        verify(exactly = 0) { scheduler.requestSync() }
    }

    @Test
    fun `replacing a local record with a server version does not ask for a sync either`() =
        runTest {
            dao.upsert(entity("1", updatedAt = 1_000L))

            val applied = repository.applyRemote(record("1", updatedAt = 2_000L), Timestamp(1_000L))

            assertTrue(applied)
            // The other branch of applyRemote. Tested separately because it is a different SQL
            // statement, and a fix applied to one branch has historically missed the other.
            verify(exactly = 0) { scheduler.requestSync() }
        }

    @Test
    fun `a refused conditional write asks for nothing`() = runTest {
        dao.upsert(entity("1", updatedAt = 9_999L))

        val applied = repository.applyRemote(record("1"), replacingLocalVersion = Timestamp(1_000L))

        assertFalse(applied)
        verify(exactly = 0) { scheduler.requestSync() }
    }

    @Test
    fun `marking a sync outcome does not itself ask for another sync`() = runTest {
        dao.upsert(entity("1", updatedAt = 1_000L))

        repository.updateSyncStatus(BeneficiaryId("1"), Timestamp(1_000L), SyncStatus.SYNCED)

        // The push loop calls this for every record it sends. If it enqueued a pass, a sync
        // would schedule the next sync and the handset would never be idle.
        verify(exactly = 0) { scheduler.requestSync() }
    }

    @Test
    fun `only retryable statuses are offered to the push loop`() = runTest {
        SyncStatus.entries.forEach { status ->
            dao.upsert(entity(status.name, updatedAt = 1L, syncStatus = status))
        }

        val pending = repository.pendingSync().map { it.syncStatus }.toSet()

        // REJECTED has been answered by the server and CONFLICTED needs a person; re-offering
        // either on every pass is a handset retrying forever. Asserted against the live enum,
        // so a new status has to be classified here rather than defaulting into the queue.
        assertTrue(pending.containsAll(setOf(SyncStatus.PENDING, SyncStatus.FAILED)))
        assertTrue(
            "a status that cannot be fixed by retrying was queued: $pending",
            pending.intersect(setOf(SyncStatus.SYNCED, SyncStatus.REJECTED, SyncStatus.CONFLICTED)).isEmpty(),
        )
    }

    private fun record(id: String, updatedAt: Long = 1_000L) = Beneficiary(
        id = BeneficiaryId(id),
        name = "Asha Devi",
        ageYears = 3,
        village = "Kotri",
        measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(updatedAt),
        syncStatus = SyncStatus.PENDING,
    )

    private fun entity(
        id: String,
        updatedAt: Long,
        syncStatus: SyncStatus = SyncStatus.PENDING,
    ) = BeneficiaryEntity(
        id = id,
        name = "Asha Devi",
        ageYears = 3,
        village = "Kotri",
        measurement = com.astracare.core.data.database.entity.MeasurementEmbedded(12.4, 91.0, null),
        recordedAtEpochMillis = 0L,
        updatedAtEpochMillis = updatedAt,
        syncStatus = syncStatus.name,
    )
}

/**
 * A fake DAO with real state.
 *
 * Deliberately not a mock, in the same file that argues for one: this thing has state, the
 * tests read it back, and the conditional writes have to behave like the SQL they stand in for.
 * `replaceIfUnchanged` refuses when `updated_at` has moved and `insertIfAbsent` refuses when a
 * row already exists, because a fake that wrote unconditionally would let the refusal tests
 * pass against code that overwrites.
 */
private class FakeBeneficiaryDao : BeneficiaryDao {

    private val rows = MutableStateFlow<Map<String, BeneficiaryEntity>>(emptyMap())

    override suspend fun upsert(entity: BeneficiaryEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    override suspend fun findById(id: String): BeneficiaryEntity? = rows.value[id]

    override suspend fun insertIfAbsent(entity: BeneficiaryEntity): Long =
        if (rows.value.containsKey(entity.id)) {
            INSERT_IGNORED
        } else {
            upsertBlocking(entity)
            1L
        }

    override suspend fun replaceIfUnchanged(entity: BeneficiaryEntity, unchangedSince: Long): Boolean {
        val unchanged = rows.value[entity.id]?.takeIf { it.updatedAtEpochMillis == unchangedSince }
        if (unchanged != null) upsertBlocking(entity)
        return unchanged != null
    }

    override suspend fun updateSyncStatusIfUnchanged(
        id: String,
        unchangedSince: Long,
        status: String,
    ): Int {
        val unchanged = rows.value[id]?.takeIf { it.updatedAtEpochMillis == unchangedSince }
        if (unchanged != null) upsertBlocking(unchanged.copy(syncStatus = status))
        return if (unchanged == null) 0 else 1
    }

    override suspend fun pendingSync(retryableStatuses: List<String>): List<BeneficiaryEntity> =
        rows.value.values.filter { it.syncStatus in retryableStatuses }

    override fun observeAwaitingSyncCount(syncedStatus: String): Flow<Int> =
        MutableStateFlow(rows.value.values.count { it.syncStatus != syncedStatus })

    override fun observeById(id: String): Flow<BeneficiaryEntity?> = MutableStateFlow(rows.value[id])

    override suspend fun deleteAll() {
        rows.value = emptyMap()
    }

    override fun pagedByAttention(
        rank1: String,
        rank2: String,
        rank3: String,
        rank4: String,
        rank5: String,
    ): PagingSource<Int, BeneficiaryEntity> =
        error("paging is exercised against a real database, not a fake — see DECISION_LOG 7.6")

    private fun upsertBlocking(entity: BeneficiaryEntity) {
        rows.value = rows.value + (entity.id to entity)
    }

    private companion object {
        const val INSERT_IGNORED = -1L
    }
}
