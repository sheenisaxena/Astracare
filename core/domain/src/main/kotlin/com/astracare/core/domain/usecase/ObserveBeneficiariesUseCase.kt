package com.astracare.core.domain.usecase

import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.model.Beneficiary
import com.astracare.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Streams all records for the history list, ordered for field use.
 *
 * This use case is thin — it could be argued the ViewModel should call the repository
 * directly. It exists because the ordering rule is a domain decision, not a display
 * preference, and it would otherwise be duplicated (and eventually diverge) across the
 * history screen, the sync status screen, and any export.
 *
 * The rule: records needing attention first, then newest first. A health worker's most urgent
 * question at the end of a day with no signal is "what is still only on this handset?" — so
 * the app answers it without them having to look for it.
 */
class ObserveBeneficiariesUseCase @Inject constructor(
    private val repository: BeneficiaryRepository,
) {

    operator fun invoke(): Flow<List<Beneficiary>> =
        repository.observeAll().map { beneficiaries ->
            beneficiaries.sortedWith(
                compareBy<Beneficiary> { ATTENTION_ORDER.indexOf(it.syncStatus) }
                    .thenByDescending { it.recordedAt },
            )
        }

    private companion object {
        /**
         * Declared as an ordered list rather than numeric priorities, so the intent is the
         * declaration — no magic numbers, and reordering means moving a line.
         *
         * CONFLICTED outranks FAILED because it needs a human decision; FAILED and PENDING
         * will resolve themselves once there is signal.
         */
        val ATTENTION_ORDER = listOf(
            SyncStatus.CONFLICTED,
            SyncStatus.FAILED,
            SyncStatus.PENDING,
            SyncStatus.SYNCED,
        )
    }
}
