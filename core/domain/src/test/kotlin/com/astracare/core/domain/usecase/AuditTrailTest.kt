package com.astracare.core.domain.usecase

import androidx.paging.PagingData
import com.astracare.core.common.Outcome
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.repository.AuditRepository
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.domain.repository.SessionRepository
import com.astracare.core.model.AuditAction
import com.astracare.core.model.AuditEntry
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import com.astracare.core.model.UserRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What gets written to the trail, and what stops it being written.
 *
 * Two things are under test and they pull in opposite directions: the audit entry must be
 * written whenever the thing it describes happened, and must **not** be written when it did
 * not. The second half is the one that decays — a refactor that moves a write outside an `if`
 * produces a trail that reads plausibly and records events that never occurred, which is worse
 * than a trail with gaps because nothing about it looks wrong.
 */
class AuditTrailTest {

    private val repository = RecordingRepository()
    private val session = FakeSessionRepository()
    private val audit = RecordingAuditRepository()
    private val clock = TimeProvider { Timestamp(FIXED_NOW) }

    private val save = SaveBeneficiaryUseCase(repository, session, audit, clock)
    private val switchRole = SwitchRoleUseCase(session, audit, clock)

    @Test
    fun `a first save is recorded as a creation`() = runTest {
        save(record("1"))

        assertEquals(1, audit.entries.size)
        val entry = audit.entries.single()
        assertEquals(AuditAction.RECORD_CREATED, entry.action)
        assertEquals(BeneficiaryId("1"), entry.recordId)
        assertEquals(UserRole.FIELD_WORKER, entry.actor)
        assertEquals(Timestamp(FIXED_NOW), entry.at)
    }

    @Test
    fun `saving over an existing record is recorded as an update`() = runTest {
        save(record("1"))
        save(record("1", name = "Asha Devi Kumari"))

        // Created then updated, not created twice. The distinction comes from reading the row
        // before writing it — the one-shot findById added for the pull on Day 14.
        assertEquals(
            listOf(AuditAction.RECORD_CREATED, AuditAction.RECORD_UPDATED),
            audit.entries.map { it.action },
        )
    }

    @Test
    fun `a save that fails validation writes nothing to the trail`() = runTest {
        val outcome = save(record("1", ageYears = 400))

        assertTrue(outcome is Outcome.Failure)
        // The trail describes what happened to data. Nothing happened to any data here.
        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `a save that storage refuses writes nothing to the trail`() = runTest {
        repository.failNextWrite = true

        val outcome = save(record("1"))

        assertTrue(outcome is Outcome.Failure)
        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `a supervisor cannot capture, and the refusal is not an audit event`() = runTest {
        session.role.value = UserRole.SUPERVISOR

        val outcome = save(record("1"))

        assertTrue(outcome is Outcome.Failure)
        assertEquals(SaveError.NotPermitted(UserRole.SUPERVISOR), (outcome as Outcome.Failure).error)
        assertTrue(repository.written.isEmpty())
        // A refused attempt is worth logging in a system with real identities; here it would
        // record that the device's own switch was in the wrong position, which tells nobody
        // anything. Deliberate, and revisited if authentication ever lands.
        assertTrue(audit.entries.isEmpty())
    }

    @Test
    fun `switching role is attributed to the role being left`() = runTest {
        switchRole(UserRole.SUPERVISOR)

        assertEquals(UserRole.SUPERVISOR, session.role.value)
        val entry = audit.entries.single()
        assertEquals(AuditAction.ROLE_CHANGED, entry.action)
        // The role being LEFT. Attributing the switch to the role being taken would let
        // someone become a supervisor and have the trail say a supervisor authorised it.
        assertEquals(UserRole.FIELD_WORKER, entry.actor)
        assertEquals(null, entry.recordId)
    }

    @Test
    fun `switching to the role already active records nothing`() = runTest {
        switchRole(UserRole.FIELD_WORKER)

        // A no-op is not an event. Without this the trail fills with role changes every time
        // someone taps the control twice, and the entries that matter scroll off the screen.
        assertTrue(audit.entries.isEmpty())
    }

    private fun record(id: String, name: String = "Asha Devi", ageYears: Int = 3) = Beneficiary(
        id = BeneficiaryId(id),
        name = name,
        ageYears = ageYears,
        village = "Kotri",
        measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(0L),
        syncStatus = SyncStatus.PENDING,
    )

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}

private class FakeSessionRepository : SessionRepository {
    val role = MutableStateFlow(UserRole.FIELD_WORKER)

    override fun observeActiveRole(): Flow<UserRole> = role
    override suspend fun activeRole(): UserRole = role.value
    override suspend fun setActiveRole(role: UserRole) {
        this.role.value = role
    }
}

/**
 * Records what was appended, and nothing else.
 *
 * Note what it does not do: it assigns ids sequentially and offers no way to change an entry,
 * mirroring the real repository's interface. A fake with an `update` would let a test pass
 * against production code that mutated the trail.
 */
private class RecordingAuditRepository : AuditRepository {
    val entries = mutableListOf<AuditEntry>()

    override suspend fun append(
        actor: UserRole,
        action: AuditAction,
        recordId: BeneficiaryId?,
        at: Timestamp,
    ) {
        entries += AuditEntry(
            id = entries.size + 1L,
            actor = actor,
            action = action,
            recordId = recordId,
            at = at,
        )
    }

    override fun observeRecent(limit: Int): Flow<List<AuditEntry>> =
        MutableStateFlow(entries.takeLast(limit).reversed())
}

/** A minimal store: enough to distinguish "record exists" from "record does not". */
private class RecordingRepository : BeneficiaryRepository {
    private val records = mutableMapOf<BeneficiaryId, Beneficiary>()

    var failNextWrite: Boolean = false

    val written: List<Beneficiary> get() = records.values.toList()

    override suspend fun findById(id: BeneficiaryId): Beneficiary? = records[id]

    override suspend fun upsert(
        beneficiary: Beneficiary,
    ): Outcome<Unit, RepositoryError> {
        if (failNextWrite) {
            failNextWrite = false
            return Outcome.Failure(
                RepositoryError.StorageFailure(
                    IllegalStateException("disk full"),
                ),
            )
        }
        records[beneficiary.id] = beneficiary
        return Outcome.success()
    }

    override fun pagedRecords(): Flow<PagingData<Beneficiary>> =
        MutableStateFlow(PagingData.from(records.values.toList()))

    override fun observeAwaitingSyncCount(): Flow<Int> =
        MutableStateFlow(records.values.count { it.syncStatus != SyncStatus.SYNCED })

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> = MutableStateFlow(records[id])

    override suspend fun pendingSync(): List<Beneficiary> =
        records.values.filter { it.syncStatus == SyncStatus.PENDING }

    override suspend fun updateSyncStatus(
        id: BeneficiaryId,
        unchangedSince: Timestamp,
        status: SyncStatus,
    ): Boolean {
        val unchanged = records[id]?.takeIf { it.updatedAt == unchangedSince }
        if (unchanged != null) records[id] = unchanged.copy(syncStatus = status)
        return unchanged != null
    }

    override suspend fun applyRemote(record: Beneficiary, replacingLocalVersion: Timestamp?): Boolean {
        val stored = records[record.id]
        val mayWrite = if (replacingLocalVersion == null) {
            stored == null
        } else {
            stored?.updatedAt == replacingLocalVersion
        }
        if (mayWrite) records[record.id] = record
        return mayWrite
    }
}
