package com.astracare.core.data.repository

import com.astracare.core.data.database.dao.AuditDao
import com.astracare.core.data.database.dao.SessionDao
import com.astracare.core.data.database.entity.AuditEntryEntity
import com.astracare.core.data.database.entity.SessionEntity
import com.astracare.core.model.AuditAction
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Timestamp
import com.astracare.core.model.UserRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app does with a stored value it does not recognise.
 *
 * Three places decode an enum out of a TEXT column, and all three fall back rather than throw.
 * The reason is the same in each case and it is not tidiness: a row written by a *newer* build
 * — after a downgrade, or a partially applied migration — must not crash the app, because the
 * app is the only thing that can send the health worker's unsynced records anywhere.
 *
 * Each fallback is also a *choice about which way to be wrong*, and that is the part worth
 * pinning:
 *
 *  - An unknown **role** becomes the least privileged one, so an unreadable session grants
 *    less access rather than more.
 *  - An unknown **actor** on an audit entry becomes the least privileged role too, so the
 *    trail never attributes an action to more authority than it can evidence.
 *  - An unknown **action** becomes `RECORD_UPDATED`, the most ordinary of the four, so an
 *    unreadable row is not promoted into a conflict alert.
 *
 * None of these is reachable in a test without writing the raw value, which is exactly why
 * they were unverified until Day 18: they are correct-looking comments guarding a case that
 * only production produces.
 *
 * `UnconfinedTestDispatcher` because both repositories hop to an injected IO dispatcher and
 * there is nothing asynchronous to schedule — the work is a map over a value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefensiveDecodingTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // ---- session -----------------------------------------------------------------------------

    @Test
    fun `a device that has never chosen a role gets the default`() = runTest {
        val repository = RoomSessionRepository(FakeSessionDao(stored = null), dispatcher)

        assertEquals(UserRole.Default, repository.activeRole())
        assertEquals(UserRole.Default, repository.observeActiveRole().first())
    }

    @Test
    fun `a stored role round trips`() = runTest {
        val dao = FakeSessionDao(stored = null)
        val repository = RoomSessionRepository(dao, dispatcher)

        repository.setActiveRole(UserRole.SUPERVISOR)

        assertEquals(UserRole.SUPERVISOR, repository.activeRole())
        // Stored as the enum NAME, like every other enum in this schema.
        assertEquals("SUPERVISOR", dao.row.value?.activeRole)
    }

    @Test
    fun `a role this build does not recognise falls back to the least privileged one`() = runTest {
        val repository = RoomSessionRepository(FakeSessionDao(SessionEntity(activeRole = "AUDITOR")), dispatcher)

        // Not SUPERVISOR, and not a crash. An unreadable session must not hand out access the
        // app cannot justify.
        assertEquals(UserRole.FIELD_WORKER, repository.activeRole())
    }

    @Test
    fun `every role survives being written and read back`() = runTest {
        val dao = FakeSessionDao(stored = null)
        val repository = RoomSessionRepository(dao, dispatcher)

        UserRole.entries.forEach { role ->
            repository.setActiveRole(role)

            assertEquals("$role did not survive the round trip", role, repository.activeRole())
        }
    }

    // ---- audit trail -------------------------------------------------------------------------

    @Test
    fun `an appended entry keeps its actor, action, record and time`() = runTest {
        val dao = FakeAuditDao()
        val repository = RoomAuditRepository(dao, dispatcher)

        repository.append(
            actor = UserRole.SUPERVISOR,
            action = AuditAction.CONFLICT_DETECTED,
            recordId = BeneficiaryId("a1b2"),
            at = Timestamp(1_700_000_000_000L),
        )

        val entry = repository.observeRecent(limit = 10).first().single()
        assertEquals(UserRole.SUPERVISOR, entry.actor)
        assertEquals(AuditAction.CONFLICT_DETECTED, entry.action)
        assertEquals(BeneficiaryId("a1b2"), entry.recordId)
        assertEquals(Timestamp(1_700_000_000_000L), entry.at)
    }

    @Test
    fun `a device-scoped entry keeps a null record id rather than an empty string`() = runTest {
        val repository = RoomAuditRepository(FakeAuditDao(), dispatcher)

        repository.append(UserRole.FIELD_WORKER, AuditAction.ROLE_CHANGED, null, Timestamp(1L))

        // Null means "this concerns the device", and the screen renders it differently. An
        // empty string would render as `Record ` with nothing after it.
        assertEquals(null, repository.observeRecent(limit = 10).first().single().recordId)
    }

    @Test
    fun `an actor this build does not recognise reads as the least privileged role`() = runTest {
        val dao = FakeAuditDao(stored = listOf(row(actor = "DISTRICT_OFFICER")))
        val repository = RoomAuditRepository(dao, dispatcher)

        // The trail must never claim more authority than it can evidence. Showing an
        // unreadable actor as a supervisor would do exactly that.
        assertEquals(UserRole.FIELD_WORKER, repository.observeRecent(10).first().single().actor)
    }

    @Test
    fun `an action this build does not recognise reads as a record update`() = runTest {
        val dao = FakeAuditDao(stored = listOf(row(action = "RECORD_DELETED")))
        val repository = RoomAuditRepository(dao, dispatcher)

        // Something happened to a record and this build cannot say what. RECORD_UPDATED is the
        // honest floor: CONFLICT_DETECTED would raise an alarm that nothing raised, and
        // dropping the row would hide an event that occurred.
        assertEquals(AuditAction.RECORD_UPDATED, repository.observeRecent(10).first().single().action)
    }

    @Test
    fun `every action survives being written and read back`() = runTest {
        val dao = FakeAuditDao()
        val repository = RoomAuditRepository(dao, dispatcher)

        AuditAction.entries.forEach { action ->
            repository.append(UserRole.FIELD_WORKER, action, null, Timestamp(1L))
        }

        // Guards the fallback above from swallowing a genuinely new action: without this, a
        // fifth AuditAction would decode as RECORD_UPDATED and every test would still pass.
        assertEquals(
            AuditAction.entries.toSet(),
            repository.observeRecent(AuditAction.entries.size).first().map { it.action }.toSet(),
        )
    }

    private fun row(
        actor: String = "FIELD_WORKER",
        action: String = "RECORD_CREATED",
    ) = AuditEntryEntity(
        id = 1L,
        actor = actor,
        action = action,
        recordId = "a1b2",
        atEpochMillis = 1L,
    )
}

/**
 * A fake DAO, not a mock.
 *
 * It holds a row and hands it back, which is all the real one does that these tests care
 * about. Note what it does **not** offer: no update and no delete, mirroring `AuditDao` — a
 * fake with them would let a test pass against code that mutated the trail.
 */
private class FakeAuditDao(stored: List<AuditEntryEntity> = emptyList()) : AuditDao {

    private val rows = MutableStateFlow(stored)

    override suspend fun insert(entry: AuditEntryEntity) {
        // Mirrors autoGenerate: the store assigns the id, monotonically.
        rows.value = rows.value + entry.copy(id = rows.value.size + 1L)
    }

    override fun observeRecent(limit: Int): Flow<List<AuditEntryEntity>> =
        MutableStateFlow(rows.value.takeLast(limit).reversed())
}

private class FakeSessionDao(stored: SessionEntity?) : SessionDao {

    val row = MutableStateFlow(stored)

    override fun observe(id: Int): Flow<SessionEntity?> = row

    override suspend fun load(id: Int): SessionEntity? = row.value

    override suspend fun upsert(entity: SessionEntity) {
        row.value = entity
    }
}
