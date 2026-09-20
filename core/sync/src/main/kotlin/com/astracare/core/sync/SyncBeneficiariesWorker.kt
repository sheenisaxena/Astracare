package com.astracare.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.astracare.core.common.log.Logger
import com.astracare.core.domain.repository.LocalStoreUnavailableException
import com.astracare.core.domain.usecase.PullRemoteChangesUseCase
import com.astracare.core.domain.usecase.PullSummary
import com.astracare.core.domain.usecase.PushPendingRecordsUseCase
import com.astracare.core.domain.usecase.PushSummary
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one sync pass: push, then pull.
 *
 * Deliberately almost empty. Everything that decides anything — what to send, what to give up
 * on, when it is unsafe to mark a record as sent, whether a server version may overwrite a
 * local one — is in `PushPendingRecordsUseCase`, `PullRemoteChangesUseCase` and
 * `ConflictResolver`, where it can be tested with plain JUnit. What is left here is the part
 * that genuinely belongs to WorkManager: ordering the two halves and turning results into a
 * `Result`.
 *
 * That split is the point. A `CoroutineWorker` cannot be constructed without a `Context` and
 * `WorkerParameters`, so testing logic written inside one means instrumentation or Robolectric.
 * For the code that can lose a health worker's records, that is the wrong place for the tests
 * to live.
 *
 * ## Push first, then pull
 *
 * Not the other way round, and not in parallel. Pushing first means a record this device has
 * been holding is on the server *before* the pull asks what the server has — so the pull sees
 * it as accepted rather than as a competing version, and the record resolves silently instead
 * of appearing as a conflict the health worker has to look at. Conflicts should be rare enough
 * that one means something; an ordering that manufactures them makes the red chip noise.
 *
 * ## Why an interrupted push skips the pull
 *
 * `PushSummary.Interrupted` means the connection dropped or the server is unreachable — that
 * is what makes a failure transient rather than a rejection. The pull would almost certainly
 * fail the same way, and this is a handset whose battery has to last a working day. It is the
 * same reasoning that stops the push loop itself rather than trying the next forty records.
 *
 * The cost is honest: on the rare failure that is specific to one record rather than to the
 * connection, the pull is delayed until the retry. Bounded, and cheaper than the alternative.
 *
 * ## Why the use cases arrive as `Lazy`
 *
 * Since Day 16 the database is encrypted with a key that cannot be used while the device is
 * locked, so *opening* it is an operation that can legitimately fail. Resolving either use case
 * pulls the repository, which pulls the DAO, which opens the database — and if that happened in
 * this class's constructor, the failure would land in WorkManager's worker factory as
 * "Could not instantiate SyncBeneficiariesWorker". That reads like a Hilt wiring bug, gives no
 * hint that the phone was simply locked, and cannot be turned into a retry because [doWork] was
 * never reached.
 *
 * [Lazy] moves that resolution inside [doWork], where it is a catchable
 * [LocalStoreUnavailableException] and a correct `Result.retry()`. The general rule is worth
 * stating: **a worker whose dependencies can fail for transient, expected reasons must not
 * resolve them in its constructor.** See DECISION_LOG 10.4.
 */
@HiltWorker
class SyncBeneficiariesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pushPendingRecords: Lazy<PushPendingRecordsUseCase>,
    private val pullRemoteChanges: Lazy<PullRemoteChangesUseCase>,
    private val logger: Logger,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        syncOnce()
    } catch (e: LocalStoreUnavailableException) {
        // Almost always: WorkManager started this pass on a locked handset, in a process that
        // was not already running, so the Keystore will not release the database passphrase.
        // Nothing is wrong — the data is meant to be unreadable then. Retry when it is not.
        logger.info(TAG, "Local store unavailable; deferring this pass", e)
        retryOrGiveUp()
    }

    private suspend fun syncOnce(): Result {
        val push = pushPendingRecords.get()()
        logger.debug(TAG, "Push finished: $push (attempt ${runAttemptCount + 1})")

        if (push is PushSummary.Interrupted) {
            return retryOrGiveUp()
        }

        val pull = pullRemoteChanges.get()()
        logger.debug(TAG, "Pull finished: $pull")

        return when (pull) {
            // Everything pending was offered and answered for, and everything the server had
            // has been resolved. Rejections and conflicts are both part of a *successful*
            // pass: the server replied, the records are marked, and running again would
            // produce the same answer. Reporting failure here would have WorkManager retry a
            // pass that has nothing left to do — and a conflict is not something a retry can
            // fix, because the thing it is waiting for is a person.
            is PullSummary.Complete -> Result.success()

            is PullSummary.Interrupted -> retryOrGiveUp()
        }
    }

    /**
     * Backs off, up to a point.
     *
     * WorkManager retries a `Result.retry()` indefinitely with the configured backoff, which
     * for a device that is simply out of coverage means waking the radio all night. Capping
     * the one-time pass and letting the periodic one take over bounds that: the record is not
     * abandoned, it is just no longer the subject of an escalating retry loop.
     *
     * `Result.failure()` ends this work request only. Nothing is lost — the records are still
     * PENDING in the database and the pull cursor has not moved, which together are the single
     * source of truth about what still has to happen.
     */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            logger.warn(TAG, "Giving up after $MAX_ATTEMPTS attempts; periodic sync will retry")
            Result.failure()
        } else {
            Result.retry()
        }

    companion object {
        internal const val TAG = "SyncBeneficiaries"

        /**
         * With the 30-second exponential backoff configured in `WorkManagerSyncScheduler`,
         * five attempts span roughly eight minutes. Long enough to ride out a tunnel or a
         * flaky cell; short enough not to keep the radio busy on a handset that has to last a
         * working day.
         */
        internal const val MAX_ATTEMPTS = 5
    }
}
