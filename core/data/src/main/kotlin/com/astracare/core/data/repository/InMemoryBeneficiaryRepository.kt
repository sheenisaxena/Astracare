package com.astracare.core.data.repository

import com.astracare.core.common.Outcome
import com.astracare.core.common.di.AstraCareDispatcher
import com.astracare.core.common.di.Dispatcher
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import com.astracare.core.model.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TEMPORARY in-memory implementation of [BeneficiaryRepository].
 *
 * ## Why this exists
 *
 * Hilt validates the dependency graph at **compile time**, so a graph with no binding for
 * [BeneficiaryRepository] does not build. Wiring DI before persistence exists therefore
 * requires something to bind to.
 *
 * It is not wasted work. It lets the UI be built and interacted with before Room lands, and
 * it is a useful reference for the test fake. It is replaced by a Room-backed
 * `OfflineFirstBeneficiaryRepository` — at which point only the `@Binds` in
 * `DataModule` changes, and nothing in the domain or UI layers is touched. That is the whole
 * argument for depending on the interface rather than the implementation.
 *
 * ## What it deliberately does NOT do
 *
 * Data is lost on process death, which makes it unusable for real offline-first behaviour —
 * the entire point being that records survive. Do not mistake this for a working repository.
 *
 * `MutableStateFlow` gives the same observable-read semantics Room's `Flow` DAO will, so
 * consumers written against this keep working unchanged after the swap.
 */
@Singleton
class InMemoryBeneficiaryRepository @Inject constructor(
    @Dispatcher(AstraCareDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : BeneficiaryRepository {

    private val records = MutableStateFlow<Map<BeneficiaryId, Beneficiary>>(emptyMap())

    override fun observeAll(): Flow<List<Beneficiary>> =
        records.map { it.values.toList() }

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> =
        records.map { it[id] }

    override suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError> =
        withContext(ioDispatcher) {
            // The injected dispatcher is pointless for a map write, but it is used here on
            // purpose: it keeps the threading contract identical to the Room implementation,
            // so the swap changes no call-site behaviour.
            records.update { current -> current + (beneficiary.id to beneficiary) }
            Outcome.success()
        }

    override suspend fun pendingSync(): List<Beneficiary> =
        withContext(ioDispatcher) {
            records.value.values.filter { it.syncStatus != SyncStatus.SYNCED }
        }
}
