package com.astracare.core.domain.ordering

import com.astracare.core.model.SyncStatus

/**
 * The order records are shown in, as a domain rule rather than a query detail.
 *
 * Records needing attention come first, newest first within each group. A health worker's
 * most urgent question at the end of a day without signal is "what is still only on this
 * handset?", so the app answers it without them having to look.
 *
 * ## Why this exists as its own declaration
 *
 * Until Day 12 this rule lived as a `sortedWith` comparator inside
 * `ObserveBeneficiariesUseCase`, which sorted the whole list in Kotlin. Paging made that
 * impossible: pages are loaded a few dozen rows at a time, so a comparator applied to one page
 * would order that page and nothing else — the fifth conflicted record would sit below the
 * thirtieth synced one, because they were never in memory together.
 *
 * Ordering therefore has to move into SQL. The question is whether the *rule* moves with it,
 * and it does not. What lives here is the specification; `BeneficiaryDao.pagedByAttention`
 * binds these exact values into its `ORDER BY` and is only the execution of it. That split is
 * what keeps "CONFLICTED is more urgent than FAILED" reviewable as a product decision rather
 * than buried in a query string, and `RecordAttentionOrderTest` is what stops the two drifting.
 *
 * ## The cheaper implementation, and why not yet
 *
 * A `CASE` expression cannot use an index, so this sort is a scan. The production answer at
 * scale is an indexed `attention_rank INTEGER` column written at upsert time from [rankOf].
 * That is a schema version and a data backfill for a table holding at most a few thousand rows
 * on a single worker's handset — worth doing when there is a measurement to justify it, which
 * is what the benchmark day exists for. See DECISION_LOG 7.2.
 */
object RecordAttentionOrder {

    /**
     * Most urgent first.
     *
     * CONFLICTED and REJECTED come first because they need a human and will not resolve on
     * their own — a conflict needs someone to choose a version, a rejection needs someone to
     * fix the record. CONFLICTED edges ahead because it means real data exists on both sides.
     * FAILED and PENDING resolve themselves once there is signal. SYNCED needs nothing.
     *
     * Declared as an ordered list rather than numeric priorities, so the intent *is* the
     * declaration — no magic numbers, and reordering means moving a line.
     *
     * The size is load-bearing: `BeneficiaryDao.pagedByAttention` has one `WHEN` arm per entry.
     * Adding a [SyncStatus] means adding an arm there too, and [RecordAttentionOrderTest] fails
     * until both are done.
     */
    val byUrgency: List<SyncStatus> = listOf(
        SyncStatus.CONFLICTED,
        SyncStatus.REJECTED,
        SyncStatus.FAILED,
        SyncStatus.PENDING,
        SyncStatus.SYNCED,
    )

    /**
     * Position in [byUrgency]; lower sorts first.
     *
     * An unlisted status returns [MOST_URGENT] rather than throwing or sorting last. A status
     * this rule has not been taught about is one the app cannot vouch for, and the safe
     * failure is to put it in front of the health worker rather than to bury it under
     * everything that is already safe. The DAO's `ELSE` branch makes the same choice.
     */
    fun rankOf(status: SyncStatus): Int {
        val index = byUrgency.indexOf(status)
        return if (index < 0) MOST_URGENT else index + 1
    }

    /** Ahead of every declared status. Reserved for one this rule does not yet know. */
    const val MOST_URGENT = 0
}
