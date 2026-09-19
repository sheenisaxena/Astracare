package com.astracare.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.astracare.core.domain.usecase.PushPendingRecordsUseCase
import com.astracare.core.domain.usecase.PushSummary
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs one push pass.
 *
 * Deliberately almost empty. Everything that decides anything — what to send, what to give up
 * on, when it is unsafe to mark a record as sent — is in `PushPendingRecordsUseCase`, where it
 * can be tested with plain JUnit. What is left here is the part that genuinely belongs to
 * WorkManager: turning a result into a `Result`.
 *
 * That split is the point. A `CoroutineWorker` cannot be constructed without a `Context` and
 * `WorkerParameters`, so testing logic written inside one means instrumentation or Robolectric.
 * For the code that can lose a health worker's records, that is the wrong place for the tests
 * to live.
 *
 * ## @HiltWorker and why it needs two artifacts
 *
 * `@AssistedInject` because WorkManager supplies the first two parameters and Hilt supplies the
 * rest. `@HiltWorker` generates the factory entry that makes that possible — but only if
 * `androidx.hilt:hilt-compiler` is on KSP as well as `hilt-work` on the compile path. With just
 * the annotation the build succeeds and the app fails at runtime with "Could not instantiate
 * worker", which reads like a WorkManager fault rather than a missing processor.
 *
 * It also requires the app to provide `HiltWorkerFactory` via `Configuration.Provider`, and the
 * default `WorkManagerInitializer` to be removed from the manifest. See `AstraCareApplication`.
 */
@HiltWorker
class PushBeneficiariesWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pushPendingRecords: PushPendingRecordsUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val summary = pushPendingRecords()
        Log.d(TAG, "Push pass finished: $summary (attempt ${runAttemptCount + 1})")

        return when (summary) {
            // Everything pending was offered and answered for. Rejections are part of a
            // successful pass: the server replied, the records are marked, and running again
            // would produce the same answer. Reporting failure here would have WorkManager
            // retry a pass that has nothing left to do.
            is PushSummary.Complete -> Result.success()

            is PushSummary.Interrupted -> retryOrGiveUp()
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
     * PENDING in the database, which is the single source of truth about what needs sending.
     */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            Log.w(TAG, "Giving up after $MAX_ATTEMPTS attempts; periodic sync will retry")
            Result.failure()
        } else {
            Result.retry()
        }

    companion object {
        internal const val TAG = "PushBeneficiaries"

        /**
         * With the 30-second exponential backoff configured in `WorkManagerSyncScheduler`,
         * five attempts span roughly eight minutes. Long enough to ride out a tunnel or a
         * flaky cell; short enough not to keep the radio busy on a handset that has to last a
         * working day.
         */
        internal const val MAX_ATTEMPTS = 5
    }
}
