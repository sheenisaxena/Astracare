package com.astracare.core.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.astracare.core.data.database.entity.BeneficiaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [BeneficiaryEntity].
 *
 * ## Why reads return Flow or PagingSource
 *
 * This is what makes the database the single source of truth rather than a cache. Room
 * observes the tables a query touches and re-emits whenever they change — including changes
 * made by the background sync worker, which the UI never calls and cannot know about. The same
 * applies to a [PagingSource]: Room invalidates it on write, and Paging reloads.
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
     * The history list, ordered and paged.
     *
     * ## Ordering lives here now, and that reverses a Day 9 decision
     *
     * This DAO used to carry a comment saying ordering was deliberately NOT done in SQL,
     * because sort order is a domain rule and encoding it here would split one decision across
     * two layers. That reasoning was sound and Paging invalidates its conclusion: pages are
     * loaded a few dozen rows at a time, so a Kotlin comparator can only sort the rows
     * currently in memory. The fifth conflicted record would sit below the thirtieth synced
     * one, because the two were never in the same list.
     *
     * What actually changed is where the rule is *executed*, not where it is *decided*. The
     * priority is still declared in `:core:domain` by `RecordAttentionOrder`, and its values
     * arrive here as bound parameters. Nothing in this file decides that CONFLICTED beats
     * FAILED; it only knows there are four ranks and where to read them from.
     *
     * The parameters are enum *names*, matching how `sync_status` is stored — see
     * [BeneficiaryEntity.syncStatus] for why the name and not the ordinal.
     *
     * `ELSE 0` puts anything unrecognised ahead of everything else. A status this query has
     * not been taught about is one the app cannot vouch for, and the safe failure is to show it
     * to the health worker rather than bury it beneath rows that are already safe. Adding a
     * `SyncStatus` therefore needs a new `WHEN` arm here; `RecordAttentionOrderTest` fails
     * until it exists.
     *
     * A `CASE` expression cannot use an index, so this is a scan plus a sort. Correct at the
     * scale of one health worker's records; the indexed-rank-column version is noted in
     * `RecordAttentionOrder` for when there is a benchmark to justify it.
     */
    @Query(
        """
        SELECT * FROM beneficiaries
        ORDER BY
            CASE sync_status
                WHEN :rank1 THEN 1
                WHEN :rank2 THEN 2
                WHEN :rank3 THEN 3
                WHEN :rank4 THEN 4
                ELSE 0
            END,
            recorded_at DESC
        """,
    )
    fun pagedByAttention(
        rank1: String,
        rank2: String,
        rank3: String,
        rank4: String,
    ): PagingSource<Int, BeneficiaryEntity>

    /**
     * Live count of records the server has not acknowledged.
     *
     * `COUNT(*)` in SQL rather than counting a loaded list, for the reason paging exists at
     * all: the list is not all there. This also uses `index_beneficiaries_sync_status`, which
     * was added on Day 9 for the sync engine and turns out to serve the UI too.
     */
    @Query("SELECT COUNT(*) FROM beneficiaries WHERE sync_status != :syncedStatus")
    fun observeAwaitingSyncCount(syncedStatus: String): Flow<Int>

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
