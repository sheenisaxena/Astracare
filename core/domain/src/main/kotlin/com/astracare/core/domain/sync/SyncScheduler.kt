package com.astracare.core.domain.sync

/**
 * Asks for a sync pass to happen. Says nothing about when, or by what mechanism.
 *
 * ## Why an interface in the domain rather than calling WorkManager directly
 *
 * The caller that matters is `OfflineFirstBeneficiaryRepository`, which requests a push right
 * after a successful local write. Without this seam that repository would import
 * `androidx.work` and every one of its unit tests would need a WorkManager test harness to
 * construct it.
 *
 * It also keeps the decision in the right place. *That* a save should be followed by a sync
 * attempt is a product rule. *How* — one-time unique work, network-constrained, exponentially
 * backed off — is a platform detail, and it lives in `:core:sync` where the platform is.
 *
 * Neither method returns anything and neither suspends, deliberately. Enqueuing is
 * fire-and-forget: the caller has already done the thing that matters, which is getting the
 * record onto the disk. If the scheduler fails, the periodic pass picks the record up. A
 * repository that waited on sync, or reported its failure, would have quietly stopped being
 * offline-first.
 */
interface SyncScheduler {

    /**
     * Requests a push as soon as there is a network.
     *
     * Called after every successful local write. Implementations must make this cheap and
     * idempotent — capturing ten records in a minute should not queue ten passes, because each
     * pass already sends everything pending.
     */
    fun requestPush()

    /**
     * Ensures the recurring safety net is scheduled.
     *
     * Called once at application start, and safe to call repeatedly. The one-time request
     * above covers the normal case; this covers the one that actually loses data — a push that
     * failed while the app was closed, on a handset whose owner does not capture another
     * record until tomorrow.
     */
    fun ensurePeriodicPush()
}
