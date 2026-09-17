package com.astracare.core.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.astracare.core.data.database.entity.DraftEntity
import com.astracare.core.data.database.entity.SINGLE_ROW_ID

/**
 * Data access for the single in-progress capture.
 *
 * Every function is `suspend` and none returns a `Flow` — the deliberate opposite of
 * [BeneficiaryDao]. Records need observation because the sync worker changes them behind the
 * UI's back; the draft has exactly one writer, which is the screen currently reading it. A
 * `Flow` here would feed the capture screen its own autosaves, and any slip in filtering
 * those out shows up as the cursor jumping while someone types.
 */
@Dao
interface DraftDao {

    @Query("SELECT * FROM capture_draft WHERE id = :id")
    suspend fun load(id: Int = SINGLE_ROW_ID): DraftEntity?

    /**
     * `@Upsert` against a fixed primary key, so saving is idempotent and there is never a
     * second row to reconcile. `@Insert(REPLACE)` would work too and is worse for the same
     * reason it is worse on `beneficiaries`: it is DELETE followed by INSERT.
     */
    @Upsert
    suspend fun upsert(entity: DraftEntity)

    @Query("DELETE FROM capture_draft WHERE id = :id")
    suspend fun clear(id: Int = SINGLE_ROW_ID)
}
