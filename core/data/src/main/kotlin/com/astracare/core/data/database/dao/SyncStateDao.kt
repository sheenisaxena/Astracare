package com.astracare.core.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.astracare.core.data.database.entity.SYNC_STATE_ROW_ID
import com.astracare.core.data.database.entity.SyncStateEntity

/**
 * Data access for the single sync-state row.
 *
 * No `Flow` anywhere, for the same reason as [DraftDao]: the cursor has exactly one reader and
 * one writer, and they are the same sync pass. Nothing observes it, so nothing should be able
 * to.
 */
@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = :id")
    suspend fun load(id: Int = SYNC_STATE_ROW_ID): SyncStateEntity?

    /** `@Upsert` against a fixed key, so there is never a second row to reconcile. */
    @Upsert
    suspend fun upsert(entity: SyncStateEntity)
}
