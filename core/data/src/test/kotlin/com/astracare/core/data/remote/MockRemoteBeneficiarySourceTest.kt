package com.astracare.core.data.remote

import com.astracare.core.common.log.NoOpLogger
import com.astracare.core.common.time.TimeProvider
import com.astracare.core.domain.remote.PullOutcome
import com.astracare.core.domain.remote.PushOutcome
import com.astracare.core.domain.remote.SyncCursor
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stand-in server's own behaviour.
 *
 * Until Day 19 this class was verified only through the use cases that consume it — which is
 * backwards. Every sync test in the project leans on it, so a bug here does not fail one test,
 * it quietly changes what a dozen others are actually asserting. It also had no direct test for
 * a mundane reason: two `Log.d` calls made it impossible to instantiate in a JVM test. The
 * `Logger` seam introduced this day is what unblocked it.
 *
 * ## What is worth testing in a mock
 *
 * Not "does it store things" — that is a `LinkedHashMap`. What matters are the four properties
 * the sync engine is *designed against*, and which would make the engine's tests lie if they
 * were wrong:
 *
 *  - **Idempotent accept**, because retrying after a lost response is only safe if the second
 *    delivery is a no-op. Day 13's whole retry story rests on it.
 *  - **Deterministic failure**, because a random mock produces a demo that sometimes misbehaves
 *    and a bug nobody can reproduce.
 *  - **A correct delta**, because a pull that returns too little loses updates and a pull that
 *    returns everything makes the cursor pointless.
 *  - **A clock the device does not control**, because the conflict design exists for exactly
 *    the case where the two disagree. A mock that stamped server edits with the device clock
 *    would make Day 14's tests pass for the wrong reason.
 *
 * `StandardTestDispatcher`, because the class `delay`s to simulate a round trip. On virtual
 * time that costs nothing; on a real dispatcher this file would take twenty seconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockRemoteBeneficiarySourceTest {

    private val dispatcher = StandardTestDispatcher()

    private fun source(now: Long = FIXED_NOW) =
        MockRemoteBeneficiarySource(TimeProvider { Timestamp(now) }, NoOpLogger, dispatcher)

    // ---- accepting ---------------------------------------------------------------------------

    @Test
    fun `a valid record is accepted`() = runTest(dispatcher) {
        val remote = source()

        val outcome = remote.push(record("1"))

        assertEquals(PushOutcome.Accepted, outcome)
    }

    @Test
    fun `re-delivering the same record is accepted again and stores one copy`() =
        runTest(dispatcher) {
            val remote = source()

            assertEquals(PushOutcome.Accepted, remote.push(record("1")))
            assertEquals(PushOutcome.Accepted, remote.push(record("1")))

            // One record, not two. IDs are minted on the device, so the server upserts by a key
            // it did not choose — which is the only reason a client may safely resend after a
            // response goes missing. A duplicate here would mean Day 13's retry loop silently
            // creating a second record per lost acknowledgement.
            val changes = remote.pullChangedSince(null) as PullOutcome.Changes
            assertEquals(1, changes.records.size)
        }

    @Test
    fun `re-delivering an edited record replaces the stored version`() = runTest(dispatcher) {
        // The half of "idempotent" that the record-count test above does NOT cover, and this
        // gap was found by mutation rather than by reading: swapping `put` for `putIfAbsent`
        // still yields exactly one stored record, so the count assertion passes while the
        // server silently keeps the *first* version forever. Every subsequent edit a health
        // worker made would be accepted, acknowledged, and discarded.
        //
        // Idempotent upsert and ignore-if-present are different guarantees. Day 13's retry
        // story needs the first one.
        val remote = source()
        remote.push(record("1", village = "Kotri"))

        remote.push(record("1", village = "Kotri Kalan"))

        val changes = remote.pullChangedSince(null) as PullOutcome.Changes
        assertEquals("Kotri Kalan", changes.records.single().village)
    }

    @Test
    fun `an accepted record comes back marked synced`() = runTest(dispatcher) {
        val remote = source()
        remote.push(record("1", syncStatus = SyncStatus.PENDING))

        val changes = remote.pullChangedSince(null) as PullOutcome.Changes

        // The contract on `pullChangedSince`: a record the server is handing out is, by
        // definition, one the server has. The resolver relies on this to decide anything.
        assertEquals(SyncStatus.SYNCED, changes.records.single().syncStatus)
    }

    @Test
    fun `a record with no village is rejected, and rejected again`() = runTest(dispatcher) {
        val remote = source()

        val first = remote.push(record("1", village = ""))
        val second = remote.push(record("1", village = "  "))

        // A 4xx shape: retrying cannot fix it. If this ever became transient, the push loop
        // would retry a permanently bad record until the battery died.
        assertTrue(first is PushOutcome.Rejected)
        assertTrue(second is PushOutcome.Rejected)
    }

    @Test
    fun `a rejected record is not stored`() = runTest(dispatcher) {
        val remote = source()
        remote.push(record("1", village = ""))

        val changes = remote.pullChangedSince(null) as PullOutcome.Changes

        assertTrue(changes.records.isEmpty())
    }

    // ---- failing -----------------------------------------------------------------------------

    @Test
    fun `every fifth push fails transiently, deterministically`() = runTest(dispatcher) {
        val remote = source()

        val outcomes = (1..10).map { remote.push(record("$it")) }

        // Positions 5 and 10, every run. A random mock would make this test flaky and a demo
        // unreproducible; the whole point of the counter is that the sequence is fixed.
        assertEquals(
            listOf(5, 10),
            outcomes.mapIndexedNotNull { index, outcome ->
                (index + 1).takeIf { outcome is PushOutcome.TransientFailure }
            },
        )
    }

    @Test
    fun `a transiently failed push is not stored`() = runTest(dispatcher) {
        val remote = source()
        repeat(4) { remote.push(record("$it")) }

        val failed = remote.push(record("the-fifth"))

        assertTrue(failed is PushOutcome.TransientFailure)
        val changes = remote.pullChangedSince(null) as PullOutcome.Changes
        // Four stored, not five. A server that kept a record it reported as failed would make
        // the client's retry produce a duplicate under a different guarantee than idempotence.
        assertEquals(4, changes.records.size)
    }

    @Test
    fun `pull failures are counted separately from push failures`() = runTest(dispatcher) {
        val remote = source()
        // Four pushes, so the push counter sits at 4 and the next push would fail.
        repeat(4) { remote.push(record("$it")) }

        // The pull counter is independent and at zero, so this must succeed.
        assertTrue(remote.pullChangedSince(null) is PullOutcome.Changes)
    }

    // ---- the delta ----------------------------------------------------------------------------

    @Test
    fun `a first pull with no cursor returns everything`() = runTest(dispatcher) {
        val remote = source()
        remote.push(record("1"))
        remote.push(record("2"))

        val changes = remote.pullChangedSince(null) as PullOutcome.Changes

        assertEquals(setOf("1", "2"), changes.records.map { it.id.value }.toSet())
    }

    @Test
    fun `a pull with the previous cursor returns only what changed since`() = runTest(dispatcher) {
        val remote = source()
        remote.push(record("1"))
        val first = remote.pullChangedSince(null) as PullOutcome.Changes

        remote.push(record("2"))
        val second = remote.pullChangedSince(first.nextCursor) as PullOutcome.Changes

        // Exactly the new record. Too few and updates are lost; everything and the cursor is
        // decoration.
        assertEquals(listOf("2"), second.records.map { it.id.value })
    }

    @Test
    fun `an idle device pulling twice gets nothing the second time`() = runTest(dispatcher) {
        val remote = source()
        remote.push(record("1"))
        val first = remote.pullChangedSince(null) as PullOutcome.Changes

        val second = remote.pullChangedSince(first.nextCursor) as PullOutcome.Changes

        assertTrue(second.records.isEmpty())
        // The cursor still advances to the stream head, so an idle handset does not re-examine
        // the same empty window on every pass forever.
        assertEquals(first.nextCursor, second.nextCursor)
    }

    @Test
    fun `an unreadable cursor is treated as no cursor rather than throwing`() =
        runTest(dispatcher) {
            val remote = source()
            remote.push(record("1"))

            val changes = remote.pullChangedSince(SyncCursor("not-a-number")) as PullOutcome.Changes

            // The client stores the cursor verbatim and never interprets it, so a corrupted one
            // is possible. Falling back to a full pull is the safe direction: expensive, and it
            // cannot skip a record. Throwing would strand the device.
            assertEquals(1, changes.records.size)
        }

    // ---- the server's own clock -----------------------------------------------------------------

    @Test
    fun `a pushed record keeps the timestamp the device gave it`() = runTest(dispatcher) {
        val remote = source()
        remote.push(record("1", updatedAt = 4_242L))

        val changes = remote.pullChangedSince(null) as PullOutcome.Changes

        // `updatedAt` is when the record was *edited*, which only the capturing device knows.
        // Restamping it on accept would make every subsequent pull look like a change and
        // rewrite the whole table on every pass.
        assertEquals(Timestamp(4_242L), changes.records.single().updatedAt)
    }

    @Test
    fun `a server-side edit carries a timestamp the device did not produce`() =
        runTest(dispatcher) {
            val remote = source()
            remote.push(record("1", updatedAt = 4_242L))

            // Every third pull edits the oldest record, as another worker's handset would.
            repeat(SERVER_EDIT_ON_PULL) { remote.pullChangedSince(null) }
            val changes = remote.pullChangedSince(null) as PullOutcome.Changes
            val edited = changes.records.single { it.id.value == "1" }

            assertTrue("the server did not edit anything", edited.village.endsWith("(corrected)"))
            // The point of the whole exercise: two clocks, no coordination. A mock that stamped
            // this with `timeProvider.now()` would model a server whose clock agrees with the
            // handset's, and Day 14's conflict tests would pass for the wrong reason.
            assertNotEquals(Timestamp(4_242L), edited.updatedAt)
            assertTrue(edited.updatedAt.epochMillis >= FIXED_NOW)
        }

    @Test
    fun `a call takes simulated round-trip time rather than none`() = runTest(dispatcher) {
        val remote = source()
        val before = testScheduler.currentTime

        remote.push(record("1"))
        remote.pullChangedSince(null)

        // Asserted against virtual time, so it costs nothing to run and still fails if the
        // delay is removed. The delay is what makes the PENDING chip visible long enough to
        // watch it flip on a real device — load-bearing for the demo, and exactly the sort of
        // line a tidy-up deletes as pointless.
        assertTrue(
            "both calls returned instantly; the simulated round trip is gone",
            testScheduler.currentTime > before,
        )
    }

    private fun record(
        id: String,
        village: String = "Kotri",
        updatedAt: Long = 1_000L,
        syncStatus: SyncStatus = SyncStatus.PENDING,
    ) = Beneficiary(
        id = BeneficiaryId(id),
        name = "Asha Devi",
        ageYears = 3,
        village = village,
        measurement = Measurement(weightKg = 12.4, heightCm = 91.0, muacMm = null),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(updatedAt),
        syncStatus = syncStatus,
    )

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L

        /** Mirrors `SERVER_EDIT_EVERY`, which is private. Kept here so the intent is readable. */
        const val SERVER_EDIT_ON_PULL = 3
    }
}
