package com.astracare.di

import com.astracare.core.common.Outcome
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.domain.repository.RepositoryError
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Test double for [BeneficiaryRepository], fully under the test's control.
 *
 * A hand-written fake rather than a mock. The difference matters:
 *
 * - A **mock** is told what to return per call. Tests then assert on interactions
 *   ("`upsert` was called once"), which couples them to how the code is written rather than
 *   what it does. Refactor the implementation and the test breaks without the behaviour
 *   changing.
 * - A **fake** is a real, simple implementation. Tests assert on observable state ("after
 *   saving, the record appears in the stream"), so they survive refactoring and read like a
 *   description of the behaviour.
 *
 * MockK still has its place — a network layer with awkward error paths — but for something
 * with a small interface and simple state, a fake is better.
 *
 * [failNextWrite] exists so the storage-error path can be exercised. That branch is otherwise
 * unreachable in a test, and untested error handling is where offline-first apps lose data.
 */
@Singleton
class FakeBeneficiaryRepository @Inject constructor() : BeneficiaryRepository {

    private val records = MutableStateFlow<Map<BeneficiaryId, Beneficiary>>(emptyMap())

    /** Set to true to make the next [upsert] fail, then it resets. */
    var failNextWrite: Boolean = false

    override fun observeAll(): Flow<List<Beneficiary>> = records.map { it.values.toList() }

    override fun observeById(id: BeneficiaryId): Flow<Beneficiary?> = records.map { it[id] }

    override suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError> {
        if (failNextWrite) {
            failNextWrite = false
            return Outcome.Failure(RepositoryError.StorageFailure(IllegalStateException("test")))
        }
        records.update { it + (beneficiary.id to beneficiary) }
        return Outcome.success()
    }

    override suspend fun pendingSync(): List<Beneficiary> = records.value.values.toList()
}
