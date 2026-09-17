package com.astracare.core.data.repository

import android.database.SQLException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.astracare.core.common.Outcome
import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.data.database.dao.BeneficiaryDao
import com.astracare.core.data.database.mapper.toDomain
import com.astracare.core.data.database.mapper.toEntity
import com.astracare.core.domain.ordering.RecordAttentionOrder
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [BeneficiaryRepository] — the real implementation.
 *
 * Named "offline-first" because that is the behavioural contract, not a description of the
 * storage: **a write returns as soon as the local database has it.** It never waits on a
 * network call, and there is no network call in this class at all. A health worker in a village
 * with no signal gets the same confirmation, at the same speed, as one standing next to a
 * tower.
 *
 * The record is left for the sync engine to push later, which is why [Beneficiary.syncStatus]
 * exists and is surfaced in the UI: the worker can see what is safe on the server and what is
 * still only on the handset.
 *
 * Reads are pass-through from Room, mapped to domain models. There is no caching layer and no
 * in-memory copy of the data — Room *is* the cache. A second cache in front of it would be a
 * second source of truth, and the two would disagree.
 */
@Singleton
class OfflineFirstBeneficiaryRepository @Inject constructor(
    private val dao: BeneficiaryDao,
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : BeneficiaryRepository {

    /**
     * ## Where the Pager belongs
     *
     * Constructed here, in the data layer, rather than in the ViewModel — which is where most
     * examples put it. [PagingConfig] is a statement about the storage: how many rows a read
     * should fetch, how far ahead to prefetch, whether the source can report a total. A
     * ViewModel choosing those numbers is a ViewModel making decisions about a database it is
     * not supposed to know exists, and the moment there are two screens over the same table
     * they will choose differently.
     *
     * The `pagingSourceFactory` is a factory and not a value because Room invalidates a
     * `PagingSource` on every write and Paging then asks for a fresh one. Passing
     * `dao.pagedByAttention(...)` directly would hand it the same invalidated instance forever,
     * and the list would silently stop updating after the first save.
     *
     * The ordering values come from the domain, not from this class. See
     * [RecordAttentionOrder].
     */
    override fun pagedRecords(): Flow<PagingData<Beneficiary>> {
        val order = RecordAttentionOrder.byUrgency

        return Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                // Placeholders would let the list report a total row count and render blank
                // rows for data not yet loaded, which keeps the scrollbar honest. They also
                // require every row to have a known height before its content exists, and
                // these rows do not — a long village name wraps. Getting that wrong shows up
                // as the list jumping under the user's thumb while they scroll, which on a
                // screen used one-handed in a field is worse than a scrollbar that grows.
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                dao.pagedByAttention(
                    rank1 = order[FIRST].name,
                    rank2 = order[SECOND].name,
                    rank3 = order[THIRD].name,
                    rank4 = order[FOURTH].name,
                )
            },
        ).flow.map { page -> page.map { it.toDomain() } }
    }

    override fun observeAwaitingSyncCount(): Flow<Int> =
        dao.observeAwaitingSyncCount(SyncStatus.SYNCED.name)

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> =
        dao.observeById(id.value).map { it?.toDomain() }

    override suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError> =
        withContext(ioDispatcher) {
            // SQLException specifically, not Exception. A disk-full or corrupt-database error
            // is something the caller can report and retry; a NullPointerException in the
            // mapper is a bug that must surface loudly rather than be reported to a health
            // worker as "could not save".
            try {
                dao.upsert(beneficiary.toEntity())
                Outcome.success()
            } catch (e: SQLException) {
                Outcome.Failure(RepositoryError.StorageFailure(e))
            }
        }

    override suspend fun pendingSync(): List<Beneficiary> =
        withContext(ioDispatcher) {
            dao.pendingSync(SyncStatus.SYNCED.name).map { it.toDomain() }
        }

    private companion object {
        /**
         * Rows per page.
         *
         * Comfortably more than fills a phone screen, so the first page renders a full list
         * and the user has to scroll before a second query runs. Smaller would mean a visible
         * load during the first flick; much larger would defeat the point of paging at all.
         * A number to revisit with the benchmark work, not by guessing twice.
         */
        const val PAGE_SIZE = 30

        // Index names for RecordAttentionOrder.byUrgency, which the DAO's four CASE arms
        // consume positionally. Named rather than inline so the mapping between the domain's
        // list and the query's arms is legible at a glance.
        const val FIRST = 0
        const val SECOND = 1
        const val THIRD = 2
        const val FOURTH = 3
    }
}
