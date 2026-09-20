package com.astracare.core.data.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.astracare.core.data.database.entity.AuditEntryEntity
import com.astracare.core.data.database.entity.BeneficiaryEntity
import com.astracare.core.data.database.entity.MeasurementEmbedded
import com.astracare.core.data.database.migration.createAuditLogTriggers
import com.astracare.core.domain.ordering.RecordAttentionOrder
import com.astracare.core.model.SyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The SQL, against SQLite.
 *
 * Deferred since Day 12, when ordering moved out of Kotlin and into a `CASE` expression and the
 * decision log noted — correctly — that no test covered the half that actually runs. Since then
 * three more pieces of load-bearing SQL have been written and none of them had a test either:
 * the conditional update behind the stale-write guard, `INSERT OR IGNORE` behind the pull, and
 * the append-only triggers.
 *
 * Every one of those is a claim the project makes in prose. A fake DAO cannot verify any of
 * them, by construction: a fake that sorted correctly would prove the fake was written
 * correctly and would keep passing while the real `ORDER BY` was wrong — which is the failure
 * mode `FakePagedBeneficiaryRepository`'s documentation named on Day 12 and deliberately
 * accepted until there was a real database to test against.
 *
 * ## In-memory, with the trigger callback
 *
 * `inMemoryDatabaseBuilder` so the tests are fast and isolated, and `addCallback` because that
 * is how the production builder installs the append-only triggers. Leaving the callback out
 * would build a database subtly unlike the app's — and would hide exactly the Day 17 trap the
 * last test here exists to catch.
 */
@RunWith(AndroidJUnit4::class)
class BeneficiaryDaoTest {

    private lateinit var database: AstraCareDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AstraCareDatabase::class.java,
        )
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = createAuditLogTriggers(db)
                },
            )
            .build()
    }

    @After
    fun tearDown() = database.close()

    private val dao get() = database.beneficiaryDao()

    // ---- the CASE ordering ---------------------------------------------------------------------

    @Test
    fun pagedByAttention_ordersByUrgencyThenRecency() = runBlocking {
        // Inserted in the wrong order on purpose, so a query that returned insertion order
        // would fail rather than accidentally pass.
        dao.upsert(entity("synced", SyncStatus.SYNCED, recordedAt = 500))
        dao.upsert(entity("pending", SyncStatus.PENDING, recordedAt = 400))
        dao.upsert(entity("failed", SyncStatus.FAILED, recordedAt = 300))
        dao.upsert(entity("rejected", SyncStatus.REJECTED, recordedAt = 200))
        dao.upsert(entity("conflicted", SyncStatus.CONFLICTED, recordedAt = 100))

        val page = loadFirstPage()

        // Exactly RecordAttentionOrder.byUrgency, and note the recency values run the other
        // way: the oldest record sorts first because urgency wins. That is the whole rule —
        // "what is still only on this handset" before "what is newest".
        assertEquals(
            listOf("conflicted", "rejected", "failed", "pending", "synced"),
            page.map { it.id },
        )
    }

    @Test
    fun pagedByAttention_ordersByRecencyWithinAStatus() = runBlocking {
        dao.upsert(entity("older", SyncStatus.PENDING, recordedAt = 100))
        dao.upsert(entity("newer", SyncStatus.PENDING, recordedAt = 900))

        assertEquals(listOf("newer", "older"), loadFirstPage().map { it.id })
    }

    @Test
    fun pagedByAttention_matchesTheDomainDeclaration() = runBlocking {
        // The two halves of the contract, joined. `RecordAttentionOrderTest` pins the domain's
        // list and this pins the SQL; neither alone proves they agree, and nothing in the type
        // system connects a Kotlin enum to a `CASE` arm.
        RecordAttentionOrder.byUrgency.forEachIndexed { index, status ->
            dao.upsert(entity(status.name, status, recordedAt = index.toLong()))
        }

        assertEquals(
            RecordAttentionOrder.byUrgency.map { it.name },
            loadFirstPage().map { it.id },
        )
    }

    // ---- the conditional writes ------------------------------------------------------------------

    @Test
    fun updateSyncStatusIfUnchanged_appliesWhenTheRowHasNotMoved() = runBlocking {
        dao.upsert(entity("a1", SyncStatus.PENDING, updatedAt = 1_000))

        val changed = dao.updateSyncStatusIfUnchanged("a1", 1_000, SyncStatus.SYNCED.name)

        assertEquals(1, changed)
        assertEquals(SyncStatus.SYNCED.name, dao.findById("a1")?.syncStatus)
    }

    @Test
    fun updateSyncStatusIfUnchanged_refusesWhenTheRowMoved() = runBlocking {
        dao.upsert(entity("a1", SyncStatus.PENDING, updatedAt = 2_000))

        val changed = dao.updateSyncStatusIfUnchanged("a1", 1_000, SyncStatus.SYNCED.name)

        // The stale write, in SQL. Zero rows, and the record stays PENDING so the next pass
        // picks it up. Marking it SYNCED here would claim the health worker's newer edit had
        // reached the server when only the older version had.
        assertEquals(0, changed)
        assertEquals(SyncStatus.PENDING.name, dao.findById("a1")?.syncStatus)
    }

    @Test
    fun replaceIfUnchanged_appliesOnlyWhenTheRowHasNotMoved() = runBlocking {
        dao.upsert(entity("a1", SyncStatus.PENDING, updatedAt = 1_000, village = "Kotri"))

        val applied = dao.replaceIfUnchanged(
            entity("a1", SyncStatus.SYNCED, updatedAt = 3_000, village = "Kotri Kalan"),
            unchangedSince = 1_000,
        )
        val refused = dao.replaceIfUnchanged(
            entity("a1", SyncStatus.SYNCED, updatedAt = 4_000, village = "Somewhere Else"),
            unchangedSince = 1_000,
        )

        assertTrue(applied)
        assertFalse(refused)
        assertEquals("Kotri Kalan", dao.findById("a1")?.village)
    }

    @Test
    fun insertIfAbsent_doesNotOverwriteALocalCaptureThatArrivedFirst() = runBlocking {
        dao.upsert(entity("a1", SyncStatus.PENDING, village = "Kotri"))

        val rowId = dao.insertIfAbsent(entity("a1", SyncStatus.SYNCED, village = "From The Server"))

        // IGNORE, not REPLACE. REPLACE is DELETE plus INSERT, so it would discard a record the
        // health worker captured between the pull deciding and the write landing — one that
        // has never been sent anywhere.
        assertEquals(-1L, rowId)
        assertEquals("Kotri", dao.findById("a1")?.village)
    }

    @Test
    fun pendingSync_returnsOnlyTheRetryableStatuses() = runBlocking {
        SyncStatus.entries.forEach { dao.upsert(entity(it.name, it)) }

        val queued = dao.pendingSync(listOf(SyncStatus.PENDING.name, SyncStatus.FAILED.name))

        assertEquals(setOf("PENDING", "FAILED"), queued.map { it.id }.toSet())
    }

    // ---- the append-only triggers, on the fresh-install path ---------------------------------------

    @Test
    fun auditLog_isAppendOnlyOnAFreshDatabase() = runBlocking {
        // The Day 17 trap, asserted. Room builds a new schema from the compiled entities and
        // runs NO migration, and `@Entity` cannot declare a trigger — so a migration-only
        // version of this guarantee holds on upgraded devices and silently fails on every new
        // install. Room does not validate triggers, so nothing else would notice.
        database.auditDao().insert(
            AuditEntryEntity(actor = "FIELD_WORKER", action = "RECORD_CREATED", recordId = "a1", atEpochMillis = 1),
        )

        val db = database.openHelper.writableDatabase
        assertTrue(rejects { db.execSQL("UPDATE audit_log SET action = 'ROLE_CHANGED'") })
        assertTrue(rejects { db.execSQL("DELETE FROM audit_log") })
    }

    @Test
    fun auditLog_stillAcceptsInserts() = runBlocking {
        // The control. If the triggers were somehow rejecting everything, the test above would
        // pass and the app would be unable to write a trail at all.
        repeat(3) { index ->
            database.auditDao().insert(
                AuditEntryEntity(
                    actor = "FIELD_WORKER",
                    action = "RECORD_CREATED",
                    recordId = "a$index",
                    atEpochMillis = index.toLong(),
                ),
            )
        }

        val db = database.openHelper.writableDatabase
        db.query("SELECT COUNT(*) FROM audit_log").use { cursor ->
            cursor.moveToFirst()
            assertEquals(3, cursor.getInt(0))
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private suspend fun loadFirstPage(): List<BeneficiaryEntity> {
        val order = RecordAttentionOrder.byUrgency.map { it.name }
        val source = dao.pagedByAttention(
            order[0],
            order[1],
            order[2],
            order[3],
            order[4],
        )
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = PAGE, placeholdersEnabled = false),
        )
        return (result as PagingSource.LoadResult.Page).data
    }

    private fun rejects(block: () -> Unit): Boolean = runCatching(block).isFailure

    private fun entity(
        id: String,
        syncStatus: SyncStatus,
        recordedAt: Long = 0,
        updatedAt: Long = 0,
        village: String = "Kotri",
    ) = BeneficiaryEntity(
        id = id,
        name = "Asha Devi",
        ageYears = 3,
        village = village,
        measurement = MeasurementEmbedded(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAtEpochMillis = recordedAt,
        updatedAtEpochMillis = updatedAt,
        syncStatus = syncStatus.name,
    )

    private companion object {
        const val PAGE = 20
    }
}
