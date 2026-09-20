package com.astracare.core.domain.usecase

import androidx.paging.PagingData
import com.astracare.core.common.Outcome
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.remote.PullOutcome
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.domain.remote.SyncCursor
import com.astracare.core.domain.repository.AuditRepository
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.domain.repository.SessionRepository
import com.astracare.core.domain.repository.SyncStateRepository
import com.astracare.core.model.AuditAction
import com.astracare.core.model.AuditEntry
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import com.astracare.core.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The pull loop.
 *
 * The *rule* is tested in `ConflictResolverTest`, as a table over a pure function. What is
 * left for this class is the orchestration, and it is not the boring half: when the cursor
 * advances decides whether records can be skipped for good, and whether writes are conditional
 * decides whether a pull can overwrite an edit made while it was running.
 *
 * The two tests worth reading first are `the cursor does not advance when the server cannot be
 * reached` and `a record edited during the pull is not overwritten`. Both guard failures with
 * no symptom: in one, records the server will never offer again; in the other, a field edit
 * replaced by a server version, with the row looking perfectly healthy afterwards.
 */
class PullRemoteChangesUseCaseTest {

    private val repository = FakePullRepository()
    private val syncState = FakeSyncStateRepository()
    private val remote = ScriptedPullSource()

    private val session = FakePullSessionRepository()
    private val audit = CountingAuditRepository()

    private val pullRemoteChanges = PullRemoteChangesUseCase(
        repository = repository,
        syncState = syncState,
        remote = remote,
        session = session,
        audit = audit,
        timeProvider = TimeProvider { Timestamp(FIXED_NOW) },
    )

    @Test
    fun `a record the device has never seen is inserted as synced`() = runTest {
        remote.serve(record("1", village = "Kotri"))

        val summary = pullRemoteChanges()

        assertEquals(SyncStatus.SYNCED, repository.statusOf("1"))
        assertEquals("Kotri", repository.villageOf("1"))
        assertEquals(PullSummary.Complete(applied = 1, conflicted = 0), summary)
    }

    @Test
    fun `a server change to a synced record is applied silently`() = runTest {
        repository.seed(record("1", village = "Kotri", status = SyncStatus.SYNCED))
        remote.serve(record("1", village = "Kotri Kalan"))

        val summary = pullRemoteChanges()

        assertEquals("Kotri Kalan", repository.villageOf("1"))
        assertEquals(SyncStatus.SYNCED, repository.statusOf("1"))
        assertEquals(PullSummary.Complete(applied = 1, conflicted = 0), summary)
    }

    @Test
    fun `a server change over an unsent local edit is flagged and overwrites nothing`() = runTest {
        repository.seed(record("1", village = "Kotri", status = SyncStatus.PENDING))
        remote.serve(record("1", village = "Kotri Kalan"))

        val summary = pullRemoteChanges()

        assertEquals(SyncStatus.CONFLICTED, repository.statusOf("1"))
        // The local value survives. Both versions still exist — one here, one on the server —
        // which is the entire promise of detecting rather than resolving.
        assertEquals("Kotri", repository.villageOf("1"))
        assertEquals(PullSummary.Complete(applied = 0, conflicted = 1), summary)
        // A conflict is the one sync event a person has to act on, so it is the one the trail
        // must carry.
        assertEquals(listOf(AuditAction.CONFLICT_DETECTED), audit.actions)
    }

    @Test
    fun `a pull that resolves cleanly writes nothing to the audit trail`() = runTest {
        repository.seed(record("1", village = "Kotri", status = SyncStatus.SYNCED))
        remote.serve(record("1", village = "Kotri Kalan"))

        pullRemoteChanges()

        // Applying a server change to a record nobody was editing is routine. Logging it would
        // bury the conflicts under a line per record per pass.
        assertTrue(audit.actions.isEmpty())
    }

    @Test
    fun `a conflict mark refused by a concurrent edit is not logged`() = runTest {
        repository.seed(record("1", village = "Kotri", status = SyncStatus.PENDING))
        remote.serve(record("1", village = "Kotri Kalan"))
        repository.onRead { repository.simulateLocalEdit("1", village = "Kotra", newUpdatedAt = 2_000L) }

        pullRemoteChanges()

        // Nothing was marked, so nothing was conflicted. An entry here would describe an event
        // that did not happen — worse than a missing one, because it reads as true.
        assertTrue(audit.actions.isEmpty())
    }

    @Test
    fun `the cursor advances once the whole pass is applied`() = runTest {
        remote.serve(record("1", village = "Kotri"), nextCursor = "42")

        pullRemoteChanges()

        assertEquals(SyncCursor("42"), syncState.cursor)
    }

    @Test
    fun `the cursor does not advance when the server cannot be reached`() = runTest {
        syncState.cursor = SyncCursor("7")
        remote.failTransiently()

        val summary = pullRemoteChanges()

        // If this ever regresses, every record the server wrote between cursor 7 and the
        // failure is behind the window and will never be offered again. Nothing errors, and
        // nothing on the device looks wrong.
        assertEquals(SyncCursor("7"), syncState.cursor)
        assertTrue(summary is PullSummary.Interrupted)
    }

    @Test
    fun `the first pull asks for everything`() = runTest {
        remote.serve()

        pullRemoteChanges()

        // A device that has never pulled sends no cursor, and gets the server's whole history.
        // Seeding one from local timestamps would be faster and would skip anything the server
        // stamped behind this handset's clock — see MIGRATION_2_3.
        assertNull(remote.requestedCursor)
    }

    @Test
    fun `a record edited during the pull is not overwritten`() = runTest {
        // The mirror of Day 13's stale write. The pull reads the local row, decides the
        // server's version supersedes it — and in the window before that write lands, the
        // health worker saves an edit. Applying the decision now would replace a record that
        // has never been sent anywhere, and the row would look perfectly healthy afterwards.
        repository.seed(record("1", village = "Kotri", status = SyncStatus.SYNCED))
        remote.serve(record("1", village = "Kotri Kalan"))
        repository.onRead { repository.simulateLocalEdit("1", village = "Kotra", newUpdatedAt = 2_000L) }

        val summary = pullRemoteChanges()

        assertEquals("Kotra", repository.villageOf("1"))
        assertEquals(PullSummary.Complete(applied = 0, conflicted = 0), summary)
        // Nothing was lost and nothing was decided wrongly — the decision was simply made
        // against a version that no longer exists, and the next pull decides again.
        assertEquals(SyncCursor("1"), syncState.cursor)
    }

    @Test
    fun `an empty pull is a completed pass, not a failure`() = runTest {
        remote.serve(nextCursor = "3")

        assertEquals(PullSummary.Complete(applied = 0, conflicted = 0), pullRemoteChanges())
        assertEquals(SyncCursor("3"), syncState.cursor)
    }

    @Test
    fun `resolving the same pull twice changes nothing the second time`() = runTest {
        // Idempotence is what makes the late cursor advance safe: a pass that dies halfway is
        // replayed in full, and the replay must not double-count or churn the database.
        repository.seed(record("1", village = "Kotri", status = SyncStatus.SYNCED))
        remote.serve(record("1", village = "Kotri Kalan"))

        pullRemoteChanges()
        val writesAfterFirst = repository.writeCount
        val second = pullRemoteChanges()

        assertEquals(PullSummary.Complete(applied = 0, conflicted = 0), second)
        assertEquals(writesAfterFirst, repository.writeCount)
    }

    private fun record(
        id: String,
        village: String,
        status: SyncStatus = SyncStatus.SYNCED,
        updatedAt: Long = 1_000L,
    ) = Beneficiary(
        id = BeneficiaryId(id),
        name = "Asha Devi",
        ageYears = 3,
        village = village,
        measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(updatedAt),
        syncStatus = status,
    )

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}

private class FakePullSessionRepository : SessionRepository {
    override fun observeActiveRole(): Flow<UserRole> = flowOf(UserRole.Default)
    override suspend fun activeRole(): UserRole = UserRole.Default
    override suspend fun setActiveRole(role: UserRole) = Unit
}

/** Records only what was appended; the pull never reads the trail back. */
private class CountingAuditRepository : AuditRepository {
    val actions = mutableListOf<AuditAction>()

    override suspend fun append(
        actor: UserRole,
        action: AuditAction,
        recordId: BeneficiaryId?,
        at: Timestamp,
    ) {
        actions += action
    }

    override fun observeRecent(limit: Int): Flow<List<AuditEntry>> = flowOf(emptyList())
}

/**
 * A scripted server for one pull.
 *
 * [requestedCursor] is recorded rather than asserted through a mock, because what the client
 * sends is part of the contract: a first pull must send nothing at all.
 */
private class ScriptedPullSource : RemoteBeneficiarySource {

    var requestedCursor: SyncCursor? = null
        private set

    private var outcome: PullOutcome = PullOutcome.Changes(emptyList(), SyncCursor("1"))

    fun serve(vararg records: Beneficiary, nextCursor: String = "1") {
        outcome = PullOutcome.Changes(records.toList(), SyncCursor(nextCursor))
    }

    fun failTransiently() {
        outcome = PullOutcome.TransientFailure(IOException("simulated"))
    }

    override suspend fun pullChangedSince(cursor: SyncCursor?): PullOutcome {
        requestedCursor = cursor
        return outcome
    }

    override suspend fun push(beneficiary: Beneficiary) =
        error("the pull pass must not push; that is a separate use case")
}

private class FakeSyncStateRepository : SyncStateRepository {

    var cursor: SyncCursor? = null

    override suspend fun lastPullCursor(): SyncCursor? = cursor

    override suspend fun recordPullCursor(cursor: SyncCursor) {
        this.cursor = cursor
    }
}

/**
 * A fake that enforces both conditional writes.
 *
 * `applyRemote` refuses when `updatedAt` has moved and refuses to insert over a row that has
 * appeared, exactly as the two SQL statements do. A fake that wrote unconditionally would let
 * `a record edited during the pull is not overwritten` pass against production code that
 * overwrites — which is the one failure mode a fake must not have.
 *
 * [onRead] runs inside `findById`, after the value has been captured and before it is
 * returned: the window in which a real edit lands while the pull is deciding.
 */
private class FakePullRepository : BeneficiaryRepository {

    private val records = mutableMapOf<BeneficiaryId, Beneficiary>()
    private var duringRead: (() -> Unit)? = null

    /** Writes that actually applied. Used to assert that a replayed pull churns nothing. */
    var writeCount: Int = 0
        private set

    fun seed(vararg beneficiaries: Beneficiary) {
        beneficiaries.forEach { records[it.id] = it }
    }

    fun statusOf(id: String): SyncStatus = records.getValue(BeneficiaryId(id)).syncStatus

    fun villageOf(id: String): String = records.getValue(BeneficiaryId(id)).village

    fun onRead(action: () -> Unit) {
        duringRead = action
    }

    fun simulateLocalEdit(id: String, village: String, newUpdatedAt: Long) {
        val key = BeneficiaryId(id)
        records[key] = records.getValue(key).copy(
            village = village,
            updatedAt = Timestamp(newUpdatedAt),
            syncStatus = SyncStatus.PENDING,
        )
    }

    override suspend fun findById(id: BeneficiaryId): Beneficiary? {
        val current = records[id]
        duringRead?.invoke()
        return current
    }

    override suspend fun applyRemote(record: Beneficiary, replacingLocalVersion: Timestamp?): Boolean {
        val stored = records[record.id]
        val mayWrite = if (replacingLocalVersion == null) {
            // Mirrors INSERT OR IGNORE: a row that appeared since the decision is a local
            // capture this pull has not considered, and must not be replaced.
            stored == null
        } else {
            stored?.updatedAt == replacingLocalVersion
        }
        if (mayWrite) {
            records[record.id] = record
            writeCount++
        }
        return mayWrite
    }

    override suspend fun updateSyncStatus(
        id: BeneficiaryId,
        unchangedSince: Timestamp,
        status: SyncStatus,
    ): Boolean {
        val unchanged = records[id]?.takeIf { it.updatedAt == unchangedSince }
        if (unchanged != null) {
            records[id] = unchanged.copy(syncStatus = status)
            writeCount++
        }
        return unchanged != null
    }

    override fun pagedRecords(): Flow<PagingData<Beneficiary>> =
        flowOf(PagingData.from(records.values.toList()))

    override fun observeAwaitingSyncCount(): Flow<Int> =
        flowOf(records.values.count { it.syncStatus != SyncStatus.SYNCED })

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> = flowOf(records[id])

    override suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError> {
        records[beneficiary.id] = beneficiary
        return Outcome.success()
    }

    override suspend fun pendingSync(): List<Beneficiary> =
        records.values.filter { it.syncStatus == SyncStatus.PENDING }
}
