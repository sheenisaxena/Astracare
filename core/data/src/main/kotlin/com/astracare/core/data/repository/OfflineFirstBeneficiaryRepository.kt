package com.astracare.core.data.repository

import android.database.SQLException
import com.astracare.core.common.Outcome
import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.data.database.dao.BeneficiaryDao
import com.astracare.core.data.database.mapper.toDomain
import com.astracare.core.data.database.mapper.toEntity
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
 * Reads are pass-through Flows from Room, mapped to domain models. There is no caching layer
 * and no in-memory copy of the data — Room *is* the cache. A second cache in front of it would
 * be a second source of truth, and the two would disagree.
 */
@Singleton
class OfflineFirstBeneficiaryRepository @Inject constructor(
    private val dao: BeneficiaryDao,
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : BeneficiaryRepository {

    override fun observeAll(): Flow<List<Beneficiary>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

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
}
