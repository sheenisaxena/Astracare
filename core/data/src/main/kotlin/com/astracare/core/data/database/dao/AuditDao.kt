package com.astracare.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.astracare.core.data.database.entity.AuditEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the append-only trail.
 *
 * Two methods: put one in, read the recent ones out. There is no update and no delete, which
 * is the first of the three things that make "append-only" true rather than aspirational:
 *
 *  1. `AuditRepository` in the domain cannot express a change.
 *  2. This DAO cannot perform one.
 *  3. `AuditLogTriggers` makes SQLite refuse one, whatever code asks.
 *
 * Only the third survives someone adding a method here, which is why it exists. The first two
 * make the intent obvious to a reader; the third makes it a property of the database.
 */
@Dao
interface AuditDao {

    @Insert
    suspend fun insert(entry: AuditEntryEntity)

    /**
     * Newest first, capped.
     *
     * `ORDER BY id DESC`, not `at DESC`. The timestamp comes from the device clock and is
     * shown to the reader; the rowid is monotonic and is what actually establishes the order.
     * Sorting by a clock that can move backwards would let a reordered trail look authentic.
     *
     * `LIMIT` rather than paging: the trail is read occasionally, not scrolled, and a bounded
     * query costs one round trip on a table that is written to on every save.
     */
    @Query("SELECT * FROM audit_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEntryEntity>>
}
