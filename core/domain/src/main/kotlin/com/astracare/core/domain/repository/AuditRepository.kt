package com.astracare.core.domain.repository

import com.astracare.core.model.AuditAction
import com.astracare.core.model.AuditEntry
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.Timestamp
import com.astracare.core.model.UserRole
import kotlinx.coroutines.flow.Flow

/**
 * The append-only trail.
 *
 * ## The interface is the first half of "append-only"
 *
 * There is no update, no delete, and no way to ask for one. That is a real constraint rather
 * than a naming convention: code that cannot express "change this entry" cannot do it by
 * accident, and a reviewer can confirm the property by reading nine lines instead of auditing
 * every call site.
 *
 * It is only the first half, because an interface binds the code that goes through it and
 * nothing else. The second half is a pair of SQLite triggers that make `UPDATE` and `DELETE`
 * on the table raise — see `AuditLogTriggers` in `:core:data`, and DECISION_LOG 11.3 for why
 * a DAO with no update method was not considered sufficient.
 *
 * ## Why append takes fields rather than an AuditEntry
 *
 * An [AuditEntry] carries an `id` that only the store can assign, so a caller constructing one
 * would have to invent a value for it — and a sentinel meaning "not saved yet" is exactly the
 * kind of thing that ends up persisted. Taking the fields keeps the model honest: every
 * [AuditEntry] that exists has been written down.
 */
interface AuditRepository {

    /**
     * Records one event. [at] is passed in rather than read from a clock here, so the caller's
     * injected `TimeProvider` remains the single source of time and the write stays testable.
     *
     * [recordId] is null for events that concern the device rather than a record.
     */
    suspend fun append(
        actor: UserRole,
        action: AuditAction,
        recordId: BeneficiaryId?,
        at: Timestamp,
    )

    /**
     * The most recent entries, newest first, capped at [limit].
     *
     * Capped rather than paged, deliberately. This screen is read occasionally by a supervisor
     * looking at what just happened, not scrolled through — and a bounded query is one round
     * trip and no `PagingSource` invalidation on a table that is written to on every save. If
     * the trail ever needs to be searched or exported, that is a different feature with
     * different requirements, and it should be built as one.
     */
    fun observeRecent(limit: Int): Flow<List<AuditEntry>>
}
