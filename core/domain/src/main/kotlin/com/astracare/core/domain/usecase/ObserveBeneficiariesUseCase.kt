package com.astracare.core.domain.usecase

import androidx.paging.PagingData
import com.astracare.core.domain.repository.BeneficiaryRepository
import com.astracare.core.model.Beneficiary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Streams records for the history list, paged and ordered for field use.
 *
 * ## The justification this used to have, and the one it has now
 *
 * On Day 7 this class held the ordering rule — a `sortedWith` comparator over the whole list —
 * and its documentation said that was why it existed rather than having the ViewModel call the
 * repository directly. Day 12 moved ordering into SQL, because Paging loads pages and a
 * comparator applied to one page sorts that page and nothing else.
 *
 * So the original reason is gone, and saying so is better than leaving a stale claim in place.
 * The rule now lives in [com.astracare.core.domain.ordering.RecordAttentionOrder], where it is
 * still a domain decision, and the DAO binds it.
 *
 * Two reasons remain, and they are enough:
 *
 *  - It is the seam. `:feature:patients` depends on `:core:domain` and not on `:core:data`, so
 *    a ViewModel test supplies a fake implementing the repository interface and needs neither
 *    Room nor Paging's Room integration. Deleting this class would mean the ViewModel reaching
 *    for the repository directly, which is the same dependency but with one less name on it.
 *  - Consistency. Every other read in this codebase goes through a use case. A rule that holds
 *    most of the time is not a rule, and the next person will not know which convention is the
 *    live one.
 *
 * It is thin. Thin is not the same as pointless, but it is worth being able to say which.
 */
class ObserveBeneficiariesUseCase @Inject constructor(
    private val repository: BeneficiaryRepository,
) {

    operator fun invoke(): Flow<PagingData<Beneficiary>> = repository.pagedRecords()
}
