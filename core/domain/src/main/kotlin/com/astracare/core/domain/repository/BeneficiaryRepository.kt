package com.astracare.core.domain.repository

import com.astracare.core.common.Outcome
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.BeneficiaryId
import kotlinx.coroutines.flow.Flow

/**
 * Access to beneficiary records.
 *
 * The INTERFACE lives in `:core:domain`; the implementation lives in `:core:data`. This is
 * the dependency inversion that makes the layering real — `:core:domain` has no idea Room
 * exists, so the persistence choice can change without the domain or UI noticing.
 *
 * It is also what makes the ViewModel tests trivial: a fake implementing this interface needs
 * no database, no Robolectric, and no mocking framework.
 *
 * ## Why reads return Flow and writes are suspend
 *
 * Reads are a [Flow] because the local database is the single source of truth. The UI
 * subscribes once and is pushed every change — including changes made by the background sync
 * worker, which the UI never calls and cannot know about. A one-shot `suspend fun getAll()`
 * would return data that is stale the moment sync completes, and the screen would need
 * manual refresh logic to compensate.
 *
 * Writes are `suspend` because they complete: there is exactly one result and nothing to
 * observe afterwards. The Flow from the read side emits the consequence.
 */
interface BeneficiaryRepository {

    /** All records, newest first. Re-emits on every local or sync-driven change. */
    fun observeAll(): Flow<List<Beneficiary>>

    /** A single record, or null once it no longer exists. */
    fun observeById(id: BeneficiaryId): Flow<Beneficiary?>

    /**
     * Creates or replaces a record locally and marks it for sync.
     *
     * Returns immediately after the local write. It does NOT wait for the server — that is
     * the entire point of offline-first: the health worker gets confirmation from the device,
     * and the sync engine reconciles later.
     */
    suspend fun upsert(beneficiary: Beneficiary): Outcome<Unit, RepositoryError>

    /** Records the sync engine still needs to push. */
    suspend fun pendingSync(): List<Beneficiary>
}

/**
 * Failure modes a caller can act on.
 *
 * Note the absence of a network error: nothing in this interface touches the network. A
 * write that cannot reach the server is not a failure here — it is a record with
 * [com.astracare.core.model.SyncStatus.PENDING], which is a normal, expected state.
 */
sealed interface RepositoryError {

    /** The local write failed — disk full, database corrupt, encryption key unavailable. */
    data class StorageFailure(val cause: Throwable) : RepositoryError

    data class NotFound(val id: BeneficiaryId) : RepositoryError
}
