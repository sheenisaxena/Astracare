package com.astracare.core.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.astracare.core.data.database.entity.BeneficiaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [BeneficiaryEntity].
 *
 * ## Why reads return Flow
 *
 * This is what makes the database the single source of truth rather than a cache. Room
 * observes the tables a query touches and re-emits whenever they change — including changes
 * made by the background sync worker, which the UI never calls and cannot know about.
 *
 * With a one-shot `suspend fun getAll()`, the screen would show data that is stale the moment
 * sync completes, and would need manual refresh plumbing to compensate. That plumbing is where
 * offline-first apps develop "pull to refresh because it's sometimes wrong" behaviour.
 *
 * ## Why writes are suspend
 *
 * A write completes: one result, nothing to observe afterwards. The consequence arrives
 * through the read Flow.
 */
@Dao
interface BeneficiaryDao {

    /**
     * Ordering is deliberately NOT done here. Sort order is a domain rule (see
     * `ObserveBeneficiariesUseCase`), and encoding it in SQL would split the same decision
     * across two layers — where the two would eventually disagree.
     */
    @Query("SELECT * FROM beneficiaries")
    fun observeAll(): Flow<List<BeneficiaryEntity>>

    @Query("SELECT * FROM beneficiaries WHERE id = :id")
    fun observeById(id: String): Flow<BeneficiaryEntity?>

    /**
     * `@Upsert` rather than `@Insert(onConflict = REPLACE)`.
     *
     * REPLACE is implemented as DELETE followed by INSERT, which triggers `ON DELETE CASCADE`
     * on any child rows and resets autoincrement counters. Harmless with one table today;
     * a genuine data-loss bug the moment a related table exists. `@Upsert` does a real
     * INSERT-or-UPDATE.
     */
    @Upsert
    suspend fun upsert(entity: BeneficiaryEntity)

    /**
     * Records the sync engine still needs to push.
     *
     * The status is passed in rather than written into the SQL string so the comparison
     * cannot drift from the enum definition.
     */
    @Query("SELECT * FROM beneficiaries WHERE sync_status != :syncedStatus")
    suspend fun pendingSync(syncedStatus: String): List<BeneficiaryEntity>

    @Query("DELETE FROM beneficiaries")
    suspend fun deleteAll()
}
