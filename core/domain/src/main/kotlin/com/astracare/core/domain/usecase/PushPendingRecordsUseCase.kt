package com.astracare.core.domain.usecase

import com.astracare.core.domain.remote.PushOutcome
import com.astracare.core.domain.remote.RemoteBeneficiarySource
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.SyncStatus
import javax.inject.Inject

/**
 * Sends every record the server has not accepted yet.
 *
 * ## Why the algorithm is here and not in the Worker
 *
 * This is the code that can lose a health worker's field data, so it is the code that most
 * needs tests — and a `CoroutineWorker` cannot be unit tested without a WorkManager harness,
 * instrumentation or Robolectric. Written here it is a plain class with two injected
 * interfaces, and `PushPendingRecordsUseCaseTest` drives every branch in milliseconds with no
 * Android at all.
 *
 * `SyncBeneficiariesWorker` is then about ten lines: call this and its pull counterpart, map
 * the summaries to a `Result`. WorkManager is scheduling infrastructure, and infrastructure
 * should not own the decisions.
 *
 * ## The stale-write hazard
 *
 * The dangerous moment in any push loop is between reading a record and marking it sent. A
 * health worker can edit a record in that window — the app is running, the form is open, and
 * the push is happening on a background thread. Marking the row SYNCED afterwards would
 * declare the *new* version sent when only the old one was, and the edit would never reach
 * the server. Nothing would look wrong: the record shows as synced, and the change is simply
 * gone.
 *
 * So the mark is conditional on the record being untouched — see
 * [BeneficiaryRepository.updateSyncStatus], which matches on `updatedAt` and reports whether
 * it actually changed a row. If the record moved, it stays pending and goes out on the next
 * pass. The check costs nothing and the bug it prevents is invisible.
 */
class PushPendingRecordsUseCase @Inject constructor(
    private val repository: BeneficiaryRepository,
    private val remote: RemoteBeneficiarySource,
) {

    suspend operator fun invoke(): PushSummary {
        var accepted = 0
        var rejected = 0

        for (record in repository.pendingSync()) {
            when (val outcome = remote.push(record)) {
                PushOutcome.Accepted -> if (markUnlessChanged(record, SyncStatus.SYNCED)) {
                    accepted++
                }

                is PushOutcome.Rejected -> if (markUnlessChanged(record, SyncStatus.REJECTED)) {
                    rejected++
                }

                // Stop the whole pass rather than trying the next record. A transient failure
                // is almost always the connection, not the record, so the remaining forty
                // attempts would fail the same way — burning battery and radio on a handset
                // that has neither to spare. Everything already accepted stays marked; the
                // rest is still pending and goes out on the retry.
                is PushOutcome.TransientFailure -> return PushSummary.Interrupted(
                    accepted = accepted,
                    rejected = rejected,
                    cause = outcome.cause,
                )
            }
        }

        return PushSummary.Complete(accepted = accepted, rejected = rejected)
    }

    /**
     * Applies [status] only if the record has not been edited since it was read.
     *
     * Returns false when the row moved underneath the push, which is not an error — it means
     * a newer version exists locally and belongs in the next pass.
     */
    private suspend fun markUnlessChanged(record: Beneficiary, status: SyncStatus): Boolean =
        repository.updateSyncStatus(
            id = record.id,
            unchangedSince = record.updatedAt,
            status = status,
        )
}

/**
 * What one pass achieved.
 *
 * The distinction drives whether WorkManager retries, so it is a type rather than a boolean:
 * "did everything get through" and "did anything go wrong" are different questions, and a
 * pass that rejected four records while reaching the server successfully is a *success* — the
 * work is done, retrying would change nothing, and the records now need a person.
 */
sealed interface PushSummary {

    val accepted: Int
    val rejected: Int

    /** Every pending record was offered to the server and answered for. Do not retry. */
    data class Complete(
        override val accepted: Int,
        override val rejected: Int,
    ) : PushSummary

    /** The pass stopped early. Whatever is left is still pending; retry with backoff. */
    data class Interrupted(
        override val accepted: Int,
        override val rejected: Int,
        val cause: Throwable?,
    ) : PushSummary
}
