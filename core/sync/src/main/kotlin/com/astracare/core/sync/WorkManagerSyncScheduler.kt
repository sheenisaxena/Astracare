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
 * Schedules sync passes with WorkManager.
 *
 * The only class in the project that knows WorkManager exists. Everything upstream talks to
 * [SyncScheduler], which is why `OfflineFirstBeneficiaryRepository` can be unit tested without
 * a work harness.
 *
 * ## Two schedules, doing different jobs
 *
 * [requestSync] is the responsive one: a record was just saved, send it. [ensurePeriodicSync]
 * is the one that actually prevents data loss — a push that failed while the app was closed,
 * on a handset whose owner does not capture another record until tomorrow.
 *
 * Keeping only the first would lose records quietly. Keeping only the second would mean a
 * captured record sits PENDING for up to an hour with the app open and a full signal, which
 * teaches a health worker that sync does not work.
 *
 * The periodic pass carries a second job since Day 14. A push is triggered by a local save, so
 * without the periodic schedule a device that captures nothing all day would never *pull*
 * either, and a correction made on the server would take until the next capture to arrive.
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
    override fun requestSync() {
        workManager.enqueueUniqueWork(
            ONE_TIME_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncBeneficiariesWorker>()
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
    override fun ensurePeriodicSync() {
        cancelPreDay14Work()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncBeneficiariesWorker>(
                PERIOD_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(networkRequired)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * Removes the work this app enqueued before Day 14.
     *
     * WorkManager's queue lives in its own database and outlives the code that created it. The
     * periodic entry written by an earlier build names `PushBeneficiariesWorker`, a class that
     * no longer exists, so on upgrade it keeps firing and keeps failing with a
     * `ClassNotFoundException` that names a class nothing in the source tree mentions — a
     * genuinely confusing hour for whoever investigates it.
     *
     * A no-op on a fresh install, so it costs an upgraded device one database write. Safe to
     * delete once no installed build predates Day 14, which for this project means never in
     * practice and is exactly why it is worth a comment rather than silent deletion.
     */
    private fun cancelPreDay14Work() {
        LEGACY_WORK_NAMES.forEach(workManager::cancelUniqueWork)
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
        const val ONE_TIME_WORK_NAME = "com.astracare.sync.oneTime"
        const val PERIODIC_WORK_NAME = "com.astracare.sync.periodic"

        /** The Day 13 names, kept only so [cancelPreDay14Work] can remove them. */
        val LEGACY_WORK_NAMES = listOf(
            "com.astracare.sync.push.oneTime",
            "com.astracare.sync.push.periodic",
        )

        /**
         * Hourly, not WorkManager's 15-minute minimum.
         *
         * This is a backstop for a pass that already failed, not the primary path — the
         * save-triggered pass covers everything that happens while someone is using the app.
         * Waking a radio four times an hour to find nothing to send is a real battery cost on
         * the low-end handsets this targets.
         *
         * It does mean a server-side correction can take up to an hour to appear on an idle
         * handset. Acceptable here: the records are captured in the field and reviewed later,
         * not read live. A push notification is the production answer to that latency, and it
         * needs a real backend — see the scope boundary in DECISION_LOG 4.2.
         */
        const val PERIOD_MINUTES = 60L

        /** WorkManager's own minimum backoff; five attempts span roughly eight minutes. */
        const val BACKOFF_SECONDS = 30L
    }
}
