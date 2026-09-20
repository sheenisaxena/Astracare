package com.astracare.core.domain.usecase

import androidx.paging.PagingData
import com.astracare.core.common.Outcome
import com.astracare.core.domain.remote.PullOutcome
import com.astracare.core.domain.remote.PushOutcome
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.domain.remote.SyncCursor
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The push loop.
 *
 * This is the code that can lose a health worker's field data, so it is the code most worth
 * testing — and because the algorithm lives in a plain class rather than inside a
 * `CoroutineWorker`, every branch here runs in milliseconds with no WorkManager, no
 * instrumentation and no Robolectric. That was the whole reason for the split.
 *
 * The test that matters most is the stale-write one. It covers a bug with no symptom: a record
 * shown as synced whose latest edit never left the device.
 */
class PushPendingRecordsUseCaseTest {

    private val repository = FakeBeneficiaryRepository()
    private val remote = ScriptedRemoteSource()

    private val pushPendingRecords = PushPendingRecordsUseCase(repository, remote)

    @Test
    fun `every pending record is offered to the server`() = runTest {
        repository.seedPending(record("1"), record("2"), record("3"))

        val summary = pushPendingRecords()

        assertEquals(listOf("1", "2", "3"), remote.pushed.map { it.id.value })
        assertEquals(PushSummary.Complete(accepted = 3, rejected = 0), summary)
    }

    @Test
    fun `an accepted record is marked synced`() = runTest {
        repository.seedPending(record("1"))

        pushPendingRecords()

        assertEquals(SyncStatus.SYNCED, repository.statusOf("1"))
    }

    @Test
    fun `a rejected record is marked rejected and the pass keeps going`() = runTest {
        repository.seedPending(record("1"), record("2"))
        remote.reject("1", reason = "village is required")

        val summary = pushPendingRecords()

        assertEquals(SyncStatus.REJECTED, repository.statusOf("1"))
        assertEquals(SyncStatus.SYNCED, repository.statusOf("2"))
        // A rejection is an answer, not an outage. The pass completed and must not be retried.
        assertEquals(PushSummary.Complete(accepted = 1, rejected = 1), summary)
    }

    @Test
    fun `a transient failure stops the pass immediately`() = runTest {
        repository.seedPending(record("1"), record("2"), record("3"))
        remote.failTransientlyOn("2")

        val summary = pushPendingRecords()

        // Record 3 is never attempted. If the connection is down it would fail the same way,
        // and forty more attempts is battery and radio a field handset cannot spare.
        assertEquals(listOf("1", "2"), remote.pushed.map { it.id.value })
        assertTrue(summary is PushSummary.Interrupted)
        assertEquals(1, summary.accepted)
    }

    @Test
    fun `records not yet sent stay pending after an interrupted pass`() = runTest {
        repository.seedPending(record("1"), record("2"))
        remote.failTransientlyOn("1")

        pushPendingRecords()

        assertEquals(SyncStatus.PENDING, repository.statusOf("1"))
        assertEquals(SyncStatus.PENDING, repository.statusOf("2"))
    }

    @Test
    fun `a record edited during the push is never marked synced`() = runTest {
        // The bug this guards has no symptom: the row would read SYNCED while the edit the
        // health worker just made had never left the handset.
        repository.seedPending(record("1", updatedAt = 1_000L))
        remote.onPush { repository.simulateLocalEdit("1", newUpdatedAt = 2_000L) }

        val summary = pushPendingRecords()

        assertEquals(SyncStatus.PENDING, repository.statusOf("1"))
        // Not counted as accepted either — nothing was durably marked, so the pass achieved
        // nothing for this record and the next one must pick it up.
        assertEquals(PushSummary.Complete(accepted = 0, rejected = 0), summary)
    }

    @Test
    fun `an empty queue is a completed pass, not a failure`() = runTest {
        assertEquals(PushSummary.Complete(accepted = 0, rejected = 0), pushPendingRecords())
        assertTrue(remote.pushed.isEmpty())
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
}

/**
 * Scripted server.
 *
 * [onPush] is what makes the stale-write test possible: it runs while the "network call" is in
 * flight, which is exactly the window a real edit would land in. Reproducing that against a
 * real repository would need precise coroutine choreography; here it is one lambda.
 */
private class ScriptedRemoteSource : RemoteBeneficiarySource {

    val pushed = mutableListOf<Beneficiary>()

    private val rejections = mutableMapOf<String, String>()
    private var transientFailureId: String? = null
    private var duringPush: (() -> Unit)? = null

    fun reject(id: String, reason: String) {
        rejections[id] = reason
    }

    fun failTransientlyOn(id: String) {
        transientFailureId = id
    }

    fun onPush(action: () -> Unit) {
        duringPush = action
    }

    override suspend fun push(beneficiary: Beneficiary): PushOutcome {
        pushed += beneficiary
        duringPush?.invoke()

        return when {
            beneficiary.id.value == transientFailureId ->
                PushOutcome.TransientFailure(IOException("simulated"))

            rejections.containsKey(beneficiary.id.value) ->
                PushOutcome.Rejected(rejections.getValue(beneficiary.id.value))

            else -> PushOutcome.Accepted
        }
    }

    /**
     * The push pass must not pull. Throwing rather than returning an empty result is the point:
     * an empty list would let the two halves quietly merge into one use case, and the ordering
     * of push before pull — which is what keeps conflicts rare — would stop being a decision
     * anyone could see.
     */
    override suspend fun pullChangedSince(cursor: SyncCursor?): PullOutcome =
        error("the push pass must not pull; that is a separate use case")
}

/**
 * A fake that enforces the conditional update.
 *
 * [updateSyncStatus] refuses the mark when `updatedAt` has moved, exactly as the real SQL
 * does. A fake that applied it unconditionally would let the stale-write test pass against
 * broken production code — which is the one failure mode a fake must not have.
 */
private class FakeBeneficiaryRepository : BeneficiaryRepository {

    private val records = mutableMapOf<BeneficiaryId, Beneficiary>()

    fun seedPending(vararg beneficiaries: Beneficiary) {
        beneficiaries.forEach { records[it.id] = it }
    }

    fun statusOf(id: String): SyncStatus = records.getValue(BeneficiaryId(id)).syncStatus

    fun simulateLocalEdit(id: String, newUpdatedAt: Long) {
        val key = BeneficiaryId(id)
        records[key] = records.getValue(key).copy(updatedAt = Timestamp(newUpdatedAt))
    }

    override suspend fun pendingSync(): List<Beneficiary> =
        records.values.filter { it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.FAILED }

    override suspend fun updateSyncStatus(
        id: BeneficiaryId,
        unchangedSince: Timestamp,
        status: SyncStatus,
    ): Boolean {
        val unchanged = records[id]?.takeIf { it.updatedAt == unchangedSince }
        if (unchanged != null) {
            records[id] = unchanged.copy(syncStatus = status)
        }
        return unchanged != null
    }

    override fun pagedRecords(): Flow<PagingData<Beneficiary>> =
        flowOf(PagingData.from(records.values.toList()))

    override fun observeAwaitingSyncCount(): Flow<Int> =
        flowOf(records.values.count { it.syncStatus != SyncStatus.SYNCED })

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> = flowOf(records[id])

    override suspend fun findById(id: BeneficiaryId): Beneficiary? = records[id]

    override suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError> {
        records[beneficiary.id] = beneficiary
        return Outcome.success()
    }

    /**
     * Mirrors the two real statements: insert only if absent, replace only if unchanged. A
     * fake that wrote unconditionally would let a pull overwrite an edit that never left the
     * device, and the test would still pass.
     */
    override suspend fun applyRemote(
        record: Beneficiary,
        replacingLocalVersion: Timestamp?,
    ): Boolean {
        val stored = records[record.id]
        val mayWrite = if (replacingLocalVersion == null) {
            stored == null
        } else {
            stored?.updatedAt == replacingLocalVersion
        }
        if (mayWrite) {
            records[record.id] = record
        }
        return mayWrite
    }
}
