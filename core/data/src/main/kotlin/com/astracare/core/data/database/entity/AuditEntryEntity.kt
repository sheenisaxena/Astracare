package com.astracare.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of the append-only trail.
 *
 * ## No foreign key to `beneficiaries`, on purpose
 *
 * [recordId] names a beneficiary and is deliberately not declared as a foreign key. A trail
 * that cascades away when the record it describes is deleted is not a trail — "record X was
 * deleted" is precisely the entry that must survive the deletion. `ON DELETE SET NULL` would
 * keep the row and destroy the only thing that made it meaningful.
 *
 * The cost is that [recordId] can name a record that no longer exists, which is correct: the
 * history of a thing outlives the thing.
 *
 * ## autoGenerate, for ordering that does not depend on a clock
 *
 * The primary key is a monotonic rowid rather than a UUID, because it is the only ordering in
 * this app that a device clock cannot corrupt. [atEpochMillis] is what a reader is shown;
 * `id DESC` is what the query actually sorts by. Two entries written in the same millisecond,
 * or written either side of someone changing the system clock, still come back in the order
 * they happened.
 */
@Entity(
    tableName = "audit_log",
    indices = [
        // The screen reads "newest first, capped". Without this that is a full scan and a
        // sort of a table that only ever grows.
        Index(value = ["at"]),
        // Answering "what happened to this record?" is the second question anyone asks of a
        // trail, and it is one query away once the index exists.
        Index(value = ["record_id"]),
    ],
)
data class AuditEntryEntity(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = UNASSIGNED_ID,

    /**
     * The enum NAME, as everywhere else in this schema — see [BeneficiaryEntity.syncStatus]
     * for why an ordinal is a data-corruption bug waiting for someone to reorder an enum.
     */
    @ColumnInfo(name = "actor")
    val actor: String,

    @ColumnInfo(name = "action")
    val action: String,

    /** Null for events about the device rather than a record, such as a role change. */
    @ColumnInfo(name = "record_id")
    val recordId: String?,

    @ColumnInfo(name = "at")
    val atEpochMillis: Long,
) {
    companion object {
        /**
         * Room assigns the real id on insert. Zero is the documented signal for "you choose"
         * with `autoGenerate`, and it never reaches the domain model: `AuditEntry` is only ever
         * constructed from a row that has been written.
         */
        const val UNASSIGNED_ID = 0L
    }
}
