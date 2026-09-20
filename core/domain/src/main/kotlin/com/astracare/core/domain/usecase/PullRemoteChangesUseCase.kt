package com.astracare.core.domain.usecase

import com.astracare.core.domain.remote.PullOutcome
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.SyncStateRepository
import com.astracare.core.domain.sync.ConflictResolution
import com.astracare.core.domain.sync.ConflictResolver
import com.astracare.core.model.SyncStatus
import javax.inject.Inject

/**
 * Brings down everything that changed on the server and reconciles it with what is here.
 *
 * The counterpart to [PushPendingRecordsUseCase], and deliberately its mirror image: a plain
 * class with injected interfaces, no Android, every branch reachable from a JUnit test. The
 * reasoning is the same — this is code that can destroy a health worker's data, so it must not
 * live anywhere that needs instrumentation to exercise.
 *
 * ## The decision is not made here
 *
 * This class runs a loop. It does not decide anything: [ConflictResolver] does, as a pure
 * function over one local record and one remote one. That split is what lets the interesting
 * cases be tested as a truth table rather than by arranging a fake server into each state, and
 * it is what keeps the rule readable as a rule.
 *
 * ## Two guards, both about writes that are no longer valid
 *
 * **Every write is conditional.** Between resolving a record and writing the result, the health
 * worker can edit it — the app is open, the pull is on a background thread. A refused write is
 * a normal outcome, not an error: the local row moved, so the resolution was decided against a
 * version that no longer exists, and the next pull decides again with the new one. This is the
 * same hazard as Day 13's stale write, arriving from the other direction, and it is defended
 * the same way.
 *
 * **The cursor advances once, at the end.** Not per record. A crash halfway through a pass
 * leaves the cursor where it was, so the next pull re-delivers records that were already
 * applied — which is free, because resolution is idempotent: re-applying a record that is
 * already there yields `KeepLocal`. The opposite ordering has no such safety. A cursor advanced
 * per record, on a pass that dies at record five, means records six through forty are behind
 * the window and the server never offers them again.
 */
class PullRemoteChangesUseCase @Inject constructor(
    private val repository: BeneficiaryRepository,
    private val syncState: SyncStateRepository,
    private val remote: RemoteBeneficiarySource,
) {

    suspend operator fun invoke(): PullSummary {
        val outcome = remote.pullChangedSince(syncState.lastPullCursor())

        return when (outcome) {
            is PullOutcome.TransientFailure -> PullSummary.Interrupted(outcome.cause)

            is PullOutcome.Changes -> {
                val counts = outcome.records.fold(Counts()) { counts, record ->
                    counts.plus(apply(ConflictResolver.resolve(repository.findById(record.id), record)))
                }
                syncState.recordPullCursor(outcome.nextCursor)
                PullSummary.Complete(applied = counts.applied, conflicted = counts.conflicted)
            }
        }
    }

    /**
     * Carries out one resolution, and reports whether it actually changed the row.
     *
     * False from either write means the record was edited underneath this pass. Not counted,
     * because nothing was durably done for it — the same accounting rule the push loop uses.
     */
    private suspend fun apply(resolution: ConflictResolution): Change = when (resolution) {
        is ConflictResolution.AcceptRemote -> Change(
            applied = repository.applyRemote(
                record = resolution.record,
                replacingLocalVersion = resolution.replacingLocalVersion,
            ),
        )

        is ConflictResolution.FlagConflict -> Change(
            conflicted = repository.updateSyncStatus(
                id = resolution.id,
                unchangedSince = resolution.localUpdatedAt,
                status = SyncStatus.CONFLICTED,
            ),
        )

        ConflictResolution.KeepLocal -> Change()
    }

    /**
     * One record's contribution to the totals.
     *
     * Named `Change` rather than the more obvious `Outcome` so it cannot be confused with
     * `core.common.Outcome`, which is the project's result type and means something else.
     */
    private data class Change(val applied: Boolean = false, val conflicted: Boolean = false)

    /** Running totals, folded over the pulled records so nothing in the loop is mutable. */
    private data class Counts(val applied: Int = 0, val conflicted: Int = 0) {
        fun plus(change: Change) = Counts(
            applied = applied + if (change.applied) 1 else 0,
            conflicted = conflicted + if (change.conflicted) 1 else 0,
        )
    }
}

/**
 * What one pull achieved.
 *
 * Mirrors `PushSummary`, minus its `rejected` case — a pull submits nothing, so there is
 * nothing for the server to refuse — and minus partial counts on the failure path. A pull
 * either gets an answer or does not; there is no half-answer to account for.
 *
 * [Complete.conflicted] is the number the project's whole design is about. It should almost
 * always be zero, and when it is not, someone has to look.
 */
sealed interface PullSummary {

    /** The server answered and every record it sent has been resolved. Do not retry. */
    data class Complete(val applied: Int, val conflicted: Int) : PullSummary

    /** The server could not be reached. The cursor is unchanged; retry with backoff. */
    data class Interrupted(val cause: Throwable?) : PullSummary
}
