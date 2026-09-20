package com.astracare.core.domain.sync

import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Measurement
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conflict rule, as a truth table.
 *
 * [ConflictResolver.resolve] is a pure function over two values, so the tests are a table of
 * cases rather than an arrangement of fakes into states. That is the payoff for keeping the
 * decision out of the use case and out of the Worker: the rule the project is judged on is
 * checkable by reading a list.
 *
 * Two of these tests are not about a case at all. `the rule never consults the clock` and
 * `every sync status has a decided outcome` exist to fail when someone later reintroduces a
 * timestamp comparison, or adds a `SyncStatus` and does not decide what it means here.
 */
class ConflictResolverTest {

    @Test
    fun `a record this device has never seen is inserted`() {
        val remote = record("1", village = "Kotri")

        val resolution = ConflictResolver.resolve(local = null, remote = remote)

        assertEquals(ConflictResolution.AcceptRemote(remote, replacingLocalVersion = null), resolution)
    }

    @Test
    fun `a synced record takes the server version`() {
        // Nothing on this handset is unsent, so there is no local edit the server's version
        // could be destroying. This is the only case in which overwriting is provably safe.
        val local = record("1", village = "Kotri", status = SyncStatus.SYNCED)
        val remote = record("1", village = "Kotri Kalan")

        val resolution = ConflictResolver.resolve(local, remote)

        assertEquals(
            ConflictResolution.AcceptRemote(remote, replacingLocalVersion = local.updatedAt),
            resolution,
        )
    }

    @Test
    fun `a synced record that already matches is left alone`() {
        // Not a micro-optimisation. Every write invalidates Room's PagingSource, so rewriting
        // forty unchanged rows on every pass reloads the list under the health worker's thumb.
        val local = record("1", village = "Kotri", status = SyncStatus.SYNCED)
        val remote = record("1", village = "Kotri", updatedAt = 9_000L)

        assertEquals(ConflictResolution.KeepLocal, ConflictResolver.resolve(local, remote))
    }

    @Test
    fun `a pending edit against a different server version is a conflict`() {
        // The case the whole design exists for: a supervisor corrected the village while the
        // health worker corrected the weight. Both edits are real and neither is wrong.
        val local = record("1", village = "Kotri", weightKg = 13.1, status = SyncStatus.PENDING)
        val remote = record("1", village = "Kotri Kalan", weightKg = 12.4)

        val resolution = ConflictResolver.resolve(local, remote)

        assertEquals(ConflictResolution.FlagConflict(local.id, local.updatedAt), resolution)
    }

    @Test
    fun `a failed edit is treated exactly like a pending one`() {
        // FAILED means "the send did not get through", not "the edit is less real".
        val local = record("1", village = "Kotri", status = SyncStatus.FAILED)
        val remote = record("1", village = "Kotri Kalan")

        assertTrue(ConflictResolver.resolve(local, remote) is ConflictResolution.FlagConflict)
    }

    @Test
    fun `a rejected record still conflicts rather than being discarded`() {
        // Tempting to treat REJECTED as worthless — the server refused it, after all. But a
        // refused record is still the only copy of what a health worker wrote down, and
        // "the server would not take it" is not the same as "it is wrong".
        val local = record("1", village = "Kotri", status = SyncStatus.REJECTED)
        val remote = record("1", village = "Kotri Kalan")

        assertTrue(ConflictResolver.resolve(local, remote) is ConflictResolution.FlagConflict)
    }

    @Test
    fun `an unsent edit that matches the server is resolved, not flagged`() {
        // Two people made the same correction, or an earlier push was accepted and the
        // acknowledgement was lost. Nothing is in conflict when nothing differs, and a red
        // chip here teaches the health worker that the red chip means nothing.
        val local = record("1", village = "Kotri", status = SyncStatus.PENDING)
        val remote = record("1", village = "Kotri", updatedAt = 9_000L)

        val resolution = ConflictResolver.resolve(local, remote)

        assertEquals(
            ConflictResolution.AcceptRemote(remote, replacingLocalVersion = local.updatedAt),
            resolution,
        )
    }

    @Test
    fun `an already conflicted record is not re-flagged`() {
        val local = record("1", village = "Kotri", status = SyncStatus.CONFLICTED)
        val remote = record("1", village = "Kotri Kalan")

        assertEquals(ConflictResolution.KeepLocal, ConflictResolver.resolve(local, remote))
    }

    @Test
    fun `a conflict the server has converged onto is resolved`() {
        // Someone fixed it on the other side. The conflict is genuinely gone, so the record
        // should stop demanding attention rather than staying red forever.
        val local = record("1", village = "Kotri", status = SyncStatus.CONFLICTED)
        val remote = record("1", village = "Kotri")

        assertTrue(ConflictResolver.resolve(local, remote) is ConflictResolution.AcceptRemote)
    }

    @Test
    fun `the rule never consults the clock`() {
        // The claim in ConflictResolver's documentation, asserted rather than trusted: the
        // decision depends on sync status and content, never on which side has the larger
        // updatedAt. Running the same pair twice with the timestamps swapped must give the
        // same answer both times.
        //
        // This is what stops someone "fixing" a conflict by adding `if (remote.updatedAt >
        // local.updatedAt)`, which is last-write-wins and silently deletes field data.
        val local = record("1", village = "Kotri", status = SyncStatus.PENDING, updatedAt = 1_000L)
        val remote = record("1", village = "Kotri Kalan", updatedAt = 5_000L)

        val remoteNewer = ConflictResolver.resolve(local, remote)
        val remoteOlder = ConflictResolver.resolve(
            local = local.copy(updatedAt = Timestamp(5_000L)),
            remote = remote.copy(updatedAt = Timestamp(1_000L)),
        )

        assertTrue(remoteNewer is ConflictResolution.FlagConflict)
        assertTrue(remoteOlder is ConflictResolution.FlagConflict)
    }

    @Test
    fun `a change to any field is a change`() {
        // Content comparison is written as "normalise the bookkeeping and compare the whole
        // record" rather than as a hand-written field list, precisely so this holds for a
        // field nobody remembered to add to the comparison. Checked field by field, because a
        // forgotten one makes two different records look identical — and a conflict that
        // compares equal is a conflict that silently disappears.
        val local = record("1", village = "Kotri", status = SyncStatus.PENDING)
        val variants = listOf(
            local.copy(name = "Asha Devii"),
            local.copy(ageYears = 4),
            local.copy(village = "Kotri Kalan"),
            local.copy(measurement = local.measurement.copy(weightKg = 12.5)),
            local.copy(measurement = local.measurement.copy(heightCm = 92.0)),
            local.copy(measurement = local.measurement.copy(muacMm = 130.0)),
            local.copy(recordedAt = Timestamp(1L)),
        )

        variants.forEach { variant ->
            val remote = variant.copy(syncStatus = SyncStatus.SYNCED)
            assertTrue(
                "a difference in this field was not treated as a conflict: $remote",
                ConflictResolver.resolve(local, remote) is ConflictResolution.FlagConflict,
            )
        }
    }

    @Test
    fun `every sync status has a decided outcome`() {
        // The drift guard, in the spirit of RecordAttentionOrderTest. Adding a SyncStatus
        // without deciding what it means for a pull would otherwise fall into the `else`
        // branch and be flagged as a conflict — which is the safe default, but a default
        // nobody chose. This test forces the choice to be made here, in writing.
        val remote = record("1", village = "Kotri Kalan")
        val expected = mapOf(
            SyncStatus.SYNCED to ConflictResolution.AcceptRemote::class,
            SyncStatus.PENDING to ConflictResolution.FlagConflict::class,
            SyncStatus.FAILED to ConflictResolution.FlagConflict::class,
            SyncStatus.REJECTED to ConflictResolution.FlagConflict::class,
            SyncStatus.CONFLICTED to ConflictResolution.KeepLocal::class,
        )

        assertEquals(
            "a SyncStatus was added without deciding how a pull should treat it",
            SyncStatus.entries.toSet(),
            expected.keys,
        )
        SyncStatus.entries.forEach { status ->
            val resolution = ConflictResolver.resolve(record("1", village = "Kotri", status = status), remote)
            assertEquals(status.name, expected.getValue(status), resolution::class)
        }
    }

    private fun record(
        id: String,
        village: String,
        weightKg: Double = 12.4,
        status: SyncStatus = SyncStatus.SYNCED,
        updatedAt: Long = 1_000L,
    ) = Beneficiary(
        id = BeneficiaryId(id),
        name = "Asha Devi",
        ageYears = 3,
        village = village,
        measurement = Measurement(weightKg = weightKg, heightCm = 91.0, muacMm = null),
        recordedAt = Timestamp(0L),
        updatedAt = Timestamp(updatedAt),
        syncStatus = status,
    )
}
