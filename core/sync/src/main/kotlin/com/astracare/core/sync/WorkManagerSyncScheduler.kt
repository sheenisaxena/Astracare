package com.astracare.core.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.astracare.core.domain.sync.SyncScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules push passes with WorkManager.
 *
 * The only class in the project that knows WorkManager exists. Everything upstream talks to
 * [SyncScheduler], which is why `OfflineFirstBeneficiaryRepository` can be unit tested without
 * a work harness.
 *
 * ## Two schedules, doing different jobs
 *
 * [requestPush] is the responsive one: a record was just saved, send it. [ensurePeriodicPush]
 * is the one that actually prevents data loss — a push that failed while the app was closed,
 * on a handset whose owner does not capture another record until tomorrow.
 *
 * Keeping only the first would lose records quietly. Keeping only the second would mean a
 * captured record sits PENDING for up to fifteen minutes with the app open and a full signal,
 * which teaches a health worker that sync does not work.
 */
@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    private val workManager: WorkManager,
) : SyncScheduler {

    /**
     * `ExistingWorkPolicy.KEEP`, not `REPLACE`.
     *
     * Capturing five records in a minute calls this five times. KEEP means the pass already
     * queued just runs, and since every pass sends *everything* pending, the later records are
     * included anyway. REPLACE would cancel and requeue on each save — so a worker that had
     * been retrying with backoff for two minutes would be thrown away and restarted at the
     * beginning of the backoff curve, which is the opposite of what backoff is for.
     */
    override fun requestPush() {
        workManager.enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PushBeneficiariesWorker>()
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * `ExistingPeriodicWorkPolicy.KEEP`, so calling this on every app start does not reset the
     * interval. `UPDATE` would mean a user who opens the app often never reaches the end of a
     * period, and the safety net would never fire.
     */
    override fun ensurePeriodicPush() {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PushBeneficiariesWorker>(
                PERIOD_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * `CONNECTED`, not `UNMETERED`.
     *
     * A beneficiary record is a few hundred bytes. Waiting for wifi to send it would mean a
     * health worker walking a village on mobile data has nothing sync all day — and wifi is
     * not a thing most of them will see before they get home. Metered data is the normal case
     * here, not the exception, and the payload is small enough that it does not matter.
     */
    private val networkRequired: Constraints
        get() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    private companion object {
        /**
         * Unique names, so the two schedules never collide and neither is ever queued twice.
         * Fully qualified because a name collision with another component's work is silent —
         * one of them simply stops running.
         */
        const val ONE_TIME_WORK_NAME = "com.astracare.sync.push.oneTime"
        const val PERIODIC_WORK_NAME = "com.astracare.sync.push.periodic"

        /**
         * Hourly, not WorkManager's 15-minute minimum.
         *
         * This is a backstop for a push that already failed, not the primary path — the
         * save-triggered pass covers everything that happens while someone is using the app.
         * Waking a radio four times an hour to find nothing to send is a real battery cost on
         * the low-end handsets this targets.
         */
        const val PERIOD_MINUTES = 60L

        /** WorkManager's own minimum backoff; five attempts span roughly eight minutes. */
        const val BACKOFF_SECONDS = 30L
    }
}
