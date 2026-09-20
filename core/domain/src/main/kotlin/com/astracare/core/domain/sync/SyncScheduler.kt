package com.astracare.core.domain.sync

/**
 * Asks for a sync pass to happen. Says nothing about when, or by what mechanism.
 *
 * ## Why an interface in the domain rather than calling WorkManager directly
 *
 * The caller that matters is `OfflineFirstBeneficiaryRepository`, which requests a sync right
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
 *
 * ## Named for the pass, not for the push
 *
 * These were `requestPush`/`ensurePeriodicPush` on Day 13, when a pass only sent. A pass now
 * pushes and then pulls, and the caller has no business knowing which halves run — that is
 * precisely the detail this interface exists to hide. Renaming rather than adding
 * `requestPull` alongside is deliberate: two entry points would let a caller ask for half a
 * sync, and the ordering of the halves is a correctness property, not a caller's choice. See
 * `SyncBeneficiariesWorker` for why push must come first.
 */
interface SyncScheduler {

    /**
     * Requests a sync pass as soon as there is a network.
     *
     * Called after every successful local write. Implementations must make this cheap and
     * idempotent — capturing ten records in a minute should not queue ten passes, because each
     * pass already sends everything pending.
     */
    fun requestSync()

    /**
     * Ensures the recurring safety net is scheduled.
     *
     * Called once at application start, and safe to call repeatedly. The one-time request
     * above covers the normal case; this covers the two that actually lose data — a push that
     * failed while the app was closed, on a handset whose owner does not capture another
     * record until tomorrow, and a server-side change that nothing on this device would
     * otherwise think to ask about.
     */
    fun ensurePeriodicSync()
}
