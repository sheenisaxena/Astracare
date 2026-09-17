package com.astracare.core.domain.ordering

import com.astracare.core.model.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the seam Day 12 opened.
 *
 * Ordering is now declared in Kotlin and executed in SQL, in two different modules. Nothing in
 * the type system connects `RecordAttentionOrder.byUrgency` to the four `WHEN` arms of
 * `BeneficiaryDao.pagedByAttention`, so the two can drift silently — and the symptom of drift
 * is not a crash. It is a conflicted record quietly sorting below synced ones, on the screen
 * whose entire job is to surface exactly that record.
 *
 * These are the cheapest tests in the project and they exist because that failure is invisible.
 *
 * The first project tests in `:core:domain`, incidentally. The module has had none since Day 7.
 */
class RecordAttentionOrderTest {

    @Test
    fun `every sync status has a declared rank`() {
        // A status missing from the list still renders, still syncs, and sorts to the top via
        // the DAO's ELSE branch — so nothing breaks loudly. It just silently outranks a
        // genuine conflict.
        assertEquals(SyncStatus.entries.toSet(), RecordAttentionOrder.byUrgency.toSet())
    }

    @Test
    fun `no status is declared twice`() {
        assertEquals(
            RecordAttentionOrder.byUrgency.size,
            RecordAttentionOrder.byUrgency.distinct().size,
        )
    }

    @Test
    fun `the DAO has one CASE arm per declared status`() {
        // BeneficiaryDao.pagedByAttention takes rank1..rank4. Adding a SyncStatus means adding
        // an arm and a parameter there, and widening the repository call that fills them.
        // This assertion is the reminder, because the compiler will not give you one.
        assertEquals(DAO_CASE_ARMS, RecordAttentionOrder.byUrgency.size)
    }

    @Test
    fun `urgency runs conflicted, failed, pending, synced`() {
        val ranks = SyncStatus.entries.associateWith { RecordAttentionOrder.rankOf(it) }

        // CONFLICTED needs a human decision. FAILED and PENDING resolve themselves once there
        // is signal. SYNCED needs nothing, so it sorts last.
        assertTrue(ranks.getValue(SyncStatus.CONFLICTED) < ranks.getValue(SyncStatus.FAILED))
        assertTrue(ranks.getValue(SyncStatus.FAILED) < ranks.getValue(SyncStatus.PENDING))
        assertTrue(ranks.getValue(SyncStatus.PENDING) < ranks.getValue(SyncStatus.SYNCED))
    }

    @Test
    fun `ranks start after the slot reserved for an unknown status`() {
        // rankOf must never return MOST_URGENT for a status that is declared, or an unknown
        // status would tie with a real one and the sort would be arbitrary between them.
        val declaredRanks = SyncStatus.entries.map { RecordAttentionOrder.rankOf(it) }

        assertTrue(declaredRanks.all { it > RecordAttentionOrder.MOST_URGENT })
    }

    private companion object {
        const val DAO_CASE_ARMS = 4
    }
}
