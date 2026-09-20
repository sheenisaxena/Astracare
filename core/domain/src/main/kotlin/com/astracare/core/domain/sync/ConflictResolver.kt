package com.astracare.core.domain.sync

import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.SyncStatus
import com.astracare.core.model.Timestamp

/**
 * Decides what to do with one record the server has sent us.
 *
 * This is the claim the whole project is judged on, so it is worth stating the rule in one
 * sentence before the code: **the local sync status, not the clock, says whether there is a
 * conflict.**
 *
 * ## Why not "last write wins"
 *
 * Last-write-wins compares `local.updatedAt` against `remote.updatedAt` and keeps the larger.
 * It is one line, it is what most offline-first tutorials do, and on this app it would
 * silently delete field data. Two reasons:
 *
 *  1. **It resolves cases that are not resolvable.** If a supervisor corrected a village name
 *     on the server while a health worker corrected the child's weight on the handset, both
 *     edits are real and neither is wrong. Last-write-wins discards one of them, with no
 *     record that it did.
 *  2. **The comparison is not sound.** `updatedAt` is stamped from the device's wall clock,
 *     which drifts, jumps when NTP corrects it, and can be set by hand. DECISION_LOG 2.6 has
 *     said so since Day 2. A handset ten minutes fast wins every race it enters.
 *
 * ## What is used instead
 *
 * The question a conflict detector actually needs to answer is *"has this device got an edit
 * the server has never seen?"* — and the device knows that for certain, without consulting any
 * clock. It is exactly what [SyncStatus] records:
 *
 *  - [SyncStatus.SYNCED] means the server acknowledged **this exact version**. Day 13's
 *    conditional mark is what makes that trustworthy: a record whose local edit landed during
 *    a push is never marked SYNCED, so the status cannot overstate what the server has.
 *  - Anything else means there is local work in flight.
 *
 * So the rule is: if the local row is SYNCED, the server's version is newer by construction and
 * applying it loses nothing. If it is not SYNCED, both sides have moved and neither may be
 * overwritten.
 *
 * **[resolve] performs no timestamp comparison at all.** Nothing here reads
 * `local.updatedAt > remote.updatedAt`. That is not incidental — it is the point. Timestamps
 * appear only as the version tokens that make the write conditional, never as a way of deciding
 * who is right.
 *
 * ## What it still does not solve
 *
 * Detecting a conflict is not resolving one. A record marked [SyncStatus.CONFLICTED] holds the
 * local version, shows a red chip, sorts to the top of the list — and can go no further,
 * because there is no merge UI yet. That is a deliberate Day 14 boundary rather than an
 * oversight: a wrong auto-resolution is invisible, and a visible dead end is not. See
 * DECISION_LOG 9.4 and the open items table.
 */
object ConflictResolver {

    /**
     * @param local the row currently in the database, or null if this ID is new to the device.
     * @param remote the server's version, carrying [SyncStatus.SYNCED].
     */
    fun resolve(local: Beneficiary?, remote: Beneficiary): ConflictResolution = when {
        // Never seen here. Another worker's record, or this worker's from another handset.
        // There is nothing local to lose, so there is nothing to resolve.
        local == null -> ConflictResolution.AcceptRemote(remote, replacingLocalVersion = null)

        // The server has acknowledged the local version, so nothing on this handset is
        // unsent. Whatever the server now holds supersedes it, and taking it cannot discard
        // an edit that never left the device — because there is no such edit.
        local.syncStatus == SyncStatus.SYNCED -> acceptUnlessAlreadyCurrent(local, remote)

        // There IS unsent local work, but the two sides say the same thing — two people made
        // the same correction, or an earlier push was accepted and the acknowledgement was
        // lost. Nothing is in conflict when nothing differs, and flagging it anyway would
        // teach the health worker that the red chip means nothing.
        local.hasSameContentAs(remote) ->
            ConflictResolution.AcceptRemote(remote, replacingLocalVersion = local.updatedAt)

        // Already flagged, and the remote has moved again. Re-flagging would rewrite the row
        // for no reason and invalidate every loaded page; a person still has to choose.
        local.syncStatus == SyncStatus.CONFLICTED -> ConflictResolution.KeepLocal

        // PENDING, FAILED or REJECTED with differing content: the device holds an edit the
        // server has never seen, and the server holds one this device has never seen. Both
        // are real. Overwrite neither.
        else -> ConflictResolution.FlagConflict(local.id, local.updatedAt)
    }

    /**
     * The SYNCED branch, split out so the `when` above stays a flat table of cases.
     *
     * Re-applying an identical record is not merely wasteful: every write invalidates Room's
     * `PagingSource`, so a pull that rewrites forty unchanged rows reloads the history list
     * under the health worker's thumb on every sync pass.
     */
    private fun acceptUnlessAlreadyCurrent(
        local: Beneficiary,
        remote: Beneficiary,
    ): ConflictResolution =
        if (local.hasSameContentAs(remote)) {
            ConflictResolution.KeepLocal
        } else {
            ConflictResolution.AcceptRemote(remote, replacingLocalVersion = local.updatedAt)
        }

    /**
     * Whether two versions carry the same information, ignoring sync bookkeeping.
     *
     * Written as "copy the other's bookkeeping onto mine and compare the whole record" rather
     * than as a hand-written list of field comparisons, and the difference is a correctness
     * one rather than a stylistic one. A field added to [Beneficiary] and forgotten in a
     * hand-written list would make two genuinely different records compare equal — and a
     * conflict that compares equal is a conflict that silently disappears. This form has the
     * opposite failure: a new field is compared automatically, and the worst case is a
     * conflict flagged that need not have been. One of those is recoverable by a person and
     * the other is not.
     *
     * The cost is one allocation per comparison, on a list bounded by what a pull returns.
     */
    private fun Beneficiary.hasSameContentAs(other: Beneficiary): Boolean =
        copy(updatedAt = other.updatedAt, syncStatus = other.syncStatus) == other
}

/**
 * A complete instruction, not a verdict.
 *
 * Each case carries everything the caller needs to act on it, including the version token the
 * write must be conditional on. The alternative — an enum the use case then interprets — puts
 * the caller in a position to pass the wrong `unchangedSince`, which is precisely the mistake
 * that produces the stale write Day 13 exists to prevent.
 */
sealed interface ConflictResolution {

    /**
     * Write the server's version locally, marked as the server sent it.
     *
     * [replacingLocalVersion] is the `updatedAt` of the row this was decided against, or null
     * when there is no local row. The write must apply **only** if the stored row still carries
     * it: between this decision and the write, the health worker can have edited the record,
     * and applying the server's version over a fresh local edit is the same data loss this
     * whole class exists to avoid. A refused write is a normal outcome — the next pull sees the
     * new local state and decides again, this time flagging a conflict.
     */
    data class AcceptRemote(
        val record: Beneficiary,
        val replacingLocalVersion: Timestamp?,
    ) : ConflictResolution

    /**
     * Mark the local row [SyncStatus.CONFLICTED] and change nothing else.
     *
     * Both versions survive: the local one in the database, the remote one on the server. The
     * app has stopped, deliberately, at the point where it would have to guess.
     */
    data class FlagConflict(
        val id: BeneficiaryId,
        val localUpdatedAt: Timestamp,
    ) : ConflictResolution

    /** Nothing to do. The local row is already current, or already flagged for a person. */
    data object KeepLocal : ConflictResolution
}
