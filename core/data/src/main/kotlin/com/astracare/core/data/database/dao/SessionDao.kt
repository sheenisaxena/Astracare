package com.astracare.core.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.astracare.core.data.database.entity.SESSION_ROW_ID
import com.astracare.core.data.database.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for the single session row.
 *
 * [observe] returns a `Flow` — the opposite of [DraftDao] and [SyncStateDao], and for a reason
 * rather than by accident. The role is read by the UI, which must re-gate itself the instant
 * it changes, and the writer is a different screen from the readers. That is precisely the
 * case a Flow is for.
 */
@Dao
interface SessionDao {

    @Query("SELECT * FROM session WHERE id = :id")
    fun observe(id: Int = SESSION_ROW_ID): Flow<SessionEntity?>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun load(id: Int = SESSION_ROW_ID): SessionEntity?

    @Upsert
    suspend fun upsert(entity: SessionEntity)
}
