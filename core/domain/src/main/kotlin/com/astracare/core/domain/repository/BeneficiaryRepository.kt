package com.astracare.core.domain.repository

import androidx.paging.PagingData
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
 *
 * ## Why a domain interface names PagingData
 *
 * This is the one place the layering is genuinely arguable, so it is argued here rather than
 * discovered later. [PagingData] comes from `androidx.paging:paging-common`, which publishes a
 * plain JVM jar with no Android dependency — so `:core:domain` remains a Kotlin/JVM module and
 * the compiler still rejects `import android.*` here. The rule this project actually enforces
 * is intact.
 *
 * The honest cost is that the domain's contract now speaks a pagination vocabulary, and
 * pagination is arguably a presentation concern. Two alternatives were weighed:
 *
 *  - **Keep Paging out of the domain entirely.** The ViewModel would then have to call
 *    `:core:data` directly, so `:feature:patients` would depend on the data module. That
 *    destroys the property proved on Day 9 — that swapping the implementation is a one-line
 *    change to a single `@Binds` — and it is a much larger loss than naming a type.
 *  - **Define an in-house pagination abstraction** for `:core:data` to adapt Paging onto.
 *    Purest on paper, and in practice a worse reimplementation of a contract that already
 *    exists, maintained forever to avoid writing one import.
 *
 * So the type is named. See DECISION_LOG 7.1.
 */
interface BeneficiaryRepository {

    /**
     * Records for the history screen, paged, ordered by
     * [com.astracare.core.domain.ordering.RecordAttentionOrder].
     *
     * Replaced `observeAll(): Flow<List<Beneficiary>>` on Day 12. The old signature promised
     * to hand the whole table to the caller, which is fine at the few hundred records one
     * health worker captures and is a design that has no answer at ten thousand.
     */
    fun pagedRecords(): Flow<PagingData<Beneficiary>>

    /**
     * How many records the server has not acknowledged.
     *
     * A separate query rather than something derived from [pagedRecords], because it cannot be
     * derived from it: paged data is, by construction, only the rows currently loaded. Counting
     * what is on screen would report "2 waiting to sync" on a list whose next page holds forty
     * more — which is worse than not showing the number, because it looks authoritative.
     */
    fun observeAwaitingSyncCount(): Flow<Int>

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
